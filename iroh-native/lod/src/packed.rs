// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista's native crate (owner code), relicensed into Iroh.
//! `PackedSection`: local palette plus bit-packed indices, little-endian. The same bytes on disk, on the wire and in VRAM.

use crate::cell::VOLUME;
use std::cell::RefCell;

pub const VERSION: u8 = 1;
pub const HEADER: usize = 8;

#[inline]
pub fn bits_for(n: usize) -> u32 {
    match n {
        0 | 1 => 0,
        2 => 1,
        3..=4 => 2,
        5..=16 => 4,
        17..=256 => 8,
        _ => 16,
    }
}

/// Insertion-ordered u32 -> index map with open addressing; reused per thread so encoding allocates nothing.
struct Palette {
    keys: Vec<u32>,
    slots: Vec<u32>, // 0 = empty, else index + 1
    values: Vec<u32>,
    idx: Vec<u16>,
    used: Vec<u32>,
}

impl Palette {
    fn new() -> Palette {
        Palette { keys: vec![0; 1 << 16], slots: vec![0; 1 << 16], values: Vec::with_capacity(VOLUME), idx: vec![0; VOLUME], used: Vec::with_capacity(VOLUME) }
    }
}

thread_local! {
    static PALETTE: RefCell<Palette> = RefCell::new(Palette::new());
}

#[inline(always)]
fn mix(x: u32) -> u32 {
    let x = x.wrapping_mul(0x9E37_79B1);
    x ^ (x >> 15)
}

/// Encodes into `out` (cleared first).
pub fn encode_into(cells: &[u32], out: &mut Vec<u8>) {
    assert_eq!(cells.len(), VOLUME, "section needs {VOLUME} cells");
    PALETTE.with(|p| {
        let p = &mut *p.borrow_mut();
        p.values.clear();
        // The table has 65536 slots for at most 32768 values: load stays under one half, no growth needed.
        let mask = p.keys.len() - 1;
        for (i, &c) in cells.iter().enumerate() {
            let mut h = mix(c) as usize & mask;
            let id = loop {
                let s = p.slots[h];
                if s == 0 {
                    p.values.push(c);
                    p.keys[h] = c;
                    p.slots[h] = p.values.len() as u32;
                    p.used.push(h as u32);
                    break p.values.len() - 1;
                }
                if p.keys[h] == c {
                    break s as usize - 1;
                }
                h = (h + 1) & mask;
            };
            p.idx[i] = id as u16;
        }
        let n = p.values.len();
        let bits = bits_for(n);
        let words = VOLUME * bits as usize / 32;
        out.clear();
        out.reserve(HEADER + 4 * n + 4 * words);
        out.extend_from_slice(&[VERSION, bits as u8, 0, 0]);
        out.extend_from_slice(&(n as u32).to_le_bytes());
        for &v in &p.values {
            out.extend_from_slice(&v.to_le_bytes());
        }
        if bits > 0 {
            let per = 32 / bits as usize;
            for w in 0..words {
                let mut word = 0u32;
                let base = w * per;
                for k in 0..per {
                    word |= (p.idx[base + k] as u32) << (k as u32 * bits);
                }
                out.extend_from_slice(&word.to_le_bytes());
            }
        }
        for &h in &p.used {
            p.slots[h as usize] = 0;
        }
        p.used.clear();
    });
}

pub fn encode(cells: &[u32]) -> Vec<u8> {
    let mut v = Vec::new();
    encode_into(cells, &mut v);
    v
}

pub fn uniform(cell: u32) -> Vec<u8> {
    let mut v = vec![VERSION, 0, 0, 0];
    v.extend_from_slice(&1u32.to_le_bytes());
    v.extend_from_slice(&cell.to_le_bytes());
    v
}

/// Whether any palette entry of packed bytes has sky light (bits 16-19). Malformed input counts as lit (load it early).
pub fn palette_sky_lit(data: &[u8]) -> bool {
    if data.len() < HEADER {
        return true;
    }
    let n = u32::from_le_bytes(data[4..8].try_into().unwrap()) as usize;
    if data.len() < HEADER + 4 * n {
        return true;
    }
    data[HEADER..HEADER + 4 * n].chunks_exact(4).any(|b| crate::cell::sky(u32::from_le_bytes(b.try_into().unwrap())) > 0)
}

#[derive(Debug)]
pub struct BadSection(pub String);

/// Decodes into `cells` (VOLUME long).
pub fn decode_into(data: &[u8], cells: &mut [u32]) -> Result<(), BadSection> {
    if data.len() < HEADER {
        return Err(BadSection("short packed section".into()));
    }
    if data[0] != VERSION {
        return Err(BadSection(format!("unknown packed section version {}", data[0])));
    }
    let bits = data[1] as u32;
    let n = u32::from_le_bytes(data[4..8].try_into().unwrap()) as usize;
    if n < 1 || n > VOLUME || bits != bits_for(n) {
        return Err(BadSection(format!("bad palette {n}/{bits}")));
    }
    let words = VOLUME * bits as usize / 32;
    if data.len() < HEADER + 4 * n + 4 * words {
        return Err(BadSection("packed section ends early".into()));
    }
    let pal: Vec<u32> = data[HEADER..HEADER + 4 * n].chunks_exact(4).map(|b| u32::from_le_bytes(b.try_into().unwrap())).collect();
    if bits == 0 {
        cells.fill(pal[0]);
        return Ok(());
    }
    let per = 32 / bits as usize;
    let mask = (1u32 << bits) - 1;
    let body = &data[HEADER + 4 * n..];
    for w in 0..words {
        let word = u32::from_le_bytes(body[4 * w..4 * w + 4].try_into().unwrap());
        let base = w * per;
        for k in 0..per {
            let p = (word >> (k as u32 * bits) & mask) as usize;
            if p >= n {
                return Err(BadSection(format!("palette index {p} out of {n}")));
            }
            cells[base + k] = pal[p];
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn round_trip_all_widths() {
        for n in [1usize, 2, 3, 4, 5, 16, 17, 256, 257, 5000] {
            let cells: Vec<u32> = (0..VOLUME).map(|i| ((i * 7919) % n) as u32 * 0x0101_0101).collect();
            let enc = encode(&cells);
            assert_eq!(enc[1] as u32, bits_for(n));
            let mut back = vec![0; VOLUME];
            decode_into(&enc, &mut back).unwrap();
            assert_eq!(back, cells);
            // twice in a row on the same thread: the palette table must come back clean
            assert_eq!(encode(&cells), enc);
        }
    }
}
