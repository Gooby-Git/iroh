// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista's native crate (owner code), relicensed into Iroh.
//! One Vista pack file (`PackFile.java`): 16 x 16 columns x 32 sections at one level. Same bytes on disk.
//!
//! ```text
//!   0..4096        header: magic, version, level, rx, rz; at 64 a 1024-bit chunk-presence map (level 0)
//!   4096..135168   index, 8192 x 16 bytes: i32 first sector, i32 length, i64 hash
//!   then           payload sectors (v1 1 KiB, v2 256 B): u32 packed length (bit 31 = Deflate, else LZ4), then data
//! ```
//! length 0 = never seen, -1 = uniform (cell word in the low half of the hash field).

use crate::cell::{self, VOLUME};
use crate::{hash, hidden, packed};
use std::cell::RefCell;
use std::fs::{File, OpenOptions};
use std::io;
use std::path::{Path, PathBuf};

pub const COLUMNS: i32 = 16;
pub const HEIGHT: i32 = 32;
pub const ENTRIES: usize = (COLUMNS * COLUMNS * HEIGHT) as usize;
const HEADER_BYTES: u64 = 4096;
const INDEX_BYTES: usize = ENTRIES * 16;
const SECTOR_V1: u64 = 1024;
const SECTOR_V2: u64 = 256;
const MAGIC: u32 = 0x3150_4B56;
pub const VERSION: i32 = 2;
const DEFLATED: u32 = 0x8000_0000;
const PRESENCE_AT: u64 = 64;
const UNIFORM: i32 = -1;
pub const LEVEL_LIVE: i32 = 6;
pub const LEVEL_UPGRADE: i32 = 9;

// ------------------------------------------------------------------------------------------- positional IO

#[cfg(unix)]
fn read_at(f: &File, buf: &mut [u8], mut pos: u64) -> io::Result<()> {
    use std::os::unix::fs::FileExt;
    let mut done = 0;
    while done < buf.len() {
        let n = f.read_at(&mut buf[done..], pos)?;
        if n == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "Vista pack file ends early"));
        }
        done += n;
        pos += n as u64;
    }
    Ok(())
}
#[cfg(unix)]
fn write_at(f: &File, buf: &[u8], pos: u64) -> io::Result<()> {
    use std::os::unix::fs::FileExt;
    f.write_all_at(buf, pos)
}
#[cfg(windows)]
fn read_at(f: &File, buf: &mut [u8], mut pos: u64) -> io::Result<()> {
    use std::os::windows::fs::FileExt;
    let mut done = 0;
    while done < buf.len() {
        let n = f.seek_read(&mut buf[done..], pos)?;
        if n == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "Vista pack file ends early"));
        }
        done += n;
        pos += n as u64;
    }
    Ok(())
}
#[cfg(windows)]
fn write_at(f: &File, mut buf: &[u8], mut pos: u64) -> io::Result<()> {
    use std::os::windows::fs::FileExt;
    while !buf.is_empty() {
        let n = f.seek_write(buf, pos)?;
        buf = &buf[n..];
        pos += n as u64;
    }
    Ok(())
}

// ------------------------------------------------------------------------------------------- compression

struct Codec {
    deflate: [Option<libdeflater::Compressor>; 13],
    inflate: libdeflater::Decompressor,
    packed: Vec<u8>,
    norm: Vec<u32>,
}

thread_local! {
    static CODEC: RefCell<Codec> = RefCell::new(Codec {
        deflate: Default::default(),
        inflate: libdeflater::Decompressor::new(),
        packed: Vec::with_capacity(160 * 1024),
        norm: vec![0; VOLUME],
    });
}

fn bad(msg: impl Into<String>) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, msg.into())
}

/// A stored payload back to the packed section (u32 head, then LZ4 or zlib-wrapped Deflate), into `out`.
pub fn unwrap_into(payload: &[u8], out: &mut Vec<u8>) -> io::Result<()> {
    if payload.len() < 4 {
        return Err(bad("short Vista payload"));
    }
    let head = u32::from_le_bytes(payload[..4].try_into().unwrap());
    let raw = (head & !DEFLATED) as usize;
    if raw > 4 * VOLUME + 4 * VOLUME + packed::HEADER {
        return Err(bad(format!("Vista payload claims {raw} bytes")));
    }
    out.clear();
    out.resize(raw, 0);
    if head & DEFLATED == 0 {
        let n = lz4_flex::block::decompress_into(&payload[4..], out).map_err(|e| bad(format!("bad LZ4 payload: {e}")))?;
        if n != raw {
            return Err(bad(format!("Vista payload decodes to {n} bytes, not {raw}")));
        }
        return Ok(());
    }
    CODEC.with(|c| {
        let n = c
            .borrow_mut()
            .inflate
            .zlib_decompress(&payload[4..], out)
            .map_err(|e| bad(format!("bad Vista payload: {e:?}")))?;
        if n != raw {
            return Err(bad(format!("Vista payload inflates to {n} bytes, not {raw}")));
        }
        Ok(())
    })
}

pub fn unwrap(payload: &[u8]) -> io::Result<Vec<u8>> {
    let mut v = Vec::new();
    unwrap_into(payload, &mut v)?;
    Ok(v)
}

fn wrap_with(c: &mut Codec, packed: &[u8], level: i32) -> Vec<u8> {
    let slot = level.clamp(1, 12) as usize;
    let comp = c.deflate[slot].get_or_insert_with(|| libdeflater::Compressor::new(libdeflater::CompressionLvl::new(slot as i32).unwrap()));
    let mut out = vec![0u8; 4 + comp.zlib_compress_bound(packed.len())];
    let n = comp.zlib_compress(packed, &mut out[4..]).expect("zlib bound");
    out[..4].copy_from_slice(&(packed.len() as u32 | DEFLATED).to_le_bytes());
    out.truncate(4 + n);
    out
}

/// A packed section as a stored payload: u32 length with bit 31 set, then zlib-wrapped Deflate (what Java's
/// `Inflater` reads).
pub fn wrap(packed: &[u8], level: i32) -> Vec<u8> {
    CODEC.with(|c| wrap_with(&mut c.borrow_mut(), packed, level))
}

/// Cells -> stored payload, hidden cells normalised when `classes` is given.
pub fn encode_payload(cells: &[u32], classes: Option<&[u8; 65536]>, level: i32) -> Vec<u8> {
    CODEC.with(|c| {
        let mut g = c.borrow_mut();
        let c = &mut *g;
        let mut packed = std::mem::take(&mut c.packed);
        let normed = match classes {
            Some(t) => hidden::normalize(cells, t, &mut c.norm),
            None => false,
        };
        packed::encode_into(if normed { &c.norm } else { cells }, &mut packed);
        let out = wrap_with(c, &packed, level);
        c.packed = packed;
        out
    })
}

// ------------------------------------------------------------------------------------------- bit set

#[derive(Default)]
struct Bits(Vec<u64>);

impl Bits {
    fn clear_all(&mut self) {
        self.0.clear();
    }
    fn get(&self, i: usize) -> bool {
        self.0.get(i >> 6).is_some_and(|w| w >> (i & 63) & 1 != 0)
    }
    fn set_range(&mut self, a: usize, b: usize, v: bool) {
        if b > self.0.len() * 64 {
            if !v {
                // clearing past the end: nothing set there
                let end = self.0.len() * 64;
                return self.set_range(a, b.min(end), v);
            }
            self.0.resize(b.div_ceil(64), 0);
        }
        for i in a..b {
            if v {
                self.0[i >> 6] |= 1 << (i & 63);
            } else {
                self.0[i >> 6] &= !(1 << (i & 63));
            }
        }
    }
    fn next_clear(&self, mut i: usize) -> usize {
        while self.get(i) {
            i += 1;
        }
        i
    }
    /// Next set bit at or after `i`, None past the end.
    fn next_set(&self, i: usize) -> Option<usize> {
        let mut w = i >> 6;
        if w >= self.0.len() {
            return None;
        }
        let mut word = self.0[w] & (!0u64 << (i & 63));
        loop {
            if word != 0 {
                return Some(w * 64 + word.trailing_zeros() as usize);
            }
            w += 1;
            if w >= self.0.len() {
                return None;
            }
            word = self.0[w];
        }
    }
}

// ------------------------------------------------------------------------------------------- file

pub enum Stored {
    Uniform { hash: u64, cell: u32 },
    Payload { hash: u64, payload: Vec<u8> },
}

impl Stored {
    pub fn hash(&self) -> u64 {
        match self {
            Stored::Uniform { hash, .. } | Stored::Payload { hash, .. } => *hash,
        }
    }
}

pub struct PackFile {
    path: PathBuf,
    level: i32,
    rx: i32,
    rz: i32,
    file: File,
    version: i32,
    sector_size: u64,
    data_start: usize,
    pub upgrading: bool,
    touched: Vec<usize>,
    sector: Vec<i32>,
    length: Vec<i32>,
    hash: Vec<u64>,
    presence: [u8; 128],
    used: Bits,
}

impl PackFile {
    pub fn open(path: &Path, level: i32, rx: i32, rz: i32) -> io::Result<PackFile> {
        if let Some(p) = path.parent() {
            std::fs::create_dir_all(p)?;
        }
        let fresh = std::fs::metadata(path).map(|m| m.len() < HEADER_BYTES + INDEX_BYTES as u64).unwrap_or(true);
        let file = OpenOptions::new().read(true).write(true).create(true).truncate(false).open(path)?;
        let mut f = PackFile {
            path: path.to_path_buf(),
            level,
            rx,
            rz,
            file,
            version: VERSION,
            sector_size: SECTOR_V2,
            data_start: 0,
            upgrading: false,
            touched: Vec::new(),
            sector: vec![0; ENTRIES],
            length: vec![0; ENTRIES],
            hash: vec![0; ENTRIES],
            presence: [0; 128],
            used: Bits::default(),
        };
        f.load(fresh)?;
        Ok(f)
    }

    fn load(&mut self, fresh: bool) -> io::Result<()> {
        self.sector.fill(0);
        self.length.fill(0);
        self.hash.fill(0);
        self.presence = [0; 128];
        self.used.clear_all();
        if fresh {
            self.set_version(VERSION);
            let mut head = vec![0u8; HEADER_BYTES as usize];
            for (i, v) in [MAGIC as i32, VERSION, self.level, self.rx, self.rz].iter().enumerate() {
                head[i * 4..i * 4 + 4].copy_from_slice(&v.to_le_bytes());
            }
            write_at(&self.file, &head, 0)?;
            write_at(&self.file, &vec![0u8; INDEX_BYTES], HEADER_BYTES)?;
        } else {
            let mut head = vec![0u8; HEADER_BYTES as usize];
            read_at(&self.file, &mut head, 0)?;
            let g = |i: usize| i32::from_le_bytes(head[i * 4..i * 4 + 4].try_into().unwrap());
            let (magic, v, lv, x, z) = (g(0) as u32, g(1), g(2), g(3), g(4));
            if magic != MAGIC || v < 1 || v > VERSION || lv != self.level || x != self.rx || z != self.rz {
                return Err(bad(format!("not a Vista pack file for L{} {},{}: {}", self.level, self.rx, self.rz, self.path.display())));
            }
            self.set_version(v);
            self.presence.copy_from_slice(&head[PRESENCE_AT as usize..PRESENCE_AT as usize + 128]);
            let mut index = vec![0u8; INDEX_BYTES];
            read_at(&self.file, &mut index, HEADER_BYTES)?;
            for i in 0..ENTRIES {
                let e = &index[i * 16..i * 16 + 16];
                self.sector[i] = i32::from_le_bytes(e[0..4].try_into().unwrap());
                self.length[i] = i32::from_le_bytes(e[4..8].try_into().unwrap());
                self.hash[i] = u64::from_le_bytes(e[8..16].try_into().unwrap());
                if self.length[i] > 0 {
                    let s = self.sector[i] as usize;
                    let n = self.sectors_for(self.length[i]);
                    self.used.set_range(s, s + n, true);
                }
            }
        }
        Ok(())
    }

    fn set_version(&mut self, v: i32) {
        self.version = v;
        self.sector_size = if v == 1 { SECTOR_V1 } else { SECTOR_V2 };
        self.data_start = ((HEADER_BYTES + INDEX_BYTES as u64) / self.sector_size) as usize;
    }

    pub fn version(&self) -> i32 {
        self.version
    }

    pub fn entry(lx: i32, ly: i32, lz: i32) -> usize {
        ((ly * COLUMNS + lz) * COLUMNS + lx) as usize
    }

    pub fn has(&self, e: usize) -> bool {
        self.length[e] != 0
    }

    pub fn hash(&self, e: usize) -> u64 {
        match self.length[e] {
            UNIFORM => hash::of_uniform(self.hash[e] as u32),
            0 => 0,
            _ => self.hash[e],
        }
    }

    pub fn uniform_value(&self, e: usize) -> Option<u32> {
        (self.length[e] == UNIFORM).then_some(self.hash[e] as u32)
    }

    fn sectors_for(&self, len: i32) -> usize {
        (len as u64).div_ceil(self.sector_size) as usize
    }

    /// The raw stored payload of an entry with one (not uniform, not absent).
    pub fn read_payload(&self, e: usize) -> io::Result<Option<Vec<u8>>> {
        let len = self.length[e];
        if len <= 0 {
            return Ok(None);
        }
        let mut buf = vec![0u8; len as usize];
        read_at(&self.file, &mut buf, self.sector[e] as u64 * self.sector_size)?;
        Ok(Some(buf))
    }

    pub fn read_stored(&self, e: usize) -> io::Result<Option<Stored>> {
        Ok(match self.length[e] {
            0 => None,
            UNIFORM => Some(Stored::Uniform { hash: hash::of_uniform(self.hash[e] as u32), cell: self.hash[e] as u32 }),
            _ => Some(Stored::Payload { hash: self.hash[e], payload: self.read_payload(e)?.unwrap() }),
        })
    }

    /// For a write: true when the entry already holds content with this hash.
    pub fn same(&self, e: usize, h: u64) -> bool {
        self.length[e] != 0 && self.hash(e) == h
    }

    pub fn put_uniform(&mut self, e: usize, cell: u32) -> io::Result<()> {
        self.release(e);
        self.set_entry(e, 0, UNIFORM, cell as u64)
    }

    pub fn put_payload(&mut self, e: usize, payload: &[u8], h: u64) -> io::Result<()> {
        self.release(e);
        let at = self.allocate(self.sectors_for(payload.len() as i32));
        write_at(&self.file, payload, at as u64 * self.sector_size)?;
        self.set_entry(e, at as i32, payload.len() as i32, h)
    }

    pub fn write_stored(&mut self, e: usize, st: &Stored) -> io::Result<bool> {
        if self.same(e, st.hash()) {
            return Ok(false);
        }
        match st {
            Stored::Uniform { cell, .. } => self.put_uniform(e, *cell)?,
            Stored::Payload { hash, payload } => self.put_payload(e, payload, *hash)?,
        }
        Ok(true)
    }

    pub fn has_chunk(&self, lx: i32, lz: i32) -> bool {
        let bit = (lz * 32 + lx) as usize;
        self.presence[bit >> 3] & (1 << (bit & 7)) != 0
    }

    pub fn mark_chunk(&mut self, lx: i32, lz: i32) -> io::Result<()> {
        let bit = (lz * 32 + lx) as usize;
        if self.presence[bit >> 3] & (1 << (bit & 7)) != 0 {
            return Ok(());
        }
        self.presence[bit >> 3] |= 1 << (bit & 7);
        write_at(&self.file, &self.presence[bit >> 3..(bit >> 3) + 1], PRESENCE_AT + (bit >> 3) as u64)
    }

    fn copy_presence(&mut self, from: &[u8; 128]) -> io::Result<()> {
        self.presence = *from;
        write_at(&self.file, &self.presence, PRESENCE_AT)
    }

    fn set_entry(&mut self, e: usize, at: i32, len: i32, h: u64) -> io::Result<()> {
        if self.upgrading {
            self.touched.push(e);
        }
        self.sector[e] = at;
        self.length[e] = len;
        self.hash[e] = h;
        let mut b = [0u8; 16];
        b[0..4].copy_from_slice(&at.to_le_bytes());
        b[4..8].copy_from_slice(&len.to_le_bytes());
        b[8..16].copy_from_slice(&h.to_le_bytes());
        write_at(&self.file, &b, HEADER_BYTES + e as u64 * 16)
    }

    fn release(&mut self, e: usize) {
        if self.length[e] > 0 {
            let s = self.sector[e] as usize;
            let n = self.sectors_for(self.length[e]);
            self.used.set_range(s, s + n, false);
        }
    }

    /// First fit from the data start, as `BitSet.nextClearBit` / `nextSetBit` did.
    fn allocate(&mut self, need: usize) -> usize {
        let mut at = self.data_start;
        loop {
            at = self.used.next_clear(at);
            match self.used.next_set(at) {
                None => break,
                Some(end) if end - at >= need => break,
                Some(end) => at = end,
            }
        }
        self.used.set_range(at, at + need, true);
        at
    }

    pub fn flush(&self) -> io::Result<()> {
        self.file.sync_data()
    }

    pub fn count(&self) -> usize {
        self.length.iter().filter(|&&l| l != 0).count()
    }
}

/// Background rewrite of a version-1 file as version 2, keeping every hash (`PackFile.upgrade`). The file stays
/// usable meanwhile: entries are read one at a time under its lock, and entries written during the rewrite are
/// copied across at the end, under the lock, before the new file takes the old one's place.
pub fn upgrade(file: &std::sync::Mutex<PackFile>, classes: &[u8; 65536]) -> io::Result<bool> {
    let (path, level, rx, rz) = {
        let mut f = file.lock().unwrap();
        if f.version >= VERSION || f.upgrading {
            return Ok(false);
        }
        f.upgrading = true;
        f.touched.clear();
        (f.path.clone(), f.level, f.rx, f.rz)
    };
    let mut name = path.file_name().unwrap().to_os_string();
    name.push(".v2tmp");
    let tmp = path.with_file_name(name);
    let result = (|| -> io::Result<bool> {
        let _ = std::fs::remove_file(&tmp);
        let mut out = PackFile::open(&tmp, level, rx, rz)?;
        let mut cells = vec![0u32; VOLUME];
        let mut raw = Vec::new();
        for e in 0..ENTRIES {
            let st = file.lock().unwrap().read_stored(e)?;
            match st {
                None => {}
                Some(Stored::Uniform { cell, .. }) => out.put_uniform(e, cell)?,
                Some(Stored::Payload { hash, payload }) => {
                    unwrap_into(&payload, &mut raw)?;
                    packed::decode_into(&raw, &mut cells).map_err(|b| bad(b.0))?;
                    let p = encode_payload(&cells, Some(classes), LEVEL_UPGRADE);
                    out.put_payload(e, &p, hash)?;
                }
            }
        }
        let mut f = file.lock().unwrap();
        let touched = std::mem::take(&mut f.touched);
        for e in touched {
            if let Some(st) = f.read_stored(e)? {
                // the same hash may already be there from the main pass; write regardless of that
                match &st {
                    Stored::Uniform { cell, .. } => out.put_uniform(e, *cell)?,
                    Stored::Payload { hash, payload } => out.put_payload(e, payload, *hash)?,
                }
            }
        }
        let presence = f.presence;
        out.copy_presence(&presence)?;
        out.flush()?;
        drop(out);
        f.file.sync_all()?;
        // Windows won't replace a file that is still open: let go of the old one first (Java closes its channel too).
        // Rust opens files with delete sharing, so holding the new file across its own rename is fine everywhere.
        f.file = OpenOptions::new().read(true).open(&tmp)?;
        std::fs::rename(&tmp, &path)?;
        f.file = OpenOptions::new().read(true).write(true).open(&path)?;
        f.load(false)?;
        Ok(true)
    })();
    {
        let mut f = file.lock().unwrap();
        f.upgrading = false;
        f.touched.clear();
    }
    let _ = std::fs::remove_file(&tmp);
    result
}

/// Uniform-or-payload for a write of `cells`: the hash and, when not uniform, nothing else yet.
pub fn hash_of(cells: &[u32]) -> (u64, Option<u32>) {
    match cell::uniform_value(cells) {
        Some(u) => (hash::of_uniform(u), Some(u)),
        None => (hash::of(cells), None),
    }
}
