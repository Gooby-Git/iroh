// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista's native crate (owner code), relicensed into Iroh.
//! Iroh's LOD native backend (from Vista's native crate, owner code). Java keeps the Minecraft integration; this crate does the heavy data work:
//! section codec, content hashes, hidden-cell normalisation, downsampling, the pack-file store and its cascade,
//! and, behind the `seed` feature, seed worldgen. Every entry from Java is in `jni` (and `seed/jni_seed.rs`).

pub mod cell;
pub mod downsample;
pub mod hash;
pub mod hidden;
pub mod ingest;
pub mod jni;
pub mod packed;
pub mod packfile;
pub mod store;
mod bench_split;

/// Bumped whenever a JNI signature changes; the Java loader refuses a library with another value.
pub const ABI: i32 = 5;

/// Whether this build contains the seed worldgen (`seed` cargo feature; private builds only).
pub const HAS_SEED: bool = cfg!(feature = "seed");

#[cfg(feature = "seed")]
#[path = "seed/noise.rs"]
pub mod noise;
#[cfg(feature = "seed")]
#[path = "seed/jmath.rs"]
pub mod jmath;
#[cfg(feature = "seed")]
#[path = "seed/density.rs"]
pub mod density;
#[cfg(feature = "seed")]
#[path = "seed/column.rs"]
pub mod column;
#[cfg(feature = "seed")]
#[path = "seed/jni_seed.rs"]
mod jni_seed;
#[cfg(feature = "seed")]
#[path = "seed/profile.rs"]
mod profile;
mod threadprio;
