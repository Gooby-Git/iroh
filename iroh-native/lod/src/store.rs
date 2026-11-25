// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista's native crate (owner code), relicensed into Iroh.
//! `VistaStore`: all sections of one dimension, `<root>/L<level>/<rx>.<rz>.vpk`, with a small LRU of open files.
//! Block and biome name tables stay in Java (they hold Minecraft names); only the block classes are mirrored here.

use crate::cell::{Key, VOLUME};
use crate::downsample::Downsampler;
use crate::packfile::{self, PackFile, Stored, HEIGHT};
use crate::{cell, packed};
use std::cell::RefCell;
use std::collections::HashMap;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};

const OPEN_FILES: usize = 48;

type FileRef = Arc<Mutex<PackFile>>;

pub struct Store {
    root: PathBuf,
    open: Mutex<HashMap<u64, (FileRef, u64)>>,
    clock: AtomicU64,
    classes: RwLock<Box<[u8; 65536]>>,
    pub closed: AtomicBool,
}

thread_local! {
    static SCRATCH: RefCell<(Downsampler, Vec<Vec<u32>>, Vec<u32>)> =
        RefCell::new((Downsampler::default(), (0..8).map(|_| vec![0u32; VOLUME]).collect(), vec![0u32; VOLUME]));
    static RAW: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) };
}

pub fn in_range(k: Key) -> bool {
    k.y >= 0 && k.y < HEIGHT
}

fn entry(k: Key) -> usize {
    PackFile::entry(k.x & 15, k.y, k.z & 15)
}

pub fn file_for(root: &Path, level: i32, rx: i32, rz: i32) -> PathBuf {
    root.join(format!("L{level}")).join(format!("{rx}.{rz}.vpk"))
}

impl Store {
    pub fn open(root: &Path) -> io::Result<Store> {
        std::fs::create_dir_all(root)?;
        Ok(Store {
            root: root.to_path_buf(),
            open: Mutex::new(HashMap::new()),
            clock: AtomicU64::new(0),
            classes: RwLock::new(Box::new([cell::class::SOLID; 65536])),
            closed: AtomicBool::new(false),
        })
    }

    pub fn set_class(&self, id: u32, cls: u8) {
        if id < 65536 {
            self.classes.write().unwrap()[id as usize] = cls;
        }
    }

    pub fn set_classes(&self, bytes: &[u8]) {
        let mut t = self.classes.write().unwrap();
        let n = bytes.len().min(65536);
        t[..n].copy_from_slice(&bytes[..n]);
    }

    fn classes(&self) -> Box<[u8; 65536]> {
        self.classes.read().unwrap().clone()
    }

    fn file(&self, level: i32, rx: i32, rz: i32, create: bool) -> io::Result<Option<FileRef>> {
        let id = (level as u64) << 56 | ((rx as u64) & 0xFFF_FFFF) << 28 | (rz as u64) & 0xFFF_FFFF;
        let stamp = self.clock.fetch_add(1, Ordering::Relaxed);
        let mut open = self.open.lock().unwrap();
        if let Some((f, s)) = open.get_mut(&id) {
            *s = stamp;
            return Ok(Some(f.clone()));
        }
        let path = file_for(&self.root, level, rx, rz);
        if !create && !path.exists() {
            return Ok(None);
        }
        let f = Arc::new(Mutex::new(PackFile::open(&path, level, rx, rz)?));
        open.insert(id, (f.clone(), stamp));
        if open.len() > OPEN_FILES {
            // the least recently used file nobody holds and that isn't being rewritten
            let victim = open
                .iter()
                .filter(|(k, (f, _))| **k != id && Arc::strong_count(f) == 1 && !f.lock().unwrap().upgrading)
                .min_by_key(|(_, (_, s))| *s)
                .map(|(k, _)| *k);
            if let Some(v) = victim {
                let (f, _) = open.remove(&v).unwrap();
                f.lock().unwrap().flush()?;
            }
        }
        Ok(Some(f))
    }

    fn with<T>(&self, k: Key, create: bool, absent: T, op: impl FnOnce(&mut PackFile, usize) -> io::Result<T>) -> io::Result<T> {
        match self.file(k.level, k.x >> 4, k.z >> 4, create)? {
            None => Ok(absent),
            Some(f) => op(&mut f.lock().unwrap(), entry(k)),
        }
    }

    /// Cells into `out`; false when never seen.
    pub fn get(&self, k: Key, out: &mut [u32]) -> io::Result<bool> {
        if !in_range(k) {
            return Ok(false);
        }
        let Some(f) = self.file(k.level, k.x >> 4, k.z >> 4, false)? else { return Ok(false) };
        let e = entry(k);
        let payload = {
            let f = f.lock().unwrap();
            if let Some(u) = f.uniform_value(e) {
                out.fill(u);
                return Ok(true);
            }
            match f.read_payload(e)? {
                None => return Ok(false),
                Some(p) => p,
            }
        };
        RAW.with(|r| {
            let raw = &mut *r.borrow_mut();
            packfile::unwrap_into(&payload, raw)?;
            packed::decode_into(raw, out).map_err(|b| io::Error::new(io::ErrorKind::InvalidData, b.0))
        })?;
        Ok(true)
    }

    /// Packed bytes (not uniform): what the GPU and the network take.
    pub fn get_packed(&self, k: Key, uniform_too: bool) -> io::Result<Option<Vec<u8>>> {
        if !in_range(k) {
            return Ok(None);
        }
        let payload = self.with(k, false, None, |f, e| {
            if let Some(u) = f.uniform_value(e) {
                return Ok(if uniform_too { Some(Err(u)) } else { None });
            }
            Ok(f.read_payload(e)?.map(Ok))
        })?;
        match payload {
            None => Ok(None),
            Some(Err(u)) => Ok(Some(packed::uniform(u))),
            Some(Ok(p)) => Ok(Some(packfile::unwrap(&p)?)),
        }
    }

    /// Whether any cell of the section has sky light (it is open to the sky somewhere: the surface layer).
    /// None when the section was never stored. Reads only the palette.
    pub fn sky_lit(&self, k: Key) -> io::Result<Option<bool>> {
        if !in_range(k) {
            return Ok(None);
        }
        let payload = self.with(k, false, None, |f, e| {
            if let Some(u) = f.uniform_value(e) {
                return Ok(Some(Err(u)));
            }
            Ok(f.read_payload(e)?.map(Ok))
        })?;
        match payload {
            None => Ok(None),
            Some(Err(u)) => Ok(Some(cell::sky(u) > 0)),
            Some(Ok(p)) => RAW.with(|r| {
                let raw = &mut *r.borrow_mut();
                packfile::unwrap_into(&p, raw)?;
                Ok(Some(packed::palette_sky_lit(raw)))
            }),
        }
    }

    pub fn hash(&self, k: Key) -> io::Result<u64> {
        if !in_range(k) {
            return Ok(0);
        }
        self.with(k, false, 0, |f, e| Ok(f.hash(e)))
    }

    pub fn has(&self, k: Key) -> io::Result<bool> {
        if !in_range(k) {
            return Ok(false);
        }
        self.with(k, false, false, |f, e| Ok(f.has(e)))
    }

    pub fn uniform_value(&self, k: Key) -> io::Result<Option<u32>> {
        if !in_range(k) {
            return Ok(None);
        }
        self.with(k, false, None, |f, e| Ok(f.uniform_value(e)))
    }

    /// True when the stored content changed.
    pub fn put(&self, k: Key, cells: &[u32]) -> io::Result<bool> {
        self.put_with(k, cells, &self.classes())
    }

    fn put_with(&self, k: Key, cells: &[u32], classes: &[u8; 65536]) -> io::Result<bool> {
        if !in_range(k) {
            return Ok(false);
        }
        let f = self.file(k.level, k.x >> 4, k.z >> 4, true)?.unwrap();
        let e = entry(k);
        let (h, uniform) = packfile::hash_of(cells);
        {
            let mut g = f.lock().unwrap();
            if g.same(e, h) {
                return Ok(false);
            }
            if let Some(u) = uniform {
                g.put_uniform(e, u)?;
                return Ok(true);
            }
        }
        // compress outside the file lock: other sections of this file stay readable meanwhile
        let payload = packfile::encode_payload(cells, Some(classes), packfile::LEVEL_LIVE);
        f.lock().unwrap().put_payload(e, &payload, h)?;
        Ok(true)
    }

    pub fn get_stored(&self, k: Key) -> io::Result<Option<Stored>> {
        if !in_range(k) {
            return Ok(None);
        }
        self.with(k, false, None, |f, e| f.read_stored(e))
    }

    pub fn put_stored(&self, k: Key, st: &Stored) -> io::Result<bool> {
        if !in_range(k) {
            return Ok(false);
        }
        self.with(k, true, false, |f, e| f.write_stored(e, st))
    }

    pub fn has_chunk(&self, cx: i32, cz: i32) -> io::Result<bool> {
        match self.file(0, cx >> 5, cz >> 5, false)? {
            None => Ok(false),
            Some(f) => Ok(f.lock().unwrap().has_chunk(cx & 31, cz & 31)),
        }
    }

    pub fn mark_chunk(&self, cx: i32, cz: i32) -> io::Result<()> {
        let f = self.file(0, cx >> 5, cz >> 5, true)?.unwrap();
        let r = f.lock().unwrap().mark_chunk(cx & 31, cz & 31);
        r
    }

    pub fn version(&self, level: i32, rx: i32, rz: i32) -> io::Result<i32> {
        Ok(self.file(level, rx, rz, false)?.map(|f| f.lock().unwrap().version()).unwrap_or(0))
    }

    pub fn upgrade(&self, level: i32, rx: i32, rz: i32) -> io::Result<bool> {
        if self.closed.load(Ordering::Relaxed) {
            return Ok(false);
        }
        match self.file(level, rx, rz, false)? {
            None => Ok(false),
            Some(f) => packfile::upgrade(&f, &self.classes()),
        }
    }

    /// Rebuilds the given parents (all one level, L >= 1) from their children; returns those whose content changed.
    pub fn cascade(&self, keys: &[Key]) -> io::Result<Vec<Key>> {
        let classes = self.classes();
        let mut changed = Vec::new();
        SCRATCH.with(|s| -> io::Result<()> {
            let s = &mut *s.borrow_mut();
            let (ds, kids, out) = (&mut s.0, &mut s.1, &mut s.2);
            for &k in keys {
                let mut present = [false; 8];
                let mut any = false;
                for o in 0..8 {
                    present[o] = self.get(k.child(o as i32), &mut kids[o])?;
                    any |= present[o];
                }
                if !any {
                    continue;
                }
                let children: [Option<&[u32]>; 8] = std::array::from_fn(|o| present[o].then(|| &kids[o][..]));
                ds.downsample(&children, &classes, out);
                if self.put_with(k, out, &classes)? {
                    changed.push(k);
                }
            }
            Ok(())
        })?;
        Ok(changed)
    }

    pub fn flush(&self) -> io::Result<()> {
        let files: Vec<FileRef> = self.open.lock().unwrap().values().map(|(f, _)| f.clone()).collect();
        for f in files {
            f.lock().unwrap().flush()?;
        }
        Ok(())
    }

    pub fn close(&self) -> io::Result<()> {
        self.closed.store(true, Ordering::Relaxed);
        let files: Vec<FileRef> = self.open.lock().unwrap().drain().map(|(_, (f, _))| f).collect();
        for f in files {
            f.lock().unwrap().flush()?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    //! Store round trips on the real file system: these are the paths that differ by OS (positional IO, rename over
    //! an open file in the upgrade), so they also run under Wine for the Windows build.
    use super::*;
    use crate::cell::{self, Key, VOLUME};

    fn dir(name: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("iroh-lod-store-test-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        d
    }

    fn section(seed: u32) -> Vec<u32> {
        let mut r = seed.wrapping_mul(2654435761) | 1;
        (0..VOLUME)
            .map(|i| {
                r ^= r << 13;
                r ^= r >> 17;
                r ^= r << 5;
                let y = i >> 10;
                if y < 12 { cell::of(30 + r % 40, 0, 0, 2) } else if y < 14 { cell::of(27, 0, 0, 2) } else { cell::of(0, 15, 0, 2) }
            })
            .collect()
    }

    #[test]
    fn round_trip_reopen_and_cascade() {
        let root = dir("rt");
        let keys: Vec<Key> = (0..40).map(|i| Key::new(0, i * 3 - 50, i % 32, -i * 2)).collect();
        {
            let s = Store::open(&root).unwrap();
            for (i, k) in keys.iter().enumerate() {
                assert!(s.put(*k, &section(i as u32)).unwrap());
                assert!(!s.put(*k, &section(i as u32)).unwrap(), "same content is not rewritten");
            }
            s.put(Key::new(1, 5, 1, 5), &vec![cell::OPEN_AIR; VOLUME]).unwrap();
            s.mark_chunk(-3, 70).unwrap();
            s.close().unwrap();
        }
        let s = Store::open(&root).unwrap();
        let mut out = vec![0u32; VOLUME];
        for (i, k) in keys.iter().enumerate() {
            assert!(s.get(*k, &mut out).unwrap());
            assert_eq!(s.hash(*k).unwrap(), crate::hash::of(&section(i as u32)));
            assert!(s.get_packed(*k, false).unwrap().is_some());
        }
        assert_eq!(s.uniform_value(Key::new(1, 5, 1, 5)).unwrap(), Some(cell::OPEN_AIR));
        assert!(s.has_chunk(-3, 70).unwrap() && !s.has_chunk(-3, 71).unwrap());
        let parents: Vec<Key> = keys.iter().map(|k| k.parent()).collect();
        assert!(!s.cascade(&parents).unwrap().is_empty());
        assert!(s.cascade(&parents).unwrap().is_empty(), "a second cascade changes nothing");
        // many files at once: the LRU closes and reopens them
        for i in 0..120 {
            s.put(Key::new(2, i * 16, 0, 0), &section(i as u32)).unwrap();
        }
        for i in 0..120 {
            assert!(s.get(Key::new(2, i * 16, 0, 0), &mut out).unwrap());
        }
        s.close().unwrap();
        let _ = std::fs::remove_dir_all(&root);
    }

    /// A version-1 file (LZ4, 1 KiB sectors) rewritten in place as version 2, keeping hashes and presence.
    #[test]
    fn version_one_upgrades() {
        let root = dir("up");
        let path = file_for(&root, 0, 0, 0);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        let secs: Vec<Vec<u32>> = (0..20).map(section).collect();
        let mut head = vec![0u8; 4096];
        for (i, v) in [0x3150_4B56u32, 1, 0, 0, 0].iter().enumerate() {
            head[i * 4..i * 4 + 4].copy_from_slice(&v.to_le_bytes());
        }
        head[64] = 0b100; // chunk (2, 0)
        let mut index = vec![0u8; 8192 * 16];
        let mut data = Vec::new();
        let first = (4096 + 8192 * 16) / 1024;
        for (e, c) in secs.iter().enumerate() {
            let packed = crate::packed::encode(c);
            let lz = lz4_flex::block::compress(&packed);
            let mut payload = (packed.len() as u32).to_le_bytes().to_vec();
            payload.extend_from_slice(&lz);
            let sector = first + data.len() / 1024;
            index[e * 16..e * 16 + 4].copy_from_slice(&(sector as i32).to_le_bytes());
            index[e * 16 + 4..e * 16 + 8].copy_from_slice(&(payload.len() as i32).to_le_bytes());
            index[e * 16 + 8..e * 16 + 16].copy_from_slice(&crate::hash::of(c).to_le_bytes());
            data.extend_from_slice(&payload);
            data.resize(data.len().div_ceil(1024) * 1024, 0);
        }
        let mut file = head;
        file.extend_from_slice(&index);
        file.extend_from_slice(&data);
        std::fs::write(&path, &file).unwrap();

        let s = Store::open(&root).unwrap();
        let mut out = vec![0u32; VOLUME];
        assert_eq!(s.version(0, 0, 0).unwrap(), 1);
        for (e, c) in secs.iter().enumerate() {
            assert!(s.get(Key::new(0, e as i32 & 15, e as i32 >> 8, (e as i32 >> 4) & 15), &mut out).unwrap());
            assert_eq!(&out, c, "v1 read {e}");
        }
        assert!(s.upgrade(0, 0, 0).unwrap());
        assert_eq!(s.version(0, 0, 0).unwrap(), 2);
        for (e, c) in secs.iter().enumerate() {
            let k = Key::new(0, e as i32 & 15, e as i32 >> 8, (e as i32 >> 4) & 15);
            assert_eq!(s.hash(k).unwrap(), crate::hash::of(c), "hash kept {e}");
            assert!(s.get(k, &mut out).unwrap());
        }
        assert!(s.has_chunk(2, 0).unwrap());
        // still writable after the swap
        assert!(s.put(Key::new(0, 1, 5, 1), &section(99)).unwrap());
        s.close().unwrap();
        assert!(std::fs::metadata(&path).unwrap().len() < file.len() as u64, "smaller after the upgrade");
        let _ = std::fs::remove_dir_all(&root);
    }
}
