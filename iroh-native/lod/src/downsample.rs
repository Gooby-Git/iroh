// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista's native crate (owner code), relicensed into Iroh.
//! `Downsampler`: one level-(L+1) section from the eight level-L sections under it (DESIGN.md section 4).
//! Bit-identical to the Java version (and so to the GPU shader); `NativeParityTest` checks it.
//!
//! Speed: the 64^3 fine grid is gathered once with its classes as bit rows (one u64 per x row): open, fluid, solid.
//! "Exposed" (open above or on a side) is then a handful of shifts and ORs per row instead of five bounds-checked
//! lookups per solid cell, and 2 x 2 x 2 groups whose eight cells are the same word (buried rock, open air: most of
//! any section) take a closed form that gives what the full rules give for identical children.

use crate::cell::{self, class, SIZE};

const F: usize = 64;

pub struct Downsampler {
    fine: Vec<u32>,
    open: Vec<u64>,
    fluid: Vec<u64>,
    solid: Vec<u64>,
    exposed: Vec<u64>,
}

impl Default for Downsampler {
    fn default() -> Self {
        Downsampler { fine: vec![0; F * F * F], open: vec![0; F * F], fluid: vec![0; F * F], solid: vec![0; F * F], exposed: vec![0; F * F] }
    }
}

#[inline(always)]
fn fi(x: usize, y: usize, z: usize) -> usize {
    (y * F + z) * F + x
}

#[inline(always)]
fn row(y: usize, z: usize) -> usize {
    y * F + z
}

/// Most frequent of `v`, ties to the lower value (signed compare, as Java's ints).
#[inline]
fn most_common(v: &[u32]) -> u32 {
    let first = v[0];
    if v.iter().all(|&a| a == first) {
        return first;
    }
    let mut best = first;
    let mut best_count = 0;
    for &a in v {
        let count = v.iter().filter(|&&b| b == a).count();
        if count > best_count || count == best_count && (a as i32) < (best as i32) {
            best = a;
            best_count = count;
        }
    }
    best
}

impl Downsampler {
    /// `children` by octant (bit 0 x, bit 1 z, bit 2 y); `None` is open air. Writes the parent into `out`.
    pub fn downsample(&mut self, children: &[Option<&[u32]>; 8], classes: &[u8; 65536], out: &mut [u32]) {
        self.gather(children, classes);
        self.expose();
        for py in 0..SIZE {
            for pz in 0..SIZE {
                for px in 0..SIZE {
                    out[cell::index(px, py, pz)] = self.merge(px * 2, py * 2, pz * 2, classes);
                }
            }
        }
    }

    fn gather(&mut self, children: &[Option<&[u32]>; 8], classes: &[u8; 65536]) {
        let air_class = classes[0];
        for (o, child) in children.iter().enumerate() {
            let (ox, oz, oy) = ((o & 1) * SIZE, (o >> 1 & 1) * SIZE, (o >> 2 & 1) * SIZE);
            let mask: u64 = 0xFFFF_FFFF << ox;
            for y in 0..SIZE {
                for z in 0..SIZE {
                    let r = row(oy + y, oz + z);
                    let at = fi(ox, oy + y, oz + z);
                    let (mut op, mut fl, mut so) = (0u64, 0u64, 0u64);
                    match child {
                        Some(c) => {
                            let src = &c[cell::index(0, y, z)..cell::index(0, y, z) + SIZE];
                            self.fine[at..at + SIZE].copy_from_slice(src);
                            for (x, &w) in src.iter().enumerate() {
                                let t = classes[cell::block(w) as usize];
                                let b = 1u64 << x;
                                if class::open(t) {
                                    op |= b;
                                } else if t == class::FLUID {
                                    fl |= b;
                                } else {
                                    so |= b;
                                }
                            }
                        }
                        None => {
                            self.fine[at..at + SIZE].fill(cell::OPEN_AIR);
                            let all = 0xFFFF_FFFFu64;
                            if class::open(air_class) {
                                op = all;
                            } else if air_class == class::FLUID {
                                fl = all;
                            } else {
                                so = all;
                            }
                        }
                    }
                    self.open[r] = self.open[r] & !mask | op << ox;
                    self.fluid[r] = self.fluid[r] & !mask | fl << ox;
                    self.solid[r] = self.solid[r] & !mask | so << ox;
                }
            }
        }
    }

    /// Solid cells with open space above or on any side (outside the 64^3 grid counts as open).
    fn expose(&mut self) {
        for y in 0..F {
            for z in 0..F {
                let o = self.open[row(y, z)];
                let up = if y == F - 1 { !0 } else { self.open[row(y + 1, z)] };
                let sides = (o << 1 | 1) | (o >> 1 | 1 << 63);
                let zp = if z == F - 1 { !0 } else { self.open[row(y, z + 1)] };
                let zm = if z == 0 { !0 } else { self.open[row(y, z - 1)] };
                self.exposed[row(y, z)] = self.solid[row(y, z)] & (up | sides | zp | zm);
            }
        }
    }

    fn merge(&self, bx: usize, by: usize, bz: usize, classes: &[u8; 65536]) -> u32 {
        let mut c = [0u32; 8];
        for k in 0..8 {
            c[k] = self.fine[fi(bx + (k & 1), by + (k >> 2 & 1), bz + (k >> 1 & 1))];
        }
        if c.iter().all(|&v| v == c[0]) {
            // Eight equal children: whichever rule applies, it picks this block (or air when open), the sky light
            // unless solid, its block light and its biome.
            let v = c[0];
            let t = classes[cell::block(v) as usize];
            let block = if class::open(t) { 0 } else { cell::block(v) };
            let sky = if t != class::SOLID { cell::sky(v) } else { 0 };
            return cell::of(block, sky, cell::block_light(v), cell::biome(v));
        }
        let mut t = [0u8; 8];
        let mut fy = [-1i32; 8];
        let (mut open, mut fluids, mut solids, mut exposed_top) = (0, 0, 0, -1i32);
        for k in 0..8 {
            let (x, y, z) = (bx + (k & 1), by + (k >> 2 & 1), bz + (k >> 1 & 1));
            let r = row(y, z);
            let b = 1u64 << x;
            if self.open[r] & b != 0 {
                open += 1;
                t[k] = class::AIR; // only "open or not" matters below for these
            } else if self.fluid[r] & b != 0 {
                fluids += 1;
                t[k] = class::FLUID;
            } else {
                solids += 1;
                t[k] = class::SOLID;
                if self.exposed[r] & b != 0 {
                    fy[k] = y as i32;
                    exposed_top = exposed_top.max(y as i32);
                }
            }
        }
        let mut cand = [0u32; 8];
        let block = if exposed_top >= 0 {
            let mut n = 0;
            for k in 0..8 {
                if fy[k] == exposed_top {
                    cand[n] = cell::block(c[k]);
                    n += 1;
                }
            }
            most_common(&cand[..n])
        } else if fluids > 0 && (open >= 4 || fluids >= solids) {
            of_class(&c, &t, class::FLUID, &mut cand)
        } else if open >= 4 {
            0
        } else {
            of_class(&c, &t, class::SOLID, &mut cand)
        };
        let (mut sky, mut light) = (0, 0);
        for k in 0..8 {
            if t[k] != class::SOLID {
                sky = sky.max(cell::sky(c[k]));
            }
            light = light.max(cell::block_light(c[k]));
        }
        let mut nb = 0;
        for k in 0..8 {
            if t[k] != class::AIR {
                cand[nb] = cell::biome(c[k]);
                nb += 1;
            }
        }
        if nb == 0 {
            for k in 0..8 {
                cand[k] = cell::biome(c[k]);
            }
            nb = 8;
        }
        cell::of(block, sky, light, most_common(&cand[..nb]))
    }
}

#[inline(always)]
fn of_class(c: &[u32; 8], t: &[u8; 8], wanted: u8, cand: &mut [u32; 8]) -> u32 {
    let mut n = 0;
    for k in 0..8 {
        if t[k] == wanted {
            cand[n] = cell::block(c[k]);
            n += 1;
        }
    }
    most_common(&cand[..n])
}
