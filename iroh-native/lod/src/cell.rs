// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista's native crate (owner code), relicensed into Iroh.
//! Cells, sections and keys: the same words and layouts as `com.vista.data` (DESIGN.md section 4).
//! A cell is bits 0-15 block id, 16-19 sky light, 20-23 block light, 24-31 biome. The GPU reads these words,
//! so nothing here may change on its own.

pub const SIZE: usize = 32;
pub const VOLUME: usize = SIZE * SIZE * SIZE;
pub const MAX_LEVEL: i32 = 4;

/// Open air under the sky: block 0, sky light 15.
pub const OPEN_AIR: u32 = 15 << 16;

#[inline(always)]
pub const fn of(block: u32, sky: u32, block_light: u32, biome: u32) -> u32 {
    (block & 0xFFFF) | (sky & 15) << 16 | (block_light & 15) << 20 | (biome & 0xFF) << 24
}
#[inline(always)]
pub const fn block(c: u32) -> u32 {
    c & 0xFFFF
}
#[inline(always)]
pub const fn sky(c: u32) -> u32 {
    c >> 16 & 15
}
#[inline(always)]
pub const fn block_light(c: u32) -> u32 {
    c >> 20 & 15
}
#[inline(always)]
pub const fn biome(c: u32) -> u32 {
    c >> 24
}

/// y-major index, `(y << 10) | (z << 5) | x`.
#[inline(always)]
pub const fn index(x: usize, y: usize, z: usize) -> usize {
    y << 10 | z << 5 | x
}

/// The single value of a uniform section.
#[inline]
pub fn uniform_value(cells: &[u32]) -> Option<u32> {
    let first = cells[0];
    if cells.iter().all(|&c| c == first) { Some(first) } else { None }
}

/// Block classes ({@code BlockClass}).
pub mod class {
    pub const AIR: u8 = 0;
    pub const DECOR: u8 = 1;
    pub const FLUID: u8 = 2;
    pub const SOLID: u8 = 3;
    #[inline(always)]
    pub const fn open(c: u8) -> bool {
        c <= DECOR
    }
}

/// `SectionKey`: level, x, y, z with the protocol-3 packing.
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub struct Key {
    pub level: i32,
    pub x: i32,
    pub y: i32,
    pub z: i32,
}

impl Key {
    pub const fn new(level: i32, x: i32, y: i32, z: i32) -> Key {
        Key { level, x, y, z }
    }
    pub fn pack(self) -> i64 {
        ((self.level as i64) << 61)
            | (((self.y & 63) as i64) << 55)
            | (((self.x as i64) & 0x7FF_FFFF) << 27)
            | ((self.z as i64) & 0x7FF_FFFF)
    }
    pub fn unpack(v: i64) -> Key {
        let v = v as u64;
        let x = (v >> 27 & 0x7FF_FFFF) as i32;
        let z = (v & 0x7FF_FFFF) as i32;
        Key { level: (v >> 61) as i32, x: x << 5 >> 5, y: (v >> 55 & 63) as i32, z: z << 5 >> 5 }
    }
    pub fn parent(self) -> Key {
        Key::new(self.level + 1, self.x >> 1, self.y >> 1, self.z >> 1)
    }
    /// Child 0-7: bit 0 x, bit 1 z, bit 2 y.
    pub fn child(self, o: i32) -> Key {
        Key::new(self.level - 1, self.x << 1 | (o & 1), self.y << 1 | (o >> 2 & 1), self.z << 1 | (o >> 1 & 1))
    }
}

/// Classes per block id, read by the downsampler and the hidden-cell pass. Unknown ids are solid.
#[derive(Default, Clone)]
pub struct Classes {
    pub bytes: Vec<u8>,
}

impl Classes {
    #[inline(always)]
    pub fn of(&self, block: u32) -> u8 {
        match self.bytes.get(block as usize) {
            Some(&c) => c,
            None => class::SOLID,
        }
    }
    /// A full 65536-entry table (ids are 16 bits), so lookups need no bounds branch.
    pub fn dense(&self) -> Box<[u8; 65536]> {
        let mut t = Box::new([class::SOLID; 65536]);
        let n = self.bytes.len().min(65536);
        t[..n].copy_from_slice(&self.bytes[..n]);
        t
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn key_round_trip() {
        for k in [Key::new(0, -5, 3, 7), Key::new(4, 67_000_000, 31, -67_000_000), Key::new(2, -1, 0, -1)] {
            assert_eq!(Key::unpack(k.pack()), k);
        }
    }
}
