# P5 plan: from the offline prototype to ray tracing in game (card P5-0, 2026-11-22)

Starting point: `docs/P5-DESIGN.md`; the offline pipeline runs on lavapipe (`./gradlew rtReplay`, `rtSeamTest`,
`rtProbe`). Rule for every card below: build and verify offline first; the real GPU only for what lavapipe cannot
prove (speed, RADV behaviour, VRAM, the game's own data). Implementers never launch the game: each GPU step is a
Proctor run requested in NEXT and driven by an `iroh-proctor` agent through the guard.

## 1. Cards

| card | model | what | offline-testable | needs the real GPU |
|---|---|---|---|---|
| P5-1 | O | Game geometry adapter: `RtGameBridge` fills `SectionGeometry` from VulkanMod's DrawBuffers / AreaBuffer (device address + segment offset, quads per bucket); RT mode mesher writes tint-only vertex colour + emissive quads; device creation requests the RT extensions/features ONLY when `RtSettings.active`; VMA allocator with the buffer-device-address flag; BLAS queue run at `BEFORE_MAIN_PASS`; owner `iroh-rt` on MemoryGuard | adapter against a recorded section capture; device-creation flags in a recording device; inert-when-off already proven | R1: in-game debug view (RT primary vs raster coverage, step (a) in game); R1b: measure BLAS bytes/triangle + compaction on RADV and quads per section in real worlds, update `RtMemoryModel` |
| P5-2 | O | Entities and block entities into the TLAS: capture through the normal renderers into a recording vertex consumer (Caustica idea), per-entity BLAS with refit + periodic rebuild, rigid reuse, previous transforms for motion | capture format + refit logic with recorded streams on lavapipe | R3 scenes with mobs (shadows cast, reflections) |
| P5-3 | O/S | Materials: per-sprite table from the atlas (emission heuristic from light level + bright texels, smooth list, water, cutout), LabPBR `_s/_n` compile, override data file | fully offline: run over the vanilla jar's textures and the pack's resource packs | visual check only (owner's taste call) |
| P5-4 | O | Light locality (per-section light grid mixed with the global CDF), ReSTIR temporal + spatial reuse, held/dynamic lights | fully offline in rtReplay (add a torch-dense room; noise vs frames curve must improve) | perf only |
| P5-5 | O | Hybrid preset in game: Iroh's raster writes the RT G-buffer layout (fp16 targets + material id), passes run, compose -> existing post; first RT image | pass correctness already offline; G-buffer writer vs rtReplay's reference writer on a captured frame | R2 per-stage timings, R3 correctness scenes |
| P5-6 | O | Denoise + upscale: NRD (card P6-1 on P6-0's interface) behind `RtDenoiseStage`, FSR 3.1 (P4-2a) behind `RtUpscaleStage`, reactive mask from disocclusion, jitter into the G-buffer projection | NRD and FSR both run on lavapipe already (P6-0, P4-2a): wire into rtReplay's 30-frame move | R2, R3 (shimmer/ghost checklist) |
| P5-7 | O | Perf structure: checkerboard half rate, ray-queue compaction (indirect dispatch), compute-queue build executor with timeline semaphores, GPU-filled TLAS + refit | logic + image equivalence offline | R2 (the point of the card) |
| P5-8 | O | Navidson look in RT (bridges to P7): Navidson sky into a sky-view LUT for misses, Tempest cloud-shadow mask, mountain fog + weather in compose after denoise, grade after the upscaler | offline via the pack replay harness (P3) + rtReplay | R5 look review (owner taste) |
| P5-9 | S | Settings: the Iroh section's ONE look switch (Navidson <-> RT) + preset, capability report, iroh.json keys | config tests | R1 smoke |
| P5-10 | P | RT correctness scenes (outline P5-7): furnace, Cornell room by command, emissive colour wall, water tank, mirror hall, every dimension | | R3 |

Order: P5-1 -> (P5-3, P5-4 offline in parallel) -> P5-5 -> P5-6 -> P5-2 -> P5-7 -> P5-8. P5-9 any time.

## 2. RADV-specific risks (rt-radiance.md issue clusters) and what each card does about them

| risk | seen in Radiance | Iroh mitigation | proven by |
|---|---|---|---|
| device loss / GPU timeout | RDNA3/4 timeouts, "failed buffer creation" after 1-2 min on a 9070 XT | bounded work per dispatch (tile dispatches above a size), BLAS builds capped per frame, no allocation in the frame loop (pooled scratch + AS buffers), MemoryGuard owner cap with refusal = wait; test runs with `MESA_VK_ABORT_ON_DEVICE_LOSS=1` and `RADV_DEBUG=hang` dumps | R4 soak, R6 |
| dark / black output, lights not emitting | the largest cluster (PBR-pack dependence, missing emission) | emission from the light level + texel heuristic (no pack needed); compose clamps NaN/Inf to 0 and counts them; a "lighting sanity" Proctor probe: mean luminance of a torch-lit room and of daylight above thresholds; materials debug view | P5-3 offline, R3 |
| BLAS build stalls / chunk-load hitches | slow chunk loading, render-thread bottlenecks | per-frame caps (sections, triangles), nearest-first, compute-queue executor (P5-7), compaction one frame later, latest-version-wins | R2 frame-time histogram while flying |
| VRAM growth, leaks | memory/VRAM leak reports | every BLAS through `BlasBuildQueue` (resets and world unload free), deferred frees behind the frame fence, owner accounting checked by the guard's growth rule; one rejoin must return AS memory to baseline | R4 |
| format/feature gaps | "no suitable device" launch failures | RT strictly optional: features requested only when `RtSettings.active`; SNORM vertex format checked at runtime with a decode fallback; ray query only (no SER, no OMM required) | rtSeamTest (inert), R1 |
| UI tied to the world pass | +50% FPS with F1 (#62) | not applicable: Iroh is Vulkan-native, UI composited after the world in one device | - |

## 3. Proctor ladder (each rung one guarded run; never two perf runs at once)

All rungs: vanilla clone first (20 s boot), `-Diroh.rt=true`, look RAY_TRACED, fps cap 240 (60 for look runs),
`MESA_VK_ABORT_ON_DEVICE_LOSS=1`, Vista bounded, C2ME OpenCL off, one world per launch until R4 proves rejoin.
- **R1 (P5-1) boot + debug view:** device reports the RT extensions; world loads; AS memory under the `iroh-rt` cap;
  in-game step (a): RT primary coverage of raster pixels >= 99.5% at 3 fixed cameras (Iroh debug capture); no
  validation errors (needs the owner's `vulkan-validation-layers` install; until then note "validation not run").
- **R2 (P5-5/P5-6/P5-7) per-stage GPU time:** timestamp per pass at fixed cameras, LOW/MEDIUM/HIGH, exclusive slot,
  load1 < 3; replaces the budget column of P5-DESIGN section 10; frame-time histogram while flying (BLAS build stalls).
- **R3 (P5-5/P5-10) correctness scenes:** furnace (uniform albedo under sky: expected radiance known), Cornell room,
  emissive colour wall, water tank, leaves/pane shadows; screenshots against offline expectations.
- **R4 (pack) memory + rejoin:** full Elemore pack, RD 12: AS + cache + targets within the planned numbers below; one
  rejoin returns device memory within 300 MiB of the first world (guard v2 rule).
- **R5 look review:** Navidson-in-RT items (owner taste call; reserved for the owner).
- **R6 soak:** 30 min flight + idle, VRAM growth rule, no device loss.

## 4. Memory: per section, at render distance 12 / 24, and the caps

Measured: lavapipe 104 B per triangle (208 B per quad), compaction saved 0% there; lavapipe's format says nothing about
RADV. Planning numbers use RADV **assumed** 64 B/triangle compacted (UNVERIFIED: R1b measures it). BLAS input costs
nothing extra (the raster arena is read in place). Quads per non-empty section are not measured in Iroh yet: 1000 /
3000 / 6000 are low / typical / foliage-heavy guesses; 8 non-empty sections per column assumed (overworld with caves).
Totals include the TLAS, build scratch for an 8-section batch, a 2^20-entry radiance cache (48 MiB) and ~64 B/pixel of
RT buffers at 1707x960 (~100 MiB). From `./gradlew rtSeamTest`:

| render distance | sections | 1000 quads/section | 3000 | 6000 |
|---|---|---|---|---|
| 8 | 2,312 | 432 MiB | 998 MiB | 1,846 MiB |
| 12 | 5,000 | 762 MiB | 1,984 MiB | 3,816 MiB |
| 16 | 8,712 | 1,217 MiB | 3,345 MiB | 6,537 MiB |
| 24 | 19,208 | 2,505 MiB | 7,195 MiB | 14,231 MiB |

Per section at 3000 quads: ~375 KiB of BLAS (RADV assumed), 610 KiB on lavapipe.
**Consequence: the RT residency radius must be independent of (and smaller than) the render distance.** Sections
outside it are raster-only, like Vista's far terrain. Proposed defaults: residency 8 chunks horizontally, +-4 sections
vertically around the camera (the queue's `radius`), which keeps typical worlds near 1 GiB.
**Caps to set:** owner `iroh-rt` cap 1.5 GiB by default (refusals shrink the residency radius by one ring before
anything waits longer than a second); Iroh's own MemoryGuard cap unchanged. Guard v2 preflight footprints for RT runs:
vanilla + RT 2.5 GiB (was 1.5), pack + RT 5.0 GiB (was 3.5); one RT pack game + headroom stays well under the 12.5 GiB
total. Revisit all three numbers after R1b.

## 5. Open questions for the owner (also in the P5-0 report)

1. Entities/block entities in v1 receive RT light but cast no RT shadows and are absent from reflections until P5-2:
   acceptable for the first in-game milestone?
2. RT residency radius 8 chunks (raster beyond, like Vista): acceptable, or is RT to the full render distance a must
   (costs 2-7 GiB at RD 12-24 by the estimate above)?
3. Sun angular size for the look: real (0.27 deg, crisp shadows) or larger for softer Navidson-like shadows?
