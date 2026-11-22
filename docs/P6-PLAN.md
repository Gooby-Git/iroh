# P6-PLAN: denoising (Phase 6) breakdown

Card P6-0, 2026-11-22. Facts, licence verdict, contract: `knowledge/nrd.md` (skill folder). Builds on card P4-2a (FSR shim, loader, lavapipe harness).

## Verdict
- NRD v4.18.0 (stock, no fork) builds on this machine with no installed packages (cmake, ninja, g++, curl; its CMake downloads ShaderMake, MathLib and a prebuilt DXC; 12 s) into a 6.5 MB library with the SPIR-V embedded, and **runs on lavapipe**: RELAX_DIFFUSE, REBLUR_DIFFUSE and SIGMA_SHADOW execute 30 frames through the JNI shim and a Java Vulkan executor, no NaN, MSE reduced ~24x on a 1 spp noisy scene while keeping silhouettes, golden stored. The RT/denoise path is therefore developable offline up to the denoiser; only RT shader work and real timing need the GPU.
- Licence: NVIDIA RTX SDKs licence (not OSI). Build at build time, never commit SDK source, never bundle the binary in the LGPL jar: ship NRD as an OPTIONAL SEPARATE COMPONENT (vendor folder, licence acceptance, credit text), same model as DLSS (dlss.md). Iroh ships a licence-free fallback (temporal accumulation) so RT works without it, at lower quality.
- Stock NRD problems found: RELAX shaders fail to compile with `NRD_NORMAL_ENCODING=4`; `GetResourceTypeString` names are out of enum order. Use encoding 2.

## What exists (card/P6-0)
`iroh-native/build-nrd.sh` + `iroh-native/nrd/` (fetch by commit+hash, CMake, JNI shim `libiroh_nrd`), `dev.elemore.iroh.rt.denoise` (`Denoiser`, `DenoiserType`, `Slot`, `FrameSettings`, `DenoiseFrame`, `DenoiseGpu`, `TemporalAccumulationDenoiser` + `tools/shaders/temporal_accum.comp` -> checked-in SPIR-V), `.rt.denoise.nrd` (`NrdNative`, `NrdLoader`, `NrdDenoiser` executor, `NrdInstanceDesc`, `NrdSettings`, `NrdFormats`), `dev.elemore.iroh.natives.NativeLib` (generic loader: jar, vendor folder), tests `./gradlew denoiseTest` (83 checks) and `./gradlew nrdReplay`.

## Phase 6 cards (revised)
| card | who | content | offline proof |
|---|---|---|---|
| P6-1a [S] | sonnet | Vendor component packaging: `natives-nv/` -> separate zip with NVIDIA LICENSE + credit text; loader state "NRD not installed" in the capability screen; fetcher hook (P4-6) with acceptance dialog | unit tests (loader states, hash check) |
| P6-1b [O] | opus | In-game `NrdDenoiser` wiring: DenoiseGpu over MemoryGuard/VMA (+ memory accounting ~217 MiB at 1707x960 for the Radiance-style set), per-resource barriers instead of one global barrier, frames-in-flight rings (descriptor pools, constant ring), resize/reset | lavapipe replay with barrier tracking checked by validation layers (needs owner's `vulkan-validation-layers`) |
| P6-1c [O] | opus | Input producers: RT/raster shaders writing IN_MV, IN_NORMAL_ROUGHNESS (R10G10B10A2 oct + roughness), IN_VIEWZ, radiance+hit distance (RELAX direct, REBLUR indirect), SIGMA penumbra; hit-distance normalisation; demodulation by albedo. Encoders either re-implemented from the documented layout or `#include NRD.hlsli` at build time (shipping only compiled SPIR-V in the optional component): owner decision (nrd.md section 1) | CPU/lavapipe golden of the encoders against `NrdReplay` scene; no GPU |
| P6-1d [O] | opus | SIGMA for the sun + RELAX/REBLUR split (Radiance technique 1), checkerboard mode, SH variants, settings tuned per resolution scale (at 320x180 smaller radii helped: 28.6x vs 23.7x) | extend `NrdReplay` with specular, checkerboard, jitter; thresholds only |
| P6-2 [O] | opus | DLSS Ray Reconstruction (NGX, UNTESTED) behind `Denoiser`, same slots (+ albedo/spec MV guides) | mock adapter contract test |
| P6-3 [O] | opus | Radiance cache (own, clean-room) | CPU reference + lavapipe compute golden (ray query on lavapipe works: tools.md) |
| P6-4 [S] | sonnet | FSR hook: one shared motion-vector buffer (NRD UV units, FSR `motionVectorScale = (renderW, renderH)`), jitter shared, resolution hand-off below | combined lavapipe test: noisy RT-like scene -> NRD -> FSR, compares the chain against the 1024 spp reference |
| P6-5 [P] | proctor | noise / lag / ghosting captures, GPU timestamps per stage (NRD instance, FSR) | Proctor only (ask owner) |
Order: P6-1a, P6-1c (parallel), P6-1b, P6-1d, P6-4, then GPU runs (P6-5); P6-2/P6-3 independent.

## Offline vs real GPU
- Offline on lavapipe (proven): library loads, pipelines from embedded SPIR-V, descriptor layout, constant ring, dispatch order, history across frames, output correctness vs a CPU 1024 spp reference, golden regression, memory sizes, fallback denoiser, NaN safety.
- Needs the real GPU: timing (NVIDIA numbers: REBLUR_DS 2.55 ms, RELAX_DS 3.25 ms, SIGMA 0.40 ms at 1440p on an RTX 4080; Radiance users ~2 ms at 1080p on RDNA4: not measured by us), RADV behaviour of `VK_KHR_compute_shader_derivatives` (lavapipe reports the quad feature false yet the quad build ran identically: UNVERIFIED on RADV; `--no-quad` build is the safe fallback), barrier cost, async compute overlap, real RT noise statistics.

## How NRD's outputs feed FSR (and what P4 must share)
Order (Radiance, AMD): ray tracing -> NRD -> FSR3 -> tone mapping -> raster post. The denoised signals are composed (demodulated radiance x albedo, + emission, fog, sky) at RENDER resolution into the linear HDR colour that FSR upscales: FSR gets colour (HDR flag set), depth, motion vectors, exposure, reactive mask. Sharing with P4:
1. Resolution: NRD runs at the RENDER size (`resourceSize == rectSize == renderW x renderH`, or half with checkerboard); FSR upscales its composed output to display size. Dynamic resolution: set NRD `rectSize` per frame and FSR `maxRenderSize`/`renderSize` equal; NRD history needs `resourceSizePrev/rectSizePrev` (FSR does not).
2. Jitter: both see the same sub-pixel jitter. NRD matrices must be NON jittered and the jitter passed in `cameraJitter/Prev` ([-0.5, 0.5] pixels; FSR `jitterOffset` is the same quantity in pixels, content shifts by +jitter: fsr.md A.2b). Trace primary rays through jittered pixel centres, write guides for the same jittered samples.
3. Motion vectors: one buffer for both: previous minus current, UV units, y down, unjittered, written for primary hits; FSR `motionVectorScale = (renderW, renderH)`. 2.5D (z = viewZ delta) recommended for NRD: FSR ignores z: use RG16F or RGBA16F with .z.
4. Depth/viewZ: NRD wants linear view depth (viewZ, sky > denoisingRange); FSR wants a depth buffer (standard or inverted, cameraNear/Far): derive both from the same primary hit pass; FSR near plane must still be >= 0.075 (the pack's real near is 0.05: P4 decision).
5. Masks: FSR reactive/T&C masks from RT: translucent/refracted pixels, emissive/animated textures; NRD confidence (antilag) input from the same signals.
6. Memory: NRD 217 MiB (1707x960 set) + FSR 63 MiB at 1280x720->1920x1080 (106 MB doc at 1440p Quality) + shared MV/depth: budget ~350 MiB for the denoise+upscale chain at 1440p Quality, plus RT acceleration structures.
