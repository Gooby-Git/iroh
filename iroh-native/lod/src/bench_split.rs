// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista's native crate (owner code), relicensed into Iroh.
//! Timing split for the store's hot paths: `cargo test --release bench_split -- --ignored --nocapture`.
#[cfg(test)]
mod t {
    use crate::cell::{self, VOLUME};
    use crate::{downsample, hash, hidden, packed, packfile};
    use std::time::Instant;

    fn terrain(seed: u32) -> Vec<u32> {
        let mut c = vec![0u32; VOLUME];
        let mut r = seed.wrapping_mul(2654435761) | 1;
        let mut rnd = |n: u32| { r ^= r << 13; r ^= r >> 17; r ^= r << 5; r % n };
        for z in 0..32 { for x in 0..32 {
            let h = 8 + ((x as f32 * 0.2).sin() * 10.0 + (z as f32 * 0.15).cos() * 6.0) as i32 + rnd(3) as i32;
            for y in 0..32i32 {
                let (b, s) = if y < h - 3 { (30 + rnd(60), 0) } else if y < h { (26, 0) } else if y == h { (27, 0) } else if y < 16 { (21, 12) } else { (0, 15) };
                c[cell::index(x, y as usize, z)] = cell::of(b, s, 0, 3);
            }
        }}
        c
    }

    #[test]
    #[ignore]
    fn bench_split() {
        let mut classes = Box::new([cell::class::SOLID; 65536]);
        classes[0] = cell::class::AIR; classes[21] = cell::class::FLUID;
        let secs: Vec<Vec<u32>> = (0..8).map(terrain).collect();
        let n = 200;
        let mut out = vec![0u32; VOLUME];
        let t = Instant::now();
        for i in 0..n { std::hint::black_box(hash::of(&secs[i % 8])); }
        println!("hash      {:.3} ms", t.elapsed().as_secs_f64() * 1e3 / n as f64);
        let t = Instant::now();
        for i in 0..n { std::hint::black_box(hidden::normalize(&secs[i % 8], &classes, &mut out)); }
        println!("hidden    {:.3} ms", t.elapsed().as_secs_f64() * 1e3 / n as f64);
        let mut p = Vec::new();
        let t = Instant::now();
        for i in 0..n { packed::encode_into(&secs[i % 8], &mut p); }
        println!("encode    {:.3} ms ({} B)", t.elapsed().as_secs_f64() * 1e3 / n as f64, p.len());
        for lvl in [1, 3, 6, 9] {
            let t = Instant::now();
            let mut sz = 0;
            for _ in 0..n { sz = packfile::wrap(&p, lvl).len(); }
            println!("deflate{lvl}  {:.3} ms ({} B)", t.elapsed().as_secs_f64() * 1e3 / n as f64, sz);
        }
        let w = packfile::wrap(&p, 6);
        let t = Instant::now();
        for _ in 0..n { std::hint::black_box(packfile::unwrap(&w).unwrap()); }
        println!("inflate   {:.3} ms", t.elapsed().as_secs_f64() * 1e3 / n as f64);
        let t = Instant::now();
        for _ in 0..n { packed::decode_into(&p, &mut out).unwrap(); }
        println!("decode    {:.3} ms", t.elapsed().as_secs_f64() * 1e3 / n as f64);
        let mut ds = downsample::Downsampler::default();
        let kids: [Option<&[u32]>; 8] = std::array::from_fn(|o| Some(&secs[o][..]));
        let t = Instant::now();
        for _ in 0..50 { ds.downsample(&kids, &classes, &mut out); }
        println!("downsample {:.3} ms", t.elapsed().as_secs_f64() * 1e3 / 50.0);
    }
}
