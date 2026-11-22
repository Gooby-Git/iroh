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

## 3b. P5-1 ladder for the first real-GPU runs (each ONE guarded launch; vanilla clone; `-Diroh.rt=true`; never two perf runs)

Guard budgets for every rung (SYSTEM SAFETY v2): preflight footprint vanilla + RT 2.5 GiB (pack + RT 5.0 GiB), total VRAM
kill at 12.5 GiB, own growth +3 GiB in 60 s, fps cap 240 (60 for look rungs), `MESA_VK_ABORT_ON_DEVICE_LOSS=1`, one
world per launch, RT memory owner `iroh-rt` capped at 1.5 GiB inside Iroh (refusal = wait / shrink residency, never
force). A guard kill stops the ladder until the owner acks.
- **R0 capability probe (no world):** boot to title with `-Diroh.rt=true`; StartupReport line lists AS/ray-query/BDA
  features and `R16G16B16A16_SNORM` AS vertex support (`RtAccel.vertexFormatSupported`); inert check with RT off.
- **R1 coverage + memory:** one world, 3 fixed cameras: RT primary vs raster coverage >= 99.5%; BLAS bytes per
  triangle built and compacted ON RADV (lavapipe: 104 B, 0% compaction), quads per non-empty section, AS total vs the
  1.5 GiB cap, TLAS instance count.
- **R2 per-stage GPU time (exclusive slot, load1 < 3):** timestamps per pass at 1707x960 for LOW/MEDIUM/HIGH/ULTRA:
  G-buffer, AS upkeep, sun visibility, block light, GI + cache + resolve, reflections, checkerboard recon, own denoiser
  (3 channels), compose, FSR 3.1, post. Replaces every ESTIMATE in P5-DESIGN sections 10 and 13 and
  `RtPreset.estimateMs`; also the measured G rays/s on RADV (the Radiance datum is an assumption).
- **R3 correctness scenes:** the rtLook scenes rebuilt in a flat test world by command (village house with torches,
  cave mouth, pond, oak trees, smooth plaza) at the same times of day: screenshots next to the offline contact sheets;
  light-switch lag (lever + lamp) in frames; disocclusion trail when strafing past a wall.
- **R4 pack memory + rejoin**, **R5 look review (owner)**, **R6 soak**: as section 3.
First real-GPU measurements wanted, in order: AS bytes/triangle + compaction on RADV; rays/s per pass; denoiser ms per
channel at 1707x960; FSR 3.1 ms; whether ray query in compute on RADV matches RT pipelines for one pass; VRAM of the
whole RT set at RD 12 (target <= 1.5 GiB RT + denoiser/FSR targets).

## 3c. FINAL R-ladder (P5-2, 2026-11-22; supersedes 3b where they differ). Each rung = ONE guarded launch, vanilla clone first

Common to every rung: Proctor guard v2 (SYSTEM SAFETY v2), never two perf runs, fps cap 240 (60 for look rungs),
`MESA_VK_ABORT_ON_DEVICE_LOSS=1`, one world per launch, Vista bounded, C2ME OpenCL off. RT flags: `-Diroh.rt=true
-Diroh.rt.look=RAY_TRACED` (or iroh.json `look: RAY_TRACED` + the dev gate). Guard footprints for preflight: vanilla + RT
**2.5 GiB**, pack + RT **5.0 GiB**, against the guard's **12.5 GiB** total-VRAM kill; Iroh's own owner `iroh-rt` cap 1.5 GiB
(`-Diroh.rt.capMiB`), residency 8 chunks (`-Diroh.rt.residency`). A guard kill stops the ladder until the OWNER acks.
Bisect switches on every rung: `-Diroh.test.failInit=rt` (RT must fall back to raster with ONE line and inert=NO only
through failures{rt}); `-Diroh.rt.look=NAVIDSON` (same jar, RT inert: the self-check line must equal the int-3 line);
`-Diroh.rt.residency=2` (BLAS set small); `-Diroh.rt.capMiB=256` (refusals: building must stall, never crash).

- **R0 capability probe (title only, no world).** Flags: RT flags. Pass: self-check `device{... rt=[accelerationStructure
  rayQuery bufferDeviceAddress snormAsVertex=true]}` and `api ... rt=on(MEDIUM,residency 8,cap 1536MiB)`; no validation
  error; the same jar WITHOUT the flags prints the int-3 line byte for byte (inert=yes, no `rt=`). Record: whether RADV
  offers R16G16B16A16_SNORM as an AS vertex format (if false: RT stays off with "unavailable"; the fix is a decode pass,
  card P5-3), `VkPhysicalDeviceAccelerationStructurePropertiesKHR` limits. Bisect: failInit=rt -> one line, raster.
- **R1 coverage + memory (one world, 3 fixed cameras, 60 fps).** Flags: RT flags + Iroh debug capture. Pass: RT primary
  vs raster coverage >= 99.5% of pixels (the in-game step (a)); `iroh-rt` used <= 1.5 GiB; VRAM total within the R1
  footprint; BLAS bytes per triangle built and compacted ON RADV (lavapipe: 104 B, 0% compaction; plan assumes 64 B);
  quads per non-empty section; TLAS instances; per-frame build counts never above the budget (8 builds / 200k tris at
  MEDIUM) in the RtSession stats line. Bisect: residency=2, capMiB=256, section positions (if coverage fails near
  section borders: the TLAS translation vs the compressed vertex offset, VkRtBackend TRANSFORM).
- **R2 sun shadows (one world, noon, 60 fps).** Pass: sun visibility matches the offline sheets at the same camera by
  eye (owner-free objective part: shadow edges under trees and houses, no acne, no light leaks at section borders);
  tint-only mesher colour active (no double AO: compare a flat grass field to the RT-off frame, luminance ratio within
  the P5-2 calibration band 0.90-1.10 of flat). Bisect: `RtMeshMode` (rebuild with RT off -> shaded colours back).
- **R3 full chain at MEDIUM with budgets (exclusive perf slot, load1 < 3).** Pass: timestamps per pass at 1707x960
  for LOW / MEDIUM / HIGH (G-buffer, AS upkeep, sun, block light, GI + cache, reflections, recon, denoiser 3 channels,
  compose, FSR 3.1, post); MEDIUM total within 16.6 ms at 1440p output on the RX 9070-class card or a written plan;
  VRAM inside the footprints above for 10 minutes of flight; frame-time histogram while flying (BLAS build stalls
  visible as > 2 ms spikes); light-switch lag in frames (lever + lamp). Replaces every ESTIMATE in P5-DESIGN 10/13 and
  `RtPreset.estimateMs`. Bisect: presets, `-Diroh.rt.residency`, checkerboard off.
R4 pack memory + rejoin, R5 look review (owner), R6 soak: unchanged from section 3.

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
