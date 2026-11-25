# Iroh RT plan: path tracing in the full release (card RT-PLAN, 2026-11-24)

Scope: the staged route from what integration/int-4c has today to a high-quality real-time path-traced mode, as fast as
possible without lowering quality, reusing only what a public LGPL-3.0 project may reuse. Base: worktree
`~/Projects/iroh-wt-p41` (int-4c `8d16cbe7`). Owner decisions applied: "Ray tracing ships in the full release",
"NVIDIA RTX Remix ... PUBLIC-PROJECT GATE", "FSR 3.1 quality first; DLSS and FSR 4 parked", "Opus only when required",
answers 2, 4, 6, 9, 10, 11, 12 and 13, SYSTEM SAFETY v2. V = checked in a file or log named here, U = unverified.

## 0. Summary

- The RT core is further along than the phase list suggests. Offline (lavapipe) Iroh already has per-section BLAS from
  the raster arena, TLAS per frame, sun, emissive light with a light grid + ReSTIR temporal, 1-bounce GI with a radiance
  cache, reflections and water, entity part BLAS with object motion vectors, its own SVGF-family denoiser, the FSR 3.1
  hand-off with a reactive mask, and a calibrated look (rt-iroh.md P5-0..P5-3). In game, only the AS side runs:
  `r1-rt-safe` PASSED clean on RADV with jar 4c-f11o (STATE 17:52, 2026-11-24). No ray is traced in game yet.
- So the critical path is game integration, not research: G-buffer + hit shading against the real atlas, lights from
  real chunks, entity capture, materials, then the path-traced preset and perf tuning on RADV.
- Three things buy speed without costing quality:
  1. **Real-world captures replayed offline** (RT-1): one guarded launch dumps resident sections, the atlas and the
     camera; every later card iterates on lavapipe against real Minecraft geometry in seconds. Captures hold Mojang
     textures, so they and any golden rendered from them stay in `~/.cache`, never in the repo.
  2. **Reuse from Caustica (LGPL)**: LabPBR decode, emission semantics, dielectrics, GGX/VNDF maths, water/medium, the
     Hillaire sky LUTs, ray-cone texture LOD. All pass the gate except two named functions (section 3).
  3. **Keep the own denoiser as the default** and treat NRD as an offline quality reference plus an optional backend.
     The own denoiser already matches stock NRD offline (RMSE 0.0213 vs 0.0219/0.0215; beats it on the Minecraft-like
     scene and on light-switch lag, trails SIGMA on penumbras), and NRD's licence makes shipping it with a free LGPL
     mod a legal question (section 3, Q1). Building on NRD would put the release behind a legal answer.
- From rtx-remix-minecraft the value is conventions and the hook map, not code. Iroh's own capture, budget and camera
  code is already ahead, and most Remix files that would help are tied to vanilla data or the closed runtime. Gate
  results per file are in section 3: three files are eligible but none is needed for the plan as written.
- Estimate (section 6): about 26 agent sessions (6 Opus cards, 11 Sonnet cards, Proctor rungs). Gate 5 (complete
  picture) around Dec 12, Gate 6 (60 fps at 1440p Balanced on the RX 9070 XT) around Dec 22, Phase 7 look sign-off
  late January. Confidence low-medium. Most of the risk is in RADV speed and owner availability for in-game rungs.

## 1. Inventory (int-4c `8d16cbe7`)

| piece | where | state | evidence |
|---|---|---|---|
| Device features, inert seams | `rt/game/RtDeviceFeatures`, `RtSwitch`, `RtRuntime`, `RtStartup` | in game V (inert when off; RT chain on with `-Diroh.rt=true`) | rtSeamTest 58/58; IntegrationInertTest pins; r1-rt-safe |
| BLAS per section, TLAS | `rt/vk/RtAccel` (SNORM AS vertices, compaction, masks SOLID/TRANSLUCENT/ENTITY/SELF, refit), `rt/game/VkRtBackend`, `VulkanModSections` (arena read in place) | in game V (builds only) | R1-HANG fixes 33c35db7, R1-REV 2a227cb7; r1-rt-safe PASS; r1-rt-as rerun owed (regex false positive) |
| Session, residency, budgets | `RtSession`, `BlasBuildQueue` (eviction radius+1), `RtLimits` (safe mode, hard ceilings), `RtGuardMemory` (owner `iroh-rt`, cap 25% <= 1536 MiB) | in game V | rtWorldReplay 2/2, rtHangRepro 17/17 |
| Frame hooks | `RtFrameHooks` (FrameStage.WORLD_START; builds only; `tlasForTrace()` for the future trace pass) | in game V | rt-iroh.md R1-HANG |
| G-buffer | `RtGBuffer` (RGBA32UI: oct normal, albedo rgb8 + 6-bit material/tile id + sky-open bit, viewZ, MV half2); `gbuffer.vert/.frag`, `gbuffer_ent.vert` | offline V; game: designed, NOT wired | RtGBuffer javadoc; P5-2 notes |
| Tint-only mesher colour | `RtMeshMode` (shadeQuad reads it) | built, OFF until RT shades the picture; fluids still bake shade | rt-iroh.md P5-2/INT-4 |
| Sun / moon | `sun.comp`, `sunvis.comp` | offline V (2/8182 px vs CPU) | rtReplay (b) |
| Emissive light | `emit.comp` (spherical-rectangle sampling), `restir.comp` (ReSTIR DI temporal), `RtLightGrid` (main, Minecraft-free), `RtMaterialRules` (40 vanilla emitters + default colour) | offline V; game light extraction MISSING; spatial reuse biased (cause unknown) | rtLights 12/12 |
| Indirect diffuse | `gi.comp` + `rt_cache.glsl` + `cache_resolve.comp` (1 bounce + own cache) | offline V (relMAE 0.022) | rtReplay (d) |
| Reflections, water | `refl.comp` (roughness gate, water Snell + TIR, absorption, rain film) | offline V | rtReplay (e), rtLook |
| Entities | `RtEntityBudget` (main), part BLAS + posed instances, object MVs | offline V; game capture MISSING | rtEntities 11/11 |
| Look | `RtLook`, `RtLookModel` (auto exposure, calibration), `rt_look.glsl` (own sky, NvMountainFog, waves, wetness), `post.comp`, `compose_look.comp` | offline V (calibration 1.022) | rtLook 7/7; sheets ~/Pictures/iroh-rt-preview |
| Presets | `RtPreset` LOW / MEDIUM / HIGH / ULTRA / PATH_TRACED (`estimateMs` = estimates) | offline | RtPreset.java |
| Denoiser (own) | `rt/denoise/SvgfDenoiser` + `tools/shaders/svgf_{temporal,atrous}.comp` (SPIR-V checked in), `TemporalAccumulationDenoiser`, `Denoiser`/`Slot`/`DenoiseGpu` API | offline V; game MISSING | nrdReplay/mcReplay/chainReplay (nrd.md 7) |
| NRD bridge | `iroh-native/nrd/` (fetch script by commit + hash, CMake, own JNI shim `iroh_nrd_jni.cpp`), `rt/denoise/nrd/*` Java executor | offline V; never in the jar (V: f8 jar holds only `natives/linux-x64/libiroh_fsr.so`) | nrd.md 1-6 |
| Upscale | FSR 3.1 adapter (raster path, P4-1b), `RtUpscaleStage`, step (i) chain with shading-change reactive mask | raster in game V; RT chain offline V | rt-iroh.md P5-1 step (i), fsr.md |
| Materials beyond emission | none (no LabPBR, no auto-PBR, no per-sprite table, no normal maps) | MISSING | RtMaterialRules only |
| Hit shading in game | harness samples a 128x128 storage-buffer toy atlas (`rt_common.glsl:11`) | game MISSING: real atlas image + arena vertex fetch | rt_common.glsl |
| Particles, weather, hand | raster paths only | design: raster after compose | P5-DESIGN 1 |
| Far field past residency | none | MISSING (rays miss beyond residency) | P5-PLAN 4 |
| Path tracer (multi-bounce, all-roughness specular, dielectrics) | none | MISSING | P5-DESIGN 12 |
| Offline harnesses | rtReplay, rtLook, rtEntities, rtLights, rtSeamTest, rtProbe, rtHangRepro, rtWorldReplay, nrdReplay, mcReplay, chainReplay (build.gradle 582-1080) | V | allsuites on int-4c |

Trap kept: a fresh worktree lacks the git-ignored `iroh-native/natives/linux-x64/libiroh_fsr.so`; copy it from
iroh-wt-p41 or rtReplay step (i) and rtEntities fail and the jar ships without FSR.

## 2. Architecture

### 2.1 Frame graph in RT mode (look = RAY_TRACED)

```
CPU, render thread                         GPU, one graphics queue (+ compute queue later, RT-12)
------------------                         ----------------------------------------------------
section remesh -> BlasBuildQueue  -------> BLAS builds (<= budget/frame) -> TLAS (reused if unchanged)
light extraction -> RtLightGrid   -------> light list + cell CDFs (upload ring)
entity capture -> part instances  -------> entity BLAS cache / refit, TLAS instances (prev + cur pose)
                                           main world pass (raster, jittered, render size):
                                             terrain/entities/block entities -> G-buffer A + B + depth
                                           [VulkanImage.endPassRunResume: RT passes, never inside the pack's W pass]
                                             sun/moon visibility       1 ray      (sunvis)
                                             emissive DI               ReSTIR temporal, 1 visibility ray
                                             indirect                  Balanced: 1 bounce + cache; Max: cache-terminated paths
                                             specular / refraction     roughness-gated; Max: all
                                             own denoiser              diffuse (IN_ALBEDO), specular, shadow
                                             compose                   albedo*diffuse + spec + first-hit emission + sky
                                             far-field blend           light levels past the residency edge
                                             fog / Navidson atmosphere AFTER denoise
                                           FSR 3.1 (linear HDR, depth, MV, reactive + T&C masks)  -> display size
                                           tone map, Navidson grade, bloom (post.comp)
                                           raster after compose: particles, weather, name tags, outline, hand
                                           UI
```
Primaries stay rasterised (P5-DESIGN decision; both Radiance and Remix trace them, the budget does not allow it on
RDNA4). Water and glass first interfaces are handled from the G-buffer by the specular/refraction pass.

### 2.2 Buffers (render size 1707x960 = FSR Quality at 1440p, 1.64 Mpx; sizes are targets to be measured in R3)

| buffer | format | MiB | written by | read by |
|---|---|---|---|---|
| G-buffer A (exists) | RGBA32UI: oct normal (geometric), albedo sRGB rgb8 + flags, viewZ f32, MV half2 (pixels, prev-cur, unjittered) | 25 | main pass | all RT passes, denoiser, FSR (MV) |
| G-buffer B (new, RT-4a) | RGBA32UI: shading normal oct16x2 (after normal map), roughness8 metal8 emission8 SSS8, material id u16 + sprite flags u16, spare (hit distance for the denoiser) | 25 | main pass | lighting, denoiser |
| depth | D32F (FSR Q10 may make it reversed-Z; RT reads viewZ from G-buffer A, so it is immune) | 6.3 | main pass | FSR, raster-after-compose |
| sun visibility | R16F (+ blocker distance R16F for penumbra, RT-8) | 6.3 | sunvis | denoiser shadow channel |
| reservoirs | 2 x 16 B/px (current, previous) | 50 | restir | restir next frame |
| diffuse / specular radiance | RGBA16F each (+ hit distance in alpha) | 25 | gi, refl | denoiser |
| denoiser history and moments | own SVGF set | ~112 (nrd.md 7) | denoiser | denoiser |
| radiance cache | hash grid, fixed slots | 32 (cap) | gi | gi, cache_resolve |
| composed HDR | RGBA16F | 12.5 | compose | FSR |
| BLAS + TLAS + scratch | per section / per part | <= `iroh-rt` cap 1536 | AS builds | all traces |
| material table | per sprite 16 B; sprite-id map at atlas/16 | < 2 | CPU at stitch | G-buffer pass, hits |
| LabPBR atlases | `_n`, `_s` RGBA8 with albedo layout and mips | 2 x albedo atlas | CPU at stitch | G-buffer pass, hits |

Total screen-space set ~300 MiB at 1707x960 plus BLAS (<= 1.5 GiB): inside Proctor's vanilla+RT footprint of 2.5 GiB.

### 2.3 Data paths that do not exist yet

- **Hit shading in game (RT-2).** `rt_hit.glsl`: fetch the three vertices of the hit triangle from the arena through
  buffer device address (the BLAS already reads the arena in place; quads -> triangles by index reconstruction),
  UV-interpolate, sample the block atlas with a ray-cone LOD (Caustica idea; prevents aliasing in reflections and GI),
  alpha-test cutouts in the ray-query candidate loop (no OMM on RADV, V amd-radv.md), sRGB decode. Geometric normal
  from positions (or `ray_tracing_position_fetch`, exposed on RADV, V). Entities need their own textures at hits:
  a bindless array of sampled images with a per-instance slot (descriptor indexing features in Iroh's chain: U, check
  in RT-2; a feature-chain change triggers hw-matrix on the card).
- **Materials (RT-4).** Per-sprite material record in one SSBO, looked up from a sprite-id map built at atlas stitch
  time (atlas/16 grid; minimum block-atlas sprite size in the pack U, verify). G-buffer pass and hits share the same
  lookup, so a material change never needs a remesh. Sources in priority: LabPBR `_n`/`_s` sidecars > shipped rules
  file (`assets/iroh/rt/materials.json`, per-namespace overrides for pack mods) > tag heuristics (ores, metals, glass,
  ice, leaves) > defaults (Lambert, F0 0.04). Emission: LabPBR `_s` alpha overrides the automatic emission (light level
  x own colour x area normalisation, `RtMaterialRules`), so every modded light block glows with no data (decision 12;
  Remix's "no `_s`, no glow" is wrong for a 370-mod pack).
- **Lights from real chunks (RT-3a).** On section remesh: for each quad whose sprite emits (sprite emission mask from
  texels, gated by `BlockState.getLightEmission()`), one rectangle light = bounding rectangle of emissive texels,
  radiance from the light level (Caustica technique). Stable per-section light slots; `RtLightGrid.rebuildAround` on
  the 27 cells; upload through the frame ring.
- **Entities (RT-5).** Capture inside Iroh's own entity render path (ModelPart parts keyed by cuboid identity -> cached
  part BLAS; pose + previous pose per part -> TLAS instances and object MVs); non-ModelPart renderers (GeckoLib,
  AzureLib, EMF/ETF, elemore-bosses) through a capturing VertexConsumer -> transient BLAS within the refit budget;
  block entities on an identity pose stack (block-local, cacheable); first-person hand and held item raster-only;
  player body as a SELF-mask instance. Written clean-room from the Remix hook map (rt-remix-1211.md section 2).
- **Far field (RT-3b).** Pixels past the BLAS residency radius get no RT hits. Blend at the residency edge to lighting
  from vanilla's baked light levels (sky light = sky visibility, block light = local emission; already in the vertex
  light short), shaded with the same sun/sky constants. Keeps memory bounded and Vista's far terrain consistent.

### 2.4 How it plugs into Iroh's frame

- One guard: `RtSwitch.ON` (dev gate + look). Off = inert by construction (rtSeamTest constant-pool scan, inert pins).
- RT and the Navidson pack are exclusive by the Look switch (MainPassArbiter rules 8-11); RT + render scale + FSR
  compose through the same upscaler cut at render size.
- RT recording only through `VulkanImage.endPassRunResume`; traces use only `VkRtBackend.tlasForTrace()` (0 unless the
  TLAS was built this frame and no BLAS died since).
- Denoiser inputs follow NRD's documented packings (option A, re-implemented) so the own denoiser and an optional NRD
  backend share one producer set (P6-PLAN).
- FSR: one MV buffer (UV units at the `RtChain.pack` site), shared jitter (Halton 32), reactive mask from the
  shading-change test (rt-iroh.md step i), T&C mask for water/glass/particles; FSR-Q11 dynamic resolution keeps the
  RT targets at max size and renders a sub-rect.

### 2.5 Safety rails every RT card keeps (from R1-HANG)

Capped ray-query candidate loops (256) and RIS M (32), finite-ray guards, range checks on every BDA input,
power-of-two `alignUp` only, transient buffers per build, TLAS reuse rule, exception-safe allocation (`failAllocationIn`
sweep). New for passes that trace: dispatches tiled so no single dispatch can run long enough to trip the amdgpu ring
timeout, and a NaN/inf counter in compose that the stats line prints (any non-zero = rung abort).

### 2.6 Presets and tiers

| tier (UI) | maps to | content | render scale | target on RX 9070 XT, 1440p out |
|---|---|---|---|---|
| Max | PATH_TRACED | cache-terminated multi-bounce GI, all-roughness specular, dielectric water/glass, ReSTIR DI (temporal; spatial once its bias is fixed), full rate | 1.5 (Quality) or Native AA with dynamic res | 60 fps with FSR-Q11 holding the target |
| Balanced | MEDIUM/HIGH | sun + emissive DI full rate, GI 1 bounce + cache half rate, reflections roughness < 0.3 | 1.5 | RT frame <= 14 ms (60+ fps) |
| Safe | LOW (hybrid) | RT sun shadows, ReSTIR DI, GI half rate + cache, mirror-like reflections only | 1.7-2.0 | <= 9 ms; RTX 2060 / RX 6600 class 1080p 40+ fps (U) |
| Navidson | pack look | owner's shader through the Iris-pack loader | FSR as chosen | non-RT GPUs (decision 2) |

Auto-pick: no RT extensions or SNORM AS vertex format -> Navidson (RT code never touched); RT with VRAM < 8 GiB ->
Safe; else Balanced; a 3-second timestamp calibration on first enable may lower it. "Without losing quality" applies
to the quality reference (Max) and to every speed-up card: a card that makes something faster must show equal or
better chain metrics (relMAE vs CPU reference, detail correlation, static flicker, frames-to-90% after a light change)
on the same scenes.

## 3. Reuse table with licence and gate verdicts

Gate for rtx-remix-minecraft files = DECISIONS "PUBLIC-PROJECT GATE" (1) own Apache-2.0 SPDX header, (2) not ported
from or substantially similar to Mojang code, (3) no closed NVIDIA dependency, (4) header kept + "Modified by Iroh
contributors, <date>: <what>" + NOTICE + Apache-2.0 text in the repo. Caustica/vulkanite/Vitrail = LGPL with NOTICE;
the same three content checks (other licence inside, Mojang-derived, NVIDIA-derived) are applied per file because
Caustica targets Minecraft 26.2 and ships NVIDIA material in its release artifacts. All checks below were run on
2026-11-24 against the local clones in `~/Projects/iroh/reference/` (Remix `a37b3b4`, Caustica `330acd2`,
NRD `v4.18.0`).

### 3.1 NRD (NVIDIA RTX SDKs licence)

| item | check | verdict |
|---|---|---|
| Licence text | `reference/NRD/LICENSE.txt` sha256 0f74d24d..., clause 4(e) l.80-84: may not use the SDK "in any manner that would cause it to become subject to an open source software license", examples include terms requiring it to be "redistributable at no charge" | V |
| What is in the repo | only Iroh's JNI shim (LGPL header; `#include "NRD.h"` from the fetched tree at build time), `fetch-nrd.sh`, `build-nrd.sh`, CMake, Java glue; `natives-nv/`, `nrd-src/`, `.nrd-cache/`, `build-nrd/` git-ignored; `git ls-files` shows no NVIDIA file | V clean |
| What is in the jar | f8 jar: `natives/linux-x64/libiroh_fsr.so` only, no NRD | V clean |
| Hole | `build.gradle:1023`: `-PbundleNv=true` copies `natives-nv/` into the jar | must be removed or refused for release builds (RT-0, RQ-RTPLAN-b) |
| Local fetch + build + use (dev, offline reference) | licence 1(a) | ALLOWED |
| Distributing `libiroh_nrd` with or for a free LGPL mod | 4(e)(iii); the shim is LGPL-licensed and statically linked with NRD into one binary | LEGAL QUESTION for the owner (Q1). Plan does not depend on it |
| Reading NRD shader source to re-implement its techniques in our denoiser | proprietary source | NOT DONE: own denoiser work uses published papers only (SVGF, A-SVGF, a-trous, PCSS-style penumbra) |

### 3.2 rtx-remix-minecraft (Apache-2.0)

| file | (1) header | (2) Mojang | (3) NVIDIA closed | use | verdict |
|---|---|---|---|---|---|
| `renderer-core/.../texture/LabPBRConverter.java` (529) | SPDX Apache-2.0 V | 0 Minecraft imports; LabPBR is a public spec | none (own `PixelImage`) | writes Remix's channel layout | ELIGIBLE, NOT PLANNED: decode from the public LabPBR spec + Caustica `RtLabPbr` fits Iroh's layout better |
| `renderer-core/.../entity/EntitySweepPlanner.java` (87) + `util/ClosestKHeap.java` | SPDX V both | 0 Minecraft imports | none | closest-first off-frustum entity sweep | ELIGIBLE, NOT PLANNED: `RtEntityBudget` already does nearest-K with hysteresis; copy only if RT-5 measures a gap (then step 4 of the gate) |
| `rtxremix-java/native/water_normals.{cpp,h}` (588) | SPDX V | procedural FFT flipbook, no vanilla logic | none (MSVC/NEON intrinsics behind `_M_X64`/`_M_ARM64`) | water normal flipbook | ELIGIBLE, OPTIONAL: only if RT-6 judges Iroh's own wave normals worse; would need a Linux build in iroh-native |
| `.../light/BlockLightDefaults.java` (224) | SPDX V | 11 Minecraft imports; per-vanilla-block light shapes and offsets read off vanilla models | depends on their own `SpotLightBlock` | light table | STUDY ONLY: data derived from vanilla model geometry is in doubt; Iroh derives lights from emissive texels, which also covers modded blocks |
| `.../light/BlockLightRuleManager.java` (736) | SPDX V | 12 Minecraft imports | tied to Remix terrain types | JSON light rules | STUDY ONLY; reading the same JSON path later is format interop with our own parser (optional) |
| `KNOWN_LIGHT_COLORS` in `renderer-core/.../texture/BlockTextureManager.java` | SPDX V | none | none | colour palette | NOT COPIED: colours come from Iroh's own table and Navidson's (P7-4 owner look) |
| `.../texture/LabPBRSidecarFilter.java` (63) | SPDX V | hooks vanilla sprite source loading | none | keep `_n/_s/_sss` out of the atlas | STUDY ONLY (trivial; own mixin) |
| `.../particle/ParticleTint.java` (23) | SPDX V | 2 Minecraft imports | none | particle colour is a lightmap tint | STUDY ONLY (idea) |
| `.../light/FirstPersonShadowModelPlacement.java` (324) | SPDX V | reads vanilla player model placement | none | first-person body shadow | STUDY ONLY (Mojang-adjacent) |
| `.../sky/AtmosphericSkyGenerator.java` (773) | SPDX V | 2 Minecraft imports | GL compute, own profiler | sky | STUDY ONLY: Caustica's Hillaire LUTs fit Iroh's Vulkan compute path |
| `remix_c.h` (MIT) | MIT | n/a | API of the closed runtime | none | NEVER |
| visibility walk ports, entity/ModelPart capture mixins, model selectors, biome blend | Apache header but Mojang-derived (DECISIONS) | yes | n/a | n/a | NEVER |
| PNG masks, CC BY 4.0 materials pack | n/a | traces Mojang art / other licence | n/a | n/a | NEVER bundled; LabPBR support makes a user-installed pack work (compatibility target) |

### 3.3 Caustica (LGPL-3.0-or-later, taken under LGPL-3.0; MC 26.2 target, V `gradle.properties`)

Content checks: `LICENSE.md` covers project-owned code; no other licence header in `shaders/` (grep of
Copyright/License/SPDX/ported/based: one hit, below); `THIRD_PARTY_NOTICES.md` says release artifacts contain NVIDIA
NGX components and "source code provided by NVIDIA", so the NGX shim, `ngx/*`, `RtDlssRr` and RR guide code are out.
Java glue against 26.2 classes is always rewritten for 1.21.1 (and never copied where it re-implements vanilla).

| file (Caustica path) | lines | Minecraft imports | use in Iroh | verdict |
|---|---|---|---|---|
| `rt/material/RtLabPbr.java` | 87 | 0 (no imports) | LabPBR 1.3 decode | COPY with NOTICE + header (RT-4b) |
| `rt/material/RtEmissionHeuristic.java` | 66 | 0 | emissive texel detection | COPY with NOTICE (RT-3a) |
| `rt/material/RtEmissionGrid.java` | 96 | 0 | emission grid premultiplied for sampling | COPY with NOTICE (RT-3a) |
| `rt/material/RtEmissionSemantics.java` | 110 | 26.2 model API | walk block states with light emission, find emitting sprites | PORT logic, rewrite glue for 1.21.1 (RT-3a) |
| `rt/material/RtDielectrics.java` | 47 | 1 | IOR / transmission per class | COPY data + logic, rewrite the one Minecraft call (RT-4a) |
| `rt/material/RtBlockMaterials.java` | 616 | 7 | canonical pages, semantic mip filtering | PORT the mip-filtering part only (RT-4b) |
| `rt/material/RtMaterialOverrides.java` | 200 | 6 | override JSON format 2 | PORT schema idea, own parser (RT-4a) |
| `shaders/pipelines/world/math.slang` | 178 | n/a | GGX, VNDF sampling, HG phase | TRANSLATE to GLSL, EXCEPT `rrSpecularAlbedo` (l.48-70, "NVIDIA RR guide-buffer helper", fitted constants of unclear origin: excluded) (RT-4c) |
| `shaders/pipelines/world/water.slang`, `medium.slang` | 153 + 86 | n/a | waves, caustic term, Beer-Lambert, dielectric split | TRANSLATE (RT-7) |
| `shaders/pipelines/world/sky.slang` + `sky_lut/{transmittance,multiscatter,view}.comp.slang` | 559 + 201 | n/a | Hillaire atmosphere LUTs, sun/moon transmittance | TRANSLATE for lighting (RT-10); displayed sky stays Navidson-style |
| `shaders/pipelines/world/sky.rmiss.slang` `hash13` (l.34) | - | n/a | Dave Hoskins' Shadertoy hash | EXCLUDED (third-party snippet); use Iroh's own PCG hash |
| `shaders/pipelines/world/world_common.slang` ray cones | 354 | n/a | texture LOD at hits | TRANSLATE the ray-cone part (RT-2) |
| `shaders/pipelines/world/lighting.slang` | 326 | n/a | emissive bounding-rect lights, alias-table proposals | TRANSLATE the rect and alias parts; Iroh keeps its own spherical-rect sampling + ReSTIR (RT-3a/3b) |
| `primary/indirect.rgen`, `closest_hit`, `any_hit` | ~1280 | n/a | ray-tracing-pipeline structure | STUDY: Iroh is ray query in compute; ideas only |
| `native/ngx_shim`, `ngx/*`, `RtDlssRr`, `guides.slang` (RR) | - | - | DLSS | NEVER (DLSS parked, NVIDIA source) |

Every copied or translated Caustica file gets a header "Derived from Caustica <path> @330acd2 (LGPL-3.0-or-later),
modified by Iroh contributors, <date>: <what>", a NOTICE line, and a row in `docs/reuse-manifest.tsv` (RT-0).

### 3.4 Others

| source | licence | verdict |
|---|---|---|
| vulkanite | LGPL-3.0 (V) | nothing needed: GL/Vulkan interop and pack-authored ray passes do not apply |
| Vitrail | LGPL-3.0 (V `reference/Vitrail-Shaders/LICENSE`) | Iris-pack loader only; no RT content |
| Radiance (MCVR, Shaderpack-Advanced) | GPL-3.0 | STUDY ONLY via `knowledge/rt-radiance.md` (techniques: two-signal denoise split, compose order, half-rate checkerboard, cache-terminated GI) |
| Dirt-RT | GPL-3.0 | STUDY ONLY (prose in rt.md) |
| FidelityFX SDK 1.1.4 / 2.4.0 | MIT source (1.1.4; 2.4.0 HLSL) | FSR 3.1 already integrated; the 1.1.4 shadow denoiser is a reference only (own clean-room shaders rule) |
| Papers | n/a | ReSTIR (Bitterli 2020), Talbot 2005 RIS, SVGF/A-SVGF, Urena 2013 spherical rectangles, Hillaire 2020 atmosphere, Heitz VNDF: the source for everything written fresh |

## 4. Cards, in execution order

Common to every card: branch `card/RT-<n>` off the RT line tip; fast checks only (build, the harnesses it touches,
`injector_scan` if mixins change; hw-matrix only when the device feature chain changes); vkval core + sync +
GPU-AV clean under the card's label; the R1 abort rules for its in-game rung (validation error naming an AS /
scratch / descriptor, `VK_ERROR_DEVICE_LOST`, kernel `amdgpu ... ring ... timeout` or `wedged`, `Iroh: rt failed`,
`ranges refused` > 0, NaN counter > 0, guard kill); ONE guarded launch per rung, vanilla clone first, 60 fps unless
the rung is a perf rung (exclusive slot, 240 cap, load1 < 3); implementers never launch, they ask for the rung in
NEXT. Sessions = one agent run of about 3-5 hours including offline gates and review.

| card | model | depends | sessions |
|---|---|---|---|
| RT-0 reuse ledger + licence rails | Sonnet | - | 0.5 |
| RT-1 world capture + offline replay | Sonnet (Opus fallback) | - | 1.5 |
| RT-2 first light in game | Opus | - (RT-1 for replay goldens) | 2 |
| RT-3a lights from real chunks | Sonnet | RT-2 hit library | 1 |
| RT-4a material registry + G-buffer B | Sonnet | RT-2 | 1.5 |
| RT-3b Balanced chain in game + timestamps | Opus | RT-2, RT-3a | 2.5 |
| RT-M line merge onto the P2-7 terrain arena | Sonnet | RT-2, int-2b P2-7a | 1 |
| RT-5 entities and block entities in game | Opus | RT-2 | 2.5 |
| RT-4b LabPBR | Sonnet | RT-4a | 1.5 |
| RT-4c GGX, normal maps, auto-PBR | Sonnet (Opus fallback) | RT-4a | 1.5 |
| RT-6 translucents, particles, weather | Sonnet | RT-3b | 1.5 |
| RT-7 Max preset: path tracer | Opus | RT-3b, RT-4c | 2.5 |
| RT-8 own denoiser to NRD class | Opus | RT-3b | 2 |
| RT-10 sky LUTs for lighting | Sonnet | RT-3b | 1 |
| RT-11 presets, tiers, auto-pick, UI | Sonnet | RT-3b | 1 |
| RT-14 pack RT compatibility sweep | Sonnet | RT-5, RT-6 | 1.5 |
| RT-12 RADV performance pass | Opus | R3 + R-pt timestamps | 2 (spread) |
| RT-13 correctness scenes, Gates 5/6 | Proctor (Sonnet) | per rung | rungs |
| RT-9 NRD optional backend | PARKED (Q1) | owner answer | 3 if unparked |

### RT-0 [S] Reuse ledger and licence rails
- Files: `NOTICE`, `THIRD_PARTY.md`, new `docs/reuse-manifest.tsv` (source repo, commit, path, licence, gate results
  1-4, Iroh path, modification line), new `tools/reuse_gate.py`, `build.gradle` (line 1023 `bundleNv`), new
  `LICENSES/Apache-2.0.txt` only together with the first Apache file that lands.
- Do: the checker verifies, for each manifest row, the header in the Iroh file (upstream copyright + SPDX + "Derived
  from ..."/"Modified by Iroh contributors" line), the NOTICE line, and that excluded functions (`rrSpecularAlbedo`,
  `hash13`) do not appear. `bundleNv` refuses unless `-PprivateBuild=true` and prints why.
- Offline: `python3 -I tools/reuse_gate.py` PASS on an empty manifest and on a fixture row; negative fixtures (missing
  header, missing NOTICE line, excluded function present) each FAIL; `./gradlew jar -PbundleNv=true` refuses.
- In game: none.

### RT-1 [S, Opus fallback] World capture and offline replay
- Files: new `rt/game/RtCapture.java`, new `src/test/.../rttest/RtGameReplay.java`, `build.gradle` task `rtGameReplay`,
  Proctor scenario `iroh/rt/capture-3cams` (in the Proctor repo).
- Do: dev switch `-Diroh.rt.capture=<dir>` (inert pin like every RT switch) writes, at a chosen frame: resident
  section arena slices (staging copy recorded at frame end, read after the fence), the block atlas with all mips,
  `RtFrameData` (camera, jitter, sun), the light list (after RT-3a), entity part poses (after RT-5), versioned header,
  cap 512 MiB. `rtGameReplay -Pcapture=<dir>` rebuilds BLAS/TLAS from the slices through the GAME classes
  (`VkRtBackend` on HeadlessGpuDevice) and runs the current chain at 320x180 with metrics.
- Legal: captures contain Mojang textures. They live in `~/.cache/iroh-captures`; their goldens stay local; the repo
  keeps only synthetic scenes and goldens. A `.gitignore` line and a reuse_gate check refuse capture files in git.
- Offline: round trip on a synthetic `RtScene` (writer -> replay) equals rtReplay steps (a)/(b) exactly; vkval clean.
- In game: one guarded vanilla-clone run, three cameras (village noon, torch-lit cave, water + glass), R1 abort rules.

### RT-2 [O] First light in game: G-buffer, hit library, sun shadows
- Files: `rt/game/RtGBuffer.java`, `RtFrameHooks.java`, `VkRtBackend.java`, `RtDeviceFeatures.java` (descriptor
  indexing, optional position fetch), new `rt/game/RtPasses.java`, shaders `gbuffer.vert/.frag` (game variant: tint-only
  colour, sRGB decode), new `rt_hit.glsl`, `sunvis.comp`, `compose_look.comp`, `post.comp`; MainPassArbiter rule hook;
  `RtMeshMode` switched on with RT shading.
- Do: wire G-buffer A as the main pass MRT slot 1 in RT mode; record RT passes through `endPassRunResume` with
  `tlasForTrace()`; real atlas + arena vertex fetch + ray cones + alpha test in `rt_hit.glsl`; tiled dispatches; NaN
  counter; compose -> existing FSR adapter -> post. Balanced chain beyond the sun comes in RT-3b.
- Offline: rtWorldReplay extended with the sun pass (visibility vs CPU on the terrain arena), rtGameReplay on RT-1
  captures (local goldens), rtHangRepro, rtSeamTest, IntegrationInertTest, vkval 0, hw-matrix (feature chain change).
- In game: `r1-rt-as` rerun (coverage, bytes per triangle and compaction ratio ON RADV), then R2 sun: shadow edges, no
  acne, no light leaks at section borders, grass luminance ratio 0.90-1.10 of flat.

### RT-3a [S] Lights from real chunks
- Files: new `rt/game/RtLightSource.java`, `RtLightGrid.java`, `RtMaterialRules.java`; Caustica copies
  `RtEmissionHeuristic`, `RtEmissionGrid`, port of `RtEmissionSemantics` (1.21.1 glue), rect + alias parts of
  `lighting.slang` translated; manifest rows.
- Do: per-sprite emission mask at atlas stitch; per remeshed section, one rectangle light per emissive quad with stable
  slots; `rebuildAround`; upload ring; alias table for the global proposal.
- Offline: Minecraft-free unit tests; rtLights 12/12; rtGameReplay cave capture: light count equals a CPU recount;
  rebuild per remesh <= 0.2 ms CPU (thread CPU time).
- In game: folded into R3.

### RT-4a [S] Material registry and G-buffer B
- Files: new `rt/RtMaterials.java` (registry), `RtMaterialRules.java`, `RtGBuffer.java`, `gbuffer.frag`, new
  `assets/iroh/rt/materials.json`, `RtDielectrics` copy (Caustica), RtMaterialOverrides-style schema (own parser).
- Do: per-sprite 16 B record (roughness, F0/metal, emission, SSS, flags water/glass/foliage/emissive), sprite-id map
  (atlas/16; verify the minimum sprite size of the pack's block atlas first), tag heuristics, G-buffer B attachment.
- Offline: every sprite of a synthetic atlas maps exactly; modded emitters get the default colour; rtLook goldens
  re-baselined only for the material change (sheet shows the diff); vkval 0.
- In game: R-mat rung: material debug views (roughness, metal, emission) at the three capture cameras.

### RT-3b [O] Balanced chain in game with timestamps
- Files: `RtPasses.java`, `restir.comp`, `gi.comp`, `rt_cache.glsl`, `cache_resolve.comp`, `refl.comp`,
  `SvgfDenoiser.java` + `DenoiseGpu` game implementation (VMA + MemoryGuard owner, per-resource barriers,
  frames-in-flight rings), reactive mask, new far-field blend in `compose_look.comp`, stats line.
- Do: ReSTIR temporal DI, GI 1 bounce + cache, reflections, own denoiser, far-field blend, GPU timestamps per pass in
  the `[iroh-rt]` line.
- Offline: rtReplay (i) on the game classes within 5% of the harness numbers (chain relMAE 0.0564), rtGameReplay x3,
  chainReplay, rtHangRepro, vkval 0.
- In game: R3 (perf slot): timestamps LOW/MEDIUM/HIGH at 1707x960; MEDIUM RT frame <= 14 ms or a written plan;
  light-switch lag <= 12 frames to 90%; 10-minute flight within the VRAM footprint. Replaces every estimate.

### RT-M [S] Line merge onto the P2-7 terrain arena
- Why: P2-7a moves world terrain into a page arena with page ids in `baseInstance` on int-2b; `VulkanModSections` reads
  `AreaBuffer` ranges (retro-queue entry on DrawBuffers readers). Without this card RT geometry goes missing when the
  arena switch is on.
- Do: port the RT line onto the merged tip; give `RtSectionSource` (page buffer, offset) through an accessor; keep the
  arena switch and RT mutually safe.
- Offline: rtWorldReplay + terrainWorldReplay with the arena on and off; rtSeamTest; inert pins.
- In game: R1 coverage repeat with the arena on.

### RT-5 [O] Entities and block entities in game
- Files: Iroh's own entity render path mixins (ModelPart capture), new `rt/game/RtEntityCapture.java`,
  `RtEntityBudget.java`, `gbuffer_ent.vert`, `VkRtBackend` (part BLAS cache, per-instance texture slot).
- Do: rigid parts -> cached BLAS + posed instances with previous pose; capturing VertexConsumer for GeckoLib / AzureLib /
  EMF / elemore-bosses -> transient BLAS within budget; block entities on an identity stack; SELF body; hand raster-only.
- Offline: rtEntities on game classes 11/11; new capture test (fake render calls -> expected parts/instances, steady
  state allocates < 16 KB per 1e6 calls); vkval 0.
- In game: R-ent on the vanilla clone (64 mobs: shadows, reflections, budget stats, VRAM flat 5 minutes); pack variant
  (footprint 5 GiB) in RT-14.

### RT-4b [S] LabPBR 1.3
- Files: own sidecar filter mixin, new `rt/game/RtPbrAtlases.java`, `RtLabPbr` copy (Caustica), mip-filter port from
  `RtBlockMaterials`, sprite animation sync next to `MSpriteContents`.
- Do: keep `_n/_s` out of the albedo atlas; stitch them into parallel atlases with the same layout and mips; animate
  with the albedo frames; `_s` alpha overrides auto emission.
- Offline: a synthetic LabPBR test pack drawn by us (no Mojang art) under `src/test/resources`; decode tests
  (roughness (1-s)^2, metal >= 230, emission A < 255, normal Z rebuild); layout equality; memory accounting.
- In game: optional R-mat repeat with a user-installed LabPBR pack (never bundled).

### RT-4c [S, Opus fallback] GGX, normal maps, auto-PBR
- Files: `rt_common.glsl` (BRDF), `math.glsl` (translated from Caustica minus `rrSpecularAlbedo`), `gbuffer.frag`,
  `rt_hit.glsl`, `RtMaterials.java`.
- Do: GGX + VNDF, normal mapping in the G-buffer and at hits, auto roughness from tags + texture variance; auto normals
  from albedo stay OFF by default (owner taste call in Phase 7).
- Offline: white furnace within 2% over a roughness grid; rtLook sheets; vkval 0.
- In game: folded into R-pt.

### RT-6 [S] Translucents, particles, weather
- Files: FluidRenderer tint-only in RT mode, `refl.comp` water path in game, shadow-ray transmittance for stained
  glass (capped candidate loop), raster-after-compose pass ordering.
- Offline: rtReplay (e)/(f) on game classes; rtGameReplay water/glass capture.
- In game: R-water (water tank, stained-glass wall, rain).

### RT-7 [O] Max preset: path tracer
- Files: new `pt.comp` (or `gi.comp` multi-bounce mode), `water.glsl`/`medium.glsl` (translated), `restir.comp`
  (spatial bias root cause), `RtPreset.PATH_TRACED`.
- Do: cache-terminated multi-bounce paths with Russian roulette, sun + ReSTIR DI at path vertices, all-roughness
  specular, dielectric split for water/glass, hit-distance outputs, checkerboard half-rate option. Spatial ReSTIR is
  enabled only after its bias (-3..-5% hall/cave) is explained and fixed.
- Offline: furnace and Cornell scenes vs CPU reference (tolerances from the CPU-vs-CPU noise floor), bias <= 1% on
  hall/cave/night, PATH_TRACED sheets.
- In game: R-pt perf rung (timestamps, VRAM, frame-time histogram while flying).

### RT-8 [O] Own denoiser to NRD class
- Files: `SvgfDenoiser.java`, `tools/shaders/svgf_*.comp`, `NrdReplay`/`McReplay` (RQ-INT2-c: own golden checked
  without NRD).
- Do: penumbra-aware shadow kernel (blocker distance), specular virtual-motion reprojection with hit-distance guides,
  history scaled by frame rate, memory and barrier cuts (old P6-6). Papers only; NRD runs locally as the reference.
- Offline: own <= stock NRD RMSE on every scene including penumbra (0.195 -> <= 0.155), light switch <= 13 frames,
  memory not above today's 112 MiB set; rtGameReplay captures.
- In game: P6-5 noise / lag / ghosting captures.

### RT-10 [S] Sky LUTs for lighting
- Files: new `sky_lut_*.comp` (translated Hillaire LUTs, own hash), `RtLookModel.java`, `rt_look.glsl`, IrohSky hook
  for Tempest cloud shadows.
- Offline: LUT values vs a CPU integration; P5-2 calibration targets still pass; day/sunset/night sheets.
- In game: folded into the Phase 7 look rungs.

### RT-11 [S] Presets, tiers, auto-pick, UI
- Files: `RtPreset.java`, `RtSettings.java`, `IrohOptions` rows, `RtLookArbiter`, en_us keys.
- Do: Safe / Balanced / Max labels plus advanced rows (residency, bounces, reflection roughness, checkerboard),
  auto-pick rules (2.6), first-enable calibration, link to FSR-Q11 dynamic resolution.
- Offline: rtSeamTest, irohConfigTest, hw-matrix (no-RT profiles -> Navidson; RTX 2060 / RX 6600 -> Safe).
- In game: folded into the next rung.

### RT-14 [S] Pack RT compatibility sweep
- Rows: alexscaves (lightmap override -> an RtLook rule), Tempest (precipitation raster, cloud shadows), Vista far
  field in RT mode (raster past residency with the far-field blend), voxelparticles / wakes / visuality (raster
  composite), polaris chisel / continuity / terrain slabs (material ids on sub-quads), enhancedblockentities,
  elemore-bosses and GeckoLib/AzureLib bosses (entity path).
- Offline: pack captures (one pack-clone capture run) through rtGameReplay.
- In game: R4 pack memory, R6 soak (P5-PLAN 3c).

### RT-12 [O] RADV performance pass (spread over the R3 / R-pt results)
- BLAS flags hot/cold and compaction (measure the RADV ratio first), async compute queue for builds and the denoiser
  (with FSR-Q15), Wave32 vs Wave64 for ray-query compute (A/B through a per-process env var in the clone; orchestrator
  confirms that this is allowed under SYSTEM SAFETY rule 8), register pressure from shader stats, half-rate tiers.
- Acceptance: Gate 6 numbers; every change shows equal or better chain metrics on rtGameReplay.

### RT-13 [P] Correctness scenes and gates
- P5-7 scenes built by command (furnace, Cornell room, emissive colour wall, water tank, mirror hall, three dimensions,
  each citadel, boss arenas). Gate 5: instance-id coverage vs raster on the parity scenes. Gate 6: 1440p output,
  Balanced + own denoiser + FSR Quality, 60+ fps on the perf scenes, or a written gap account. Runs only in
  owner-away windows; stop at once when the owner is in a game.

### RT-9 [O/S] NRD optional backend (PARKED until Q1 is answered)
- If the owner's answer allows it: P6-1a/b/c/d of P6-PLAN (separate component with NVIDIA licence acceptance, in-game
  executor, input producers already shared, RELAX direct + REBLUR indirect + SIGMA). Never on the critical path.

## 5. Risks and the critical path

Critical path (Opus lane A): RT-2 -> RT-3b -> RT-7 -> RT-8 -> RT-12 -> Gate 6, with the in-game rungs R1/R2, R3,
R-pt between them. Lane B (Opus, second slot when the budget allows, else after lane A): RT-5. Sonnet lanes run
RT-0, RT-1, RT-3a, RT-4a/b/c, RT-6, RT-10, RT-11, RT-M, RT-14 beside them.

| risk | effect | mitigation |
|---|---|---|
| RADV ray throughput unknown (all ms figures are estimates from one Radiance datum) | Balanced may miss 14 ms | R3 timestamps in RT-3b, before the path tracer; presets with measured knobs; FSR-Q11 dynamic resolution |
| Device loss on long dispatches | lost rungs, stop-the-line | tiled dispatches, capped loops, NaN counter, R1 abort rules, `MESA_VK_ABORT_ON_DEVICE_LOSS=1` |
| BLAS bytes per triangle on RADV unknown (lavapipe 104 B, model 64 B) | residency radius smaller than planned | measure in the r1-rt-as rerun; far-field blend makes a small radius look right |
| NRD distribution legality | NRD cannot be the shipped denoiser | own denoiser is the default and already at parity offline; RT-8 closes the penumbra gap |
| Line divergence (P2-7 arena on int-2b vs RT on int-4c) | missing geometry after merge | RT-M right after RT-2 |
| G-buffer layout change (6-bit material id) | every RT consumer touched | done once in RT-4a, before the chain grows (RQ-RTPLAN-a) |
| Third-party entity renderers | no RT shadows/reflections for bosses | capturing VertexConsumer path in RT-5; pack rows in RT-14 |
| ReSTIR spatial bias | Max preset noisier than possible | stays off until explained (RT-7); temporal already beats 8-ray RIS |
| Owner availability for in-game rungs | calendar slips | rtGameReplay makes rungs confirmations, not iterations; batch rungs per owner-away window |
| Look sign-off (Gate 7, taste) | late rework | local sheets from captures to the owner early (Phase 7 side-by-side) |
| Mojang assets in captures or goldens | publishing Mojang art in a public repo | captures and their goldens stay in `~/.cache`; reuse_gate refuses them |
| Opus weekly budget | stalls on lane A | Sonnet-first routing; Opus only on the six cards above |

## 6. Time estimate

Per card sessions are in the section 4 table (one session = one agent run of about 3-5 hours). Totals: Opus 13.5
sessions (RT-2, RT-3b, RT-5, RT-7, RT-8, RT-12), Sonnet 12.5 sessions (RT-0, RT-1, RT-3a, RT-4a/b/c, RT-6, RT-10,
RT-11, RT-M, RT-14), plus about eight in-game rungs (R1/R2, R-mat, R3, R-ent, R-water, R-pt, R4/R6, Gate 5/6 runs).

Calendar (low-medium confidence; assumes one Opus lane from Nov 26, a second from the Nov 30 budget reset, two Sonnet
lanes, and an owner-away window every one to two days):

| window | cards | milestone |
|---|---|---|
| Nov 25-28 | RT-0, RT-1, RT-2 | first light: sun shadows in game (R2) |
| Nov 29 - Dec 5 | RT-3a, RT-4a, RT-3b, RT-M, RT-5 start | Balanced chain in game, timestamps (R3) |
| Dec 6-12 | RT-5, RT-4b, RT-4c, RT-6, RT-11 | Gate 5 (complete picture, coverage) |
| Dec 13-22 | RT-7, RT-8, RT-10, RT-14, RT-12 start | Max preset; Gate 6 (60 fps Balanced at 1440p) |
| Dec 23 - Jan 31 | RT-12 rest, Phase 7 P7-1..P7-6 | look sign-off (Gate 7) |

This matches the accelerated TIMELINE row for Phase 5 (Dec 15 - Jan 15) and leaves the full release target of early
February intact if Phase 7 does not need a second look round.

## 7. Questions for the owner (none blocks RT-0 .. RT-8)

1. **NRD distribution (legal).** NRD's licence (4(e)) forbids uses that would make it subject to open-source terms,
   including "redistributable at no charge". Shipping `libiroh_nrd` with or for a free LGPL mod is therefore uncertain
   (also because our LGPL shim is statically linked with it). Options: (a) own denoiser only (plan default);
   (b) NRD as an optional component the user builds locally with `build-nrd.sh`; (c) ask NVIDIA
   (nvidia-rtx-license-questions@nvidia.com) before hosting any binary. Not legal advice.
2. Auto-generated normal maps from albedo for blocks without LabPBR: off by default (proposed) or on?
3. May RT-12 A/B per-process RADV env vars (e.g. `RADV_PERFTEST=rtwave64`) inside guarded perf runs? They change only
   the test game's process, never system settings.

## Amendment 2026-11-24 (orchestrator): AMD FidelityFX 1.1.4 denoisers
FidelityFX SDK 1.1.4's Shadow Denoiser and Reflection Denoiser are MIT with a Vulkan backend (reference/ffx-1.1.4/sdk/src/components/denoiser, gpu/denoiser incl. *_glsl.h callbacks). Use them for the dominant-light shadow signal and the specular/reflection signal; Iroh's own denoiser stays for diffuse GI. The denoise cards (the RT-plan cards covering shadow/specular denoising) should: (1) port the two FFX denoisers through iroh-native or as GLSL compute in Iroh, with ledger rows; (2) A/B them against Iroh's own denoiser offline (golden images, temporal stability metric) and keep the better one per signal. FSR Ray Regeneration (SDK 2.4) is DX12/Windows-only closed binary: not usable.
- Owner addition 2026-11-25: Intel Open Image Denoise (OIDN, Apache-2.0) is a third candidate in the denoiser A/B (own vs FFX 1.1.4 vs OIDN), per signal: quality vs reference, temporal stability, GPU ms at 1440p on RX 9070 XT, Linux/Windows availability, runtime dependencies (HIP/CUDA/SYCL, Vulkan external-memory interop). Best per signal ships; OIDN is also a candidate for a screenshot/photo mode.
- Owner 2026-11-25: five user-facing levels replace safe/balanced/max: Off / GLSL (shader pack) / Low (RT shadows only) / Medium (RT shadows + reflections) / High (all RT: GI, shadows, reflections, AO, caustics, emissive, ...). Cards must deliver Low first (shadows), then Medium (reflections), then High (GI + AO + caustics); Low/Medium/High disabled with a reason on non-RT GPUs. Settings UI card: one "Rendering quality" selector with these five entries (see DECISIONS).
- Owner 2026-11-25: RT levels must look like Navidson and support all its features and mod integrations (NAV-PARITY inventory card -> acceptance checklist with Navidson-vs-RT screenshot pairs), plus an "Advanced Settings" screen for the Vulkan RT shader (per-effect controls, denoiser per signal, Navidson-style colour/exposure/fog/water options; edits switch the level to "Custom"). See DECISIONS.
