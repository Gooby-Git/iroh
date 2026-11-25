// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista's native crate (owner code), relicensed into Iroh.
//! JNI entry points for `dev.elemore.iroh.lod.nat.LodNative`. Calls are per section, per chunk or per batch, never per block.
//! IO errors become `java.io.IOException`, panics `java.lang.IllegalStateException`; nothing unwinds into the JVM.

use crate::cell::{Key, VOLUME};
use crate::packfile::Stored;
use crate::store::Store;
use crate::{downsample, hash, hidden, packed};
use jni::objects::{JByteArray, JClass, JIntArray, JLongArray, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jlongArray, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::cell::RefCell;
use std::io;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;

pub(crate) enum Fail {
    Io(io::Error),
    Jni(#[allow(dead_code)] jni::errors::Error),
}

impl From<io::Error> for Fail {
    fn from(e: io::Error) -> Self {
        Fail::Io(e)
    }
}
impl From<jni::errors::Error> for Fail {
    fn from(e: jni::errors::Error) -> Self {
        Fail::Jni(e)
    }
}

pub(crate) type R<T> = Result<T, Fail>;

/// Runs `f`, turning errors and panics into Java exceptions (the return value is then ignored by Java).
pub(crate) fn guard<'l, T>(env: &mut JNIEnv<'l>, default: T, f: impl FnOnce(&mut JNIEnv<'l>) -> R<T>) -> T {
    let r = catch_unwind(AssertUnwindSafe(|| f(env)));
    match r {
        Ok(Ok(v)) => v,
        Ok(Err(Fail::Io(e))) => {
            if !env.exception_check().unwrap_or(false) {
                let _ = env.throw_new("java/io/IOException", format!("Iroh LOD native: {e}"));
            }
            default
        }
        Ok(Err(Fail::Jni(_))) => default, // a Java exception is already pending
        Err(p) => {
            let msg = p.downcast_ref::<&str>().map(|s| s.to_string()).or_else(|| p.downcast_ref::<String>().cloned()).unwrap_or_default();
            if !env.exception_check().unwrap_or(false) {
                let _ = env.throw_new("java/lang/IllegalStateException", format!("Iroh LOD native panic: {msg}"));
            }
            default
        }
    }
}

thread_local! {
    static CELLS: RefCell<Vec<i32>> = RefCell::new(vec![0; VOLUME]);
}

pub(crate) fn as_u32(v: &[i32]) -> &[u32] {
    // i32 and u32 share size and alignment
    unsafe { std::slice::from_raw_parts(v.as_ptr() as *const u32, v.len()) }
}
pub(crate) fn as_u32_mut(v: &mut [i32]) -> &mut [u32] {
    unsafe { std::slice::from_raw_parts_mut(v.as_mut_ptr() as *mut u32, v.len()) }
}
pub(crate) fn as_i32(v: &[u32]) -> &[i32] {
    unsafe { std::slice::from_raw_parts(v.as_ptr() as *const i32, v.len()) }
}

/// The store behind a handle. Stores are never freed (a closed one only flushes and stops background work), so a
/// worker that still holds the handle after a world closes can't touch freed memory.
fn store<'a>(h: jlong) -> &'a Store {
    assert!(h != 0, "Iroh LOD native: null store handle");
    unsafe { &*(h as *const Store) }
}

fn read_cells(env: &mut JNIEnv, arr: &JIntArray, buf: &mut [i32]) -> R<()> {
    let n = env.get_array_length(arr)?;
    if n as usize != VOLUME {
        return Err(Fail::Io(io::Error::new(io::ErrorKind::InvalidInput, format!("section needs {VOLUME} cells, got {n}"))));
    }
    env.get_int_array_region(arr, 0, buf)?;
    Ok(())
}

fn bool_j(b: bool) -> jboolean {
    if b { JNI_TRUE } else { JNI_FALSE }
}

fn bytes<'l>(env: &mut JNIEnv<'l>, v: Option<Vec<u8>>) -> R<jbyteArray> {
    Ok(match v {
        None => std::ptr::null_mut(),
        Some(v) => env.byte_array_from_slice(&v)?.into_raw(),
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_abi(_env: JNIEnv, _c: JClass) -> jint {
    crate::ABI
}

/// 1 when this build contains the `seed` worldgen (LodSeedNative is then usable), else 0.
#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_hasSeed(_env: JNIEnv, _c: JClass) -> jint {
    crate::HAS_SEED as jint
}

/// Sets the calling thread's scheduling priority: 0 normal, 1 low, 2 idle. Returns 0 on success, negative otherwise.
#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_threadPriority(_env: JNIEnv, _c: JClass, level: jint) -> jint {
    crate::threadprio::set(level)
}

// ------------------------------------------------------------------------------------------------ store

#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_storeOpen<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, root: JString<'l>) -> jlong {
    guard(&mut env, 0, |env| {
        let root: String = env.get_string(&root)?.into();
        let s = Store::open(&PathBuf::from(root))?;
        Ok(Box::into_raw(Box::new(s)) as jlong)
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_storeClose<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, h: jlong) {
    guard(&mut env, (), |_| Ok(store(h).close()?))
}

#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_storeSetClass<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, h: jlong, id: jint, cls: jint) {
    guard(&mut env, (), |_| {
        store(h).set_class(id as u32, cls as u8);
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_storeSetClasses<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, h: jlong, classes: JByteArray<'l>, count: jint) {
    guard(&mut env, (), |env| {
        let v = env.convert_byte_array(&classes)?;
        let n = (count.max(0) as usize).min(v.len());
        store(h).set_classes(&v[..n]);
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_storeGet<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, h: jlong, key: jlong, out: JIntArray<'l>) -> jboolean {
    guard(&mut env, JNI_FALSE, |env| {
        CELLS.with(|b| {
            let buf = &mut *b.borrow_mut();
            if !store(h).get(Key::unpack(key), as_u32_mut(buf))? {
                return Ok(JNI_FALSE);
            }
            env.set_int_array_region(&out, 0, buf)?;
            Ok(JNI_TRUE)
        })
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_storeGetPacked<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, h: jlong, key: jlong, uniform_too: jboolean) -> jbyteArray {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let v = store(h).get_packed(Key::unpack(key), uniform_too != 0)?;
        bytes(env, v)
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_storeHash<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, h: jlong, key: jlong) -> jlong {
    guard(&mut env, 0, |_| Ok(store(h).hash(Key::unpack(key))? as jlong))
}

/// 1 sky-lit, 0 not, -1 never stored.
#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_storeSkyLit<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, h: jlong, key: jlong) -> jint {
    guard(&mut env, -1, |_| Ok(match store(h).sky_lit(Key::unpack(key))? {
        None => -1,
        Some(true) => 1,
        Some(false) => 0,
    }))
}

#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_storeHas<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, h: jlong, key: jlong) -> jboolean {
    guard(&mut env, JNI_FALSE, |_| Ok(bool_j(store(h).has(Key::unpack(key))?)))
}

/// Bit 32 set when the section is uniform; its cell word in the low half.
#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_storeUniform<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, h: jlong, key: jlong) -> jlong {
    guard(&mut env, 0, |_| Ok(store(h).uniform_value(Key::unpack(key))?.map_or(0, |u| 1i64 << 32 | u as i64)))
}

#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_storePut<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, h: jlong, key: jlong, cells: JIntArray<'l>) -> jboolean {
    guard(&mut env, JNI_FALSE, |env| {
        CELLS.with(|b| {
            let buf = &mut *b.borrow_mut();
            read_cells(env, &cells, buf)?;
            Ok(bool_j(store(h).put(Key::unpack(key), as_u32(buf))?))
        })
    })
}

/// `[kind (1 uniform, 2 payload), hash (8, LE), then cell (4, LE) or the payload]`, or null when never seen.
#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_storeGetStored<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, h: jlong, key: jlong) -> jbyteArray {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let v = store(h).get_stored(Key::unpack(key))?.map(|st| {
            let mut v = Vec::new();
            match st {
                Stored::Uniform { hash, cell } => {
                    v.push(1);
                    v.extend_from_slice(&hash.to_le_bytes());
                    v.extend_from_slice(&cell.to_le_bytes());
                }
                Stored::Payload { hash, payload } => {
                    v.reserve(9 + payload.len());
                    v.push(2);
                    v.extend_from_slice(&hash.to_le_bytes());
                    v.extend_from_slice(&payload);
                }
            }
            v
        });
        bytes(env, v)
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_storePutStored<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    h: jlong,
    key: jlong,
    hash: jlong,
    uniform: jboolean,
    cell: jint,
    payload: JByteArray<'l>,
) -> jboolean {
    guard(&mut env, JNI_FALSE, |env| {
        let st = if uniform != 0 {
            Stored::Uniform { hash: hash as u64, cell: cell as u32 }
        } else {
            Stored::Payload { hash: hash as u64, payload: env.convert_byte_array(&payload)? }
        };
        Ok(bool_j(store(h).put_stored(Key::unpack(key), &st)?))
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_storeHasChunk<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, h: jlong, cx: jint, cz: jint) -> jboolean {
    guard(&mut env, JNI_FALSE, |_| Ok(bool_j(store(h).has_chunk(cx, cz)?)))
}

#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_storeMarkChunk<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, h: jlong, cx: jint, cz: jint) {
    guard(&mut env, (), |_| Ok(store(h).mark_chunk(cx, cz)?))
}

#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_storeFlush<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, h: jlong) {
    guard(&mut env, (), |_| Ok(store(h).flush()?))
}

#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_storeVersion<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, h: jlong, level: jint, rx: jint, rz: jint) -> jint {
    guard(&mut env, 0, |_| Ok(store(h).version(level, rx, rz)?))
}

#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_storeUpgrade<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, h: jlong, level: jint, rx: jint, rz: jint) -> jboolean {
    guard(&mut env, JNI_FALSE, |_| Ok(bool_j(store(h).upgrade(level, rx, rz)?)))
}

/// Rebuilds these parents (one level) from their children; returns the keys whose content changed.
#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_storeCascade<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, h: jlong, keys: JLongArray<'l>) -> jlongArray {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let n = env.get_array_length(&keys)? as usize;
        let mut raw = vec![0i64; n];
        env.get_long_array_region(&keys, 0, &mut raw)?;
        let ks: Vec<Key> = raw.iter().map(|&v| Key::unpack(v)).collect();
        let changed = store(h).cascade(&ks)?;
        let out: Vec<i64> = changed.iter().map(|k| k.pack()).collect();
        let arr = env.new_long_array(out.len() as i32)?;
        env.set_long_array_region(&arr, 0, &out)?;
        Ok(arr.into_raw())
    })
}

// ------------------------------------------------------------------------------------------------ codec (parity tests)

#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_hashCells<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, cells: JIntArray<'l>) -> jlong {
    guard(&mut env, 0, |env| {
        let n = env.get_array_length(&cells)? as usize;
        let mut v = vec![0i32; n];
        env.get_int_array_region(&cells, 0, &mut v)?;
        Ok(hash::of(as_u32(&v)) as jlong)
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_hashUniform<'l>(_env: JNIEnv<'l>, _c: JClass<'l>, cell: jint) -> jlong {
    hash::of_uniform(cell as u32) as jlong
}

#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_encodePacked<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, cells: JIntArray<'l>) -> jbyteArray {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let mut v = vec![0i32; VOLUME];
        read_cells(env, &cells, &mut v)?;
        bytes(env, Some(packed::encode(as_u32(&v))))
    })
}

/// Hidden-cell normalisation with a class table (index = block id); null when nothing changes.
#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_hiddenNormalize<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, cells: JIntArray<'l>, classes: JByteArray<'l>) -> jni::sys::jintArray {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let mut v = vec![0i32; VOLUME];
        read_cells(env, &cells, &mut v)?;
        let t = dense(env.convert_byte_array(&classes)?);
        let mut out = vec![0u32; VOLUME];
        if !hidden::normalize(as_u32(&v), &t, &mut out) {
            return Ok(std::ptr::null_mut());
        }
        let arr = env.new_int_array(VOLUME as i32)?;
        env.set_int_array_region(&arr, 0, as_i32(&out))?;
        Ok(arr.into_raw())
    })
}

/// Downsamples eight children given back to back (8 x VOLUME); bit o of `present` says child o exists.
#[no_mangle]
pub extern "system" fn Java_dev_elemore_iroh_lod_nat_LodNative_downsample<'l>(
    mut env: JNIEnv<'l>,
    _c: JClass<'l>,
    kids: JIntArray<'l>,
    present: jint,
    classes: JByteArray<'l>,
) -> jni::sys::jintArray {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let mut v = vec![0i32; 8 * VOLUME];
        env.get_int_array_region(&kids, 0, &mut v)?;
        let t = dense(env.convert_byte_array(&classes)?);
        let all = as_u32(&v);
        let children: [Option<&[u32]>; 8] = std::array::from_fn(|o| (present >> o & 1 != 0).then(|| &all[o * VOLUME..(o + 1) * VOLUME]));
        let mut out = vec![0u32; VOLUME];
        downsample::Downsampler::default().downsample(&children, &t, &mut out);
        let arr = env.new_int_array(VOLUME as i32)?;
        env.set_int_array_region(&arr, 0, as_i32(&out))?;
        Ok(arr.into_raw())
    })
}

fn dense(bytes: Vec<u8>) -> Box<[u8; 65536]> {
    crate::cell::Classes { bytes }.dense()
}
