// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista's native crate (owner code), relicensed into Iroh.
//! `Hidden`: storage-only normalisation of cells no mesh can show (solid on all six sides inside the section).
//! Those become the section's most common hidden block, light zero, biome kept. Same result as the Java pass.

use crate::cell::{self, class, SIZE, VOLUME};
use std::cell::RefCell;

struct Scratch {
    solid: Vec<bool>,
    hidden: Vec<bool>,
    counts: Vec<u32>,
    touched: Vec<u16>,
}

thread_local! {
    static SCRATCH: RefCell<Scratch> = RefCell::new(Scratch {
        solid: vec![false; VOLUME],
        hidden: vec![false; VOLUME],
        counts: vec![0; 65536],
        touched: Vec::with_capacity(1024),
    });
}

/// Normalises into `out`. Returns false (and leaves `out` untouched) when nothing is hidden or the section is uniform.
pub fn normalize(cells: &[u32], classes: &[u8; 65536], out: &mut [u32]) -> bool {
    if cells.len() != VOLUME || cell::uniform_value(cells).is_some() {
        return false;
    }
    SCRATCH.with(|s| {
        let s = &mut *s.borrow_mut();
        for (i, &c) in cells.iter().enumerate() {
            s.solid[i] = classes[cell::block(c) as usize] == class::SOLID;
        }
        let (dy, dz) = (SIZE * SIZE, SIZE);
        let mut any = 0usize;
        let mut top: i32 = -1;
        s.hidden.fill(false);
        for y in 1..SIZE - 1 {
            for z in 1..SIZE - 1 {
                for x in 1..SIZE - 1 {
                    let i = cell::index(x, y, z);
                    let sd = &s.solid;
                    if !(sd[i] && sd[i - 1] && sd[i + 1] && sd[i - dz] && sd[i + dz] && sd[i - dy] && sd[i + dy]) {
                        continue;
                    }
                    s.hidden[i] = true;
                    any += 1;
                    let b = cell::block(cells[i]) as usize;
                    if s.counts[b] == 0 {
                        s.touched.push(b as u16);
                    }
                    s.counts[b] += 1;
                    // Java: ++counts[b] > (top < 0 ? 0 : counts[top]) || counts[b] == counts[top] && b < top
                    let tc = if top < 0 { 0 } else { s.counts[top as usize] };
                    if s.counts[b] > tc || (top >= 0 && s.counts[b] == tc && (b as i32) < top) {
                        top = b as i32;
                    }
                }
            }
        }
        for &b in &s.touched {
            s.counts[b as usize] = 0;
        }
        s.touched.clear();
        if any == 0 {
            return false;
        }
        let top = top as u32;
        for i in 0..VOLUME {
            out[i] = if s.hidden[i] { top | (cells[i] & 0xFF00_0000) } else { cells[i] };
        }
        true
    })
}
