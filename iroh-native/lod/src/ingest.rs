// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista's native crate (owner code), relicensed into Iroh.
//! Chunk ingest: one chunk, handed over as a single byte buffer, becomes edits to the level-0 sections it covers.
//! Java only copies the chunk's palette indices, light nibbles and biome ids; cells, hidden-cell normalisation, hashing,
//! Deflate and the file write all happen here, off the JVM heap. A small write-back cache (the same idea as Vista's
//! `WriteBack`) merges the four chunks that share a level-0 section into one compressed write.
//!
//! Chunk buffer, little endian, one block per 16-tall chunk section from the dimension's bottom:
//! ```text
//!   u8  flags        1 blocks, 2 sky light, 4 block light, 8 biomes
//!   [1] u16 palette length n (1..=4096), u16 palette[n] (LOD block ids), u16 index[4096] (y<<8 | z<<4 | x)
//!   [2] u8 sky[2048]      nibbles, cell n in byte n/2, low nibble first
//!   [4] u8 light[2048]    same
//!   [8] u8 biome[64]      4x4x4 quarter cells, index (y>>2)<<4 | (z>>2)<<2 | (x>>2)
//! ```
//! A missing blocks part means air; missing sky means open sky when the dimension has a sky (`has_sky`) else dark.

use crate::cell::{self, Key, VOLUME};
use crate::store::Store;
use std::collections::{HashMap, HashSet};
use std::io;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Held sections before the oldest are written regardless of age.
pub const MAX_HELD: usize = 384;
/// How long a section waits for its other chunks.
pub const HOLD: Duration = Duration::from_millis(4000);
const LOCKS: usize = 256;

struct Entry {
    cells: Vec<u32>,
    since: Instant,
    dirty: bool,
}

/// Write-back state of a store.
pub struct Held {
    map: Mutex<HashMap<Key, Arc<Mutex<Entry>>>>,
    chunks: Mutex<HashSet<(i32, i32)>>,
    locks: Vec<Mutex<()>>,
}

impl Default for Held {
    fn default() -> Self {
        Held { map: Mutex::new(HashMap::new()), chunks: Mutex::new(HashSet::new()), locks: (0..LOCKS).map(|_| Mutex::new(())).collect() }
    }
}

fn lock_index(k: Key) -> usize {
    let h = (k.x as u32).wrapping_mul(0x9E37_79B1) ^ (k.z as u32).wrapping_mul(0x85EB_CA6B) ^ (k.y as u32).wrapping_mul(0xC2B2_AE35);
    ((h ^ h >> 16) as usize) & (LOCKS - 1)
}

fn bad(msg: impl Into<String>) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, msg.into())
}

/// One chunk section of the buffer, as slices into it.
struct Part<'a> {
    palette: Option<(&'a [u8], &'a [u8])>, // palette bytes (u16 each), indices bytes (u16 each)
    sky: Option<&'a [u8]>,
    light: Option<&'a [u8]>,
    biome: Option<&'a [u8]>,
}

fn parse(buf: &[u8], sections: usize) -> io::Result<Vec<Part<'_>>> {
    let mut at = 0usize;
    let mut take = |n: usize| -> io::Result<&[u8]> {
        let s = buf.get(at..at + n).ok_or_else(|| bad("chunk buffer ends early"))?;
        at += n;
        Ok(s)
    };
    let mut out = Vec::with_capacity(sections);
    for _ in 0..sections {
        let flags = take(1)?[0];
        let palette = if flags & 1 != 0 {
            let n = u16::from_le_bytes(take(2)?.try_into().unwrap()) as usize;
            if n == 0 || n > 4096 {
                return Err(bad(format!("palette length {n}")));
            }
            let pal = take(2 * n)?;
            let idx = take(8192)?;
            Some((pal, idx))
        } else {
            None
        };
        let sky = if flags & 2 != 0 { Some(take(2048)?) } else { None };
        let light = if flags & 4 != 0 { Some(take(2048)?) } else { None };
        let biome = if flags & 8 != 0 { Some(take(64)?) } else { None };
        out.push(Part { palette, sky, light, biome });
    }
    Ok(out)
}

fn nibble(d: &[u8], n: usize) -> u32 {
    (d[n >> 1] >> ((n & 1) << 2) & 15) as u32
}

fn all_open_air(p: &Part, open_sky: u32) -> bool {
    if p.palette.is_some() {
        return false;
    }
    if let Some(l) = p.light {
        if l.iter().any(|&b| b != 0) {
            return false;
        }
    }
    match p.sky {
        None => open_sky == 15,
        Some(s) => s.iter().all(|&b| b == 0xFF),
    }
}

/// Writes one chunk section's cells into `dst` at (qx, yoff, qz).
fn fill(p: &Part, yoff: usize, dst: &mut [u32], qx: usize, qz: usize, open_sky: u32) {
    for y in 0..16usize {
        for z in 0..16usize {
            for x in 0..16usize {
                let n = y << 8 | z << 4 | x;
                let s = p.sky.map_or(open_sky, |d| nibble(d, n));
                let b = p.light.map_or(0, |d| nibble(d, n));
                let biome = p.biome.map_or(0, |d| d[(y >> 2) << 4 | (z >> 2) << 2 | (x >> 2)] as u32);
                let block = match p.palette {
                    None => 0,
                    Some((pal, idx)) => {
                        let i = u16::from_le_bytes([idx[2 * n], idx[2 * n + 1]]) as usize;
                        // an index past the palette is a Java bug; air keeps the world readable
                        pal.get(2 * i..2 * i + 2).map_or(0, |v| u16::from_le_bytes([v[0], v[1]]) as u32)
                    }
                };
                dst[cell::index(qx + x, yoff + y, qz + z)] = cell::of(block, s, b, biome);
            }
        }
    }
}

impl Store {
    /// Applies a chunk (see the module doc) to the held level-0 sections. Nothing reaches disk until [`Store::flush_held`].
    pub fn ingest_chunk(&self, cx: i32, cz: i32, has_sky: bool, sections: usize, buf: &[u8]) -> io::Result<()> {
        let parts = parse(buf, sections)?;
        let open_sky = if has_sky { 15 } else { 0 };
        let (sx, sz) = (cx >> 1, cz >> 1);
        let (qx, qz) = (((cx & 1) * 16) as usize, ((cz & 1) * 16) as usize);
        let n = parts.len();
        let mut sy = 0usize;
        while sy * 2 < n && sy < 32 {
            let lo = sy * 2;
            let hi = (lo + 1).min(n - 1);
            let empty = all_open_air(&parts[lo], open_sky) && (hi == lo || all_open_air(&parts[hi], open_sky));
            let blank = cell::of(0, open_sky, 0, 0);
            self.edit_held(Key::new(0, sx, sy as i32, sz), empty, blank, |cells| {
                fill(&parts[lo], 0, cells, qx, qz, open_sky);
                if hi != lo {
                    fill(&parts[hi], 16, cells, qx, qz, open_sky);
                }
            })?;
            sy += 1;
        }
        self.held.chunks.lock().unwrap().insert((cx, cz));
        if self.held.map.lock().unwrap().len() > MAX_HELD {
            self.flush_held(false)?;
        }
        Ok(())
    }

    fn edit_held(&self, k: Key, skip_if_new: bool, blank: u32, f: impl FnOnce(&mut [u32])) -> io::Result<()> {
        let _g = self.held.locks[lock_index(k)].lock().unwrap();
        let existing = self.held.map.lock().unwrap().get(&k).cloned();
        let entry = match existing {
            Some(e) => e,
            None => {
                let mut cells = vec![0u32; VOLUME];
                if !self.get(k, &mut cells)? {
                    if skip_if_new {
                        return Ok(());
                    }
                    cells.fill(blank);
                }
                let e = Arc::new(Mutex::new(Entry { cells, since: Instant::now(), dirty: false }));
                self.held.map.lock().unwrap().insert(k, e.clone());
                e
            }
        };
        let mut e = entry.lock().unwrap();
        f(&mut e.cells);
        e.dirty = true;
        Ok(())
    }

    /// Writes held sections (all, or those older than [`HOLD`] and the oldest ones beyond [`MAX_HELD`]); returns the keys
    /// whose stored content changed. Chunks are marked ingested once none of their sections is still held.
    pub fn flush_held(&self, all: bool) -> io::Result<Vec<Key>> {
        let now = Instant::now();
        let keys: Vec<Key> = {
            let map = self.held.map.lock().unwrap();
            if all {
                map.keys().copied().collect()
            } else {
                let mut list: Vec<(Instant, Key)> = map.iter().map(|(k, e)| (e.lock().unwrap().since, *k)).collect();
                list.sort_by_key(|(s, _)| *s);
                let mut over = map.len() as i64 - MAX_HELD as i64;
                let mut v = Vec::new();
                for (since, k) in list {
                    let o = over > 0;
                    over -= 1;
                    if now.duration_since(since) >= HOLD || o {
                        v.push(k);
                    }
                }
                v
            }
        };
        let mut changed = Vec::new();
        for k in keys {
            let _g = self.held.locks[lock_index(k)].lock().unwrap();
            let e = self.held.map.lock().unwrap().remove(&k);
            if let Some(e) = e {
                let e = e.lock().unwrap();
                if e.dirty && self.put(k, &e.cells)? {
                    changed.push(k);
                }
            }
        }
        let held_cols: HashSet<(i32, i32)> = self.held.map.lock().unwrap().keys().map(|k| (k.x, k.z)).collect();
        let pending: Vec<(i32, i32)> = self.held.chunks.lock().unwrap().iter().copied().collect();
        for (cx, cz) in pending {
            if all || held_cols.is_empty() || !held_cols.contains(&(cx >> 1, cz >> 1)) {
                self.held.chunks.lock().unwrap().remove(&(cx, cz));
                self.mark_chunk(cx, cz)?;
            }
        }
        Ok(changed)
    }

    /// Sections and chunks still waiting for their write.
    pub fn held_count(&self) -> usize {
        self.held.map.lock().unwrap().len()
    }

    /// True when the chunk was ingested but not yet marked in the store.
    pub fn holds_chunk(&self, cx: i32, cz: i32) -> bool {
        self.held.chunks.lock().unwrap().contains(&(cx, cz))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dir(name: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("iroh-lod-ingest-test-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        d
    }

    /// One chunk of `n` sections: stone below y=20, grass on top, sky light above.
    fn chunk(n: usize, seed: u8) -> Vec<u8> {
        let mut b = Vec::new();
        for s in 0..n {
            let air_above = s * 16 >= 32;
            if air_above {
                b.push(2); // sky only: all 0xFF -> open air, skipped when new
                b.extend(std::iter::repeat(0xFFu8).take(2048));
                continue;
            }
            b.push(1 | 2 | 8);
            b.extend_from_slice(&3u16.to_le_bytes());
            for id in [0u16, 40 + seed as u16, 41] {
                b.extend_from_slice(&id.to_le_bytes());
            }
            for i in 0..4096usize {
                let y = s * 16 + (i >> 8);
                let idx: u16 = if y < 20 { 1 } else if y == 20 { 2 } else { 0 };
                b.extend_from_slice(&idx.to_le_bytes());
            }
            for i in 0..4096usize {
                let y = s * 16 + (i >> 8);
                let v = if y > 20 { 15u8 } else { 0 };
                if i & 1 == 0 { b.push(v) } else { *b.last_mut().unwrap() |= v << 4 }
            }
            b.extend((0..64).map(|i| (i % 5) as u8));
        }
        b
    }

    #[test]
    fn four_chunks_make_one_write_and_mark_chunks() {
        let st = Store::open(&dir("quad")).unwrap();
        st.set_class(0, cell::class::AIR);
        for (cx, cz) in [(0, 0), (1, 0), (0, 1), (1, 1)] {
            st.ingest_chunk(cx, cz, true, 4, &chunk(4, (cx * 2 + cz) as u8)).unwrap();
        }
        assert_eq!(st.held_count(), 1, "LOD section y=0 (chunk sections 0-1); chunk sections 2-3 are open air, never stored");
        assert!(!st.has(Key::new(0, 0, 0, 0)).unwrap(), "nothing on disk before the flush");
        assert!(st.holds_chunk(1, 1));
        let changed = st.flush_held(true).unwrap();
        assert_eq!(changed.len(), 1);
        assert!(st.has(Key::new(0, 0, 0, 0)).unwrap());
        assert!(!st.has(Key::new(0, 0, 1, 0)).unwrap(), "open-air sections are skipped");
        assert!(st.has_chunk(1, 1).unwrap() && !st.holds_chunk(1, 1));
        let mut c = vec![0u32; VOLUME];
        assert!(st.get(Key::new(0, 0, 0, 0), &mut c).unwrap());
        // quadrant (1,0) came from chunk (1,0), seed 2: its stone is block 42 (read on the bottom row: interior cells are hidden-normalised)
        assert_eq!(cell::block(c[cell::index(16 + 3, 0, 3)]), 42);
        assert_eq!(cell::block(c[cell::index(3, 0, 3)]), 40);
        assert_eq!(cell::block(c[cell::index(3, 20, 3)]), 41);
        assert_eq!(cell::sky(c[cell::index(3, 25, 3)]), 15);
        assert_eq!(cell::biome(c[cell::index(3, 0, 3)]), 0);
        // an unchanged second flush writes nothing
        for (cx, cz) in [(0, 0), (1, 0), (0, 1), (1, 1)] {
            st.ingest_chunk(cx, cz, true, 4, &chunk(4, (cx * 2 + cz) as u8)).unwrap();
        }
        assert!(st.flush_held(true).unwrap().is_empty());
    }

    #[test]
    fn truncated_buffer_is_an_error() {
        let st = Store::open(&dir("trunc")).unwrap();
        let b = chunk(2, 0);
        assert!(st.ingest_chunk(0, 0, true, 2, &b[..b.len() - 1]).is_err());
        assert!(st.ingest_chunk(0, 0, true, 3, &b).is_err());
    }
}
