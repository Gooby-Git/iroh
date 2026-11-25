// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista's native crate (owner code), relicensed into Iroh.
//! `SectionHash`: the 64-bit content hash servers and clients compare. Must equal the Java one bit for bit forever.

use std::collections::HashMap;
use std::sync::Mutex;

const P1: u64 = 0x9E37_79B9_7F4A_7C15;
const P2: u64 = 0xC2B2_AE3D_27D4_EB4F;
const A0: u64 = 0x243F_6A88_85A3_08D3;
const B0: u64 = 0x1319_8A2E_0370_7344;

#[inline(always)]
fn finish(mut z: u64) -> u64 {
    z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
    z ^ (z >> 31)
}

pub fn of(cells: &[u32]) -> u64 {
    let (mut a, mut b) = (A0, B0);
    for pair in cells.chunks_exact(2) {
        a = (a ^ (pair[0] as u64).wrapping_mul(P1)).rotate_left(29).wrapping_mul(P2);
        b = (b ^ (pair[1] as u64).wrapping_mul(P2)).rotate_left(31).wrapping_mul(P1);
    }
    finish(a ^ b.rotate_left(17) ^ cells.len() as u64)
}

static UNIFORM: Mutex<Option<HashMap<u32, u64>>> = Mutex::new(None);

/// Hash of a section whose every cell is `cell` (cached: a handful of values per world).
pub fn of_uniform(cell: u32) -> u64 {
    {
        let g = UNIFORM.lock().unwrap();
        if let Some(h) = g.as_ref().and_then(|m| m.get(&cell)) {
            return *h;
        }
    }
    let v = cell as u64;
    let (pa, pb) = (v.wrapping_mul(P1), v.wrapping_mul(P2));
    let (mut a, mut b) = (A0, B0);
    for _ in 0..crate::cell::VOLUME / 2 {
        a = (a ^ pa).rotate_left(29).wrapping_mul(P2);
        b = (b ^ pb).rotate_left(31).wrapping_mul(P1);
    }
    let h = finish(a ^ b.rotate_left(17) ^ crate::cell::VOLUME as u64);
    let mut g = UNIFORM.lock().unwrap();
    let m = g.get_or_insert_with(HashMap::new);
    if m.len() > 4096 {
        m.clear();
    }
    m.insert(cell, h);
    h
}

#[cfg(test)]
mod tests {
    #[test]
    fn uniform_matches_full() {
        for c in [0u32, 15 << 16, 0xFFFF_FFFF, 0x0102_0304] {
            assert_eq!(super::of_uniform(c), super::of(&vec![c; crate::cell::VOLUME]));
        }
    }
}
