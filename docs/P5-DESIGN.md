# P5 design: Iroh's ray-traced lighting pipeline (card P5-0, 2026-11-22)

Status: built and checked OFFLINE on lavapipe (CPU Vulkan) with `./gradlew rtReplay` (49/49, CPU reference path
tracer for every pass), `rtSeamTest` (24/24) and `rtProbe` (4/4). Nothing here has run on a GPU or in the game.
Everything about real-GPU speed, RADV behaviour or VRAM is marked **expected, unverified**.

Sources: `knowledge/rt.md` (Caustica, vulkanite, Dirt-RT prose), `knowledge/rt-radiance.md` (Radiance digest;
section 6 comparison, section 7 first pipeline), `knowledge/navidson.md`, `knowledge/fsr.md` section A, card P6-0's
denoiser interface (branch `card/P6-0`, unmerged). GPL sources (Dirt-RT, Radiance) were used through the digests only.
Algorithms implemented from their papers: spherical rectangle sampling (Urena, Fajardo, King 2013), GGX VNDF sampling
(Heitz 2018), Smith height-correlated masking, Schlick Fresnel, exponential height fog (closed-form integral).
Nothing was copied from Caustica either (LGPL, allowed) in this card.

Provisional calls (the orchestrator's, the owner can change them): rasterised primary visibility; rays for lighting
only; stock NRD with a temporal fallback; FSR 3.1 after denoising; Vista far terrain raster-only behind the RT image in
v1; RX 9070 XT, 1440p output, ~1707x960 render, ~13.5 ms (rt-radiance.md section 7, UNVERIFIED).

**Provisional decisions (coordinator, 2026-11-22, owner asleep; reversible):**
- Entities in v1 receive RT light but cast no RT shadows and are absent from reflections ONLY as a stepping stone:
  entity shadows matter for the look. The entity BLAS is a P5 card (per-entity-type static BLAS with instance
  transforms, or a per-frame refit set for nearby entities), built offline first (synthetic moving box + quad "mob").
- RT residency radius 8 chunks with raster beyond; configurable, tied to the quality preset and the `iroh-rt` memory cap.
- Sun: default slightly soft (Navidson-like) as a preset parameter; physical 0.27 deg as an option.

## 1. Frame graph

```
 raster G-buffer (terrain + entities + hand; jittered)      [GBufferSource RASTER | PACK]
   -> AS maintenance (BLAS queue: <= N sections/frame, TLAS rebuild)
   -> sun/moon shadow       (1 ray, cone)                                   sun.comp
   -> block light           (RIS over emissive quads, 1 shadow ray)         emit.comp
   -> indirect diffuse      (1 ray; radiance cache or direct at the hit)    gi.comp  + cache_resolve.comp
   -> reflections           (roughness-gated; water mirror + transmission)  refl.comp
   -> denoise               (NRD RELAX/REBLUR; offline: temporal accumulation)  accum.comp
   -> compose + fog         (emission + albedo*diffuse + specular; Navidson fog AFTER denoise)  compose.comp
   -> FSR 3.1 upscale       (linear HDR, reactive mask from disocclusion)
   -> tone map, Navidson grade, bloom, then UI
```
Particles, weather, name tags, the block outline and the first-person hand's transparent bits are rasterised AFTER
compose (Radiance's split), depth-tested against the G-buffer depth.

## 2. Acceleration structures

**Geometry source: the raster vertex arena, read in place.** Iroh's 16-byte terrain vertex starts with three int16
positions (`x2048`, offset -4 blocks). The BLAS build reads them as `VK_FORMAT_R16G16B16A16_SNORM` (the 4th short, the
light value, is ignored by the build) and undoes the encoding with the geometry's 3x4 transform (scale 32767/2048,
offset +4). No decode pass and no second copy of positions; the 36-byte pack vertex has the same first 16 bytes, so it
works unchanged. Proven: `rtProbe` (one quad, all variants hit at the exact distance) and step (a) of rtReplay (the RT
primary ray hits every raster pixel of the scene, viewZ equal within 0.1% except 6 of 8260 edge pixels where the ray
and the rasteriser pick different faces of a shared edge). SNORM is a required AS vertex format, so RADV must support
it (expected, unverified until P5-1 queries it on the card; RtAccel.vertexFormatSupported checks at runtime, fallback
= a compute decode into R32G32B32 floats).

| item | v1 choice | why |
|---|---|---|
| granularity | one BLAS per 16^3 section (Caustica, Radiance, vulkanite agree) | matches the mesher's unit and the rebuild events |
| geometries | fixed order: 0 opaque (`OPAQUE` flag: no candidate work), 1 alpha-tested (non-opaque) | `rayQueryGetIntersectionGeometryIndexEXT` is a stable bucket id |
| translucent (water, stained glass) | separate BLAS per section, TLAS instance mask bit 2 | shadow and GI rays skip it by cull mask, not by candidate work |
| alpha test | manual, in the ray-query candidate loop (atlas texel at the barycentric UV, cutoff 0.5) | works on every RT device; any-hit with no SBT; proven by step (f) |
| opacity micromaps | NOT in v1 (optional accelerator later) | RADV support UNVERIFIED; leaves are the only big cutout cost |
| flags | `PREFER_FAST_TRACE + ALLOW_COMPACTION` | terrain is traced many times per build |
| compaction | compacted-size query + COMPACT copy after the build frame | works on lavapipe (but saved 0% there); RADV saving UNVERIFIED (measure in P5-1) |
| rebuild vs refit | terrain: always REBUILD on a section rebuild (discrete events); refit only for entities (P5-2) | a remesh changes topology |
| build queue | `BlasBuildQueue`: nearest first, 3D residency radius, per-frame caps (8 sections / 200k triangles start values), latest version wins, resets free, memory under owner `iroh-rt` (refusal = wait, never force) | tested in rtSeamTest |
| where built | v1: graphics queue at api-4 `BEFORE_MAIN_PASS`; next: a reserved compute queue + timeline semaphores (Caustica's executor) | start simple, measure, then overlap |
| TLAS | rebuilt every frame (instances <= ~20k: cheap); translation-only instances relative to a camera-near integer rebase origin; customIndex = section table index | Caustica; GPU-filled TLAS + refit from a persistent section table (Radiance) when instance counts grow |
| entities, block entities (v1) | NOT in the TLAS. They ARE in the raster G-buffer, so they RECEIVE RT sun shadows, block light and GI | visual consequence: mobs, players, chests, item frames cast no RT shadows and do not appear in reflections or bounce light; acceptable for the first in-game milestone; P5-2 adds capture + per-entity refit (Caustica) |
| far terrain (Vista) | raster only behind the RT image | rays reaching past the residency radius see sky (or the cache); Vista spans as BLAS tiles later (Radiance shows the idea) |

## 3. Ray query in compute, not ray tracing pipelines

Choice: **`VK_KHR_ray_query` from compute shaders for every pass.** Reasons: (1) portability: lavapipe and RADV both
support it, and RADV compiles RT pipelines into one big traversal shader anyway (no SER on RADV: rt.md), so the
pipeline API buys no speed there that we know of (expected, unverified: measure both on the card in P5-1 with one pass);
(2) the hybrid design is wavefront-shaped: raster primaries, one effect per pass, ray-queue compaction with indirect
dispatch later; compute does that natively; (3) no SBT and no per-pass hit groups (Radiance's cost centre); the alpha
test is a short candidate loop; (4) the same descriptor/pipeline model as api-4 compute (`RtCompute` adds only the
TLAS binding). The path-traced top preset (P5-4) stays a ray-query loop; switch only if a measured RADV comparison says so.

## 4. Direct lighting

**Sun / moon.** One shadow ray per pixel to a cone-sampled direction (uniform over the disc's solid angle; angular
radius from the look: real sun 0.27 deg; the test used 2 deg to show a penumbra at 160x90). Whichever of sun or moon
delivers more light is the NEE light (Caustica). Shadow rays: mask SOLID, alpha-tested, terminate on first hit. Water
does not block sunlight; stained glass will multiply a transmittance in the candidate loop (designed, not built).
Measured: hard shadows equal the CPU reference except 2 of 8182 pixels; soft converges to relMAE 0.003.

**Emissive blocks: light list built at mesh time.** Every emitted quad whose sprite is emissive becomes a rectangle
light (corner, two edges, outward normal, area, radiance = the sprite's mean emissive radiance so power is exact). In
game the mesher appends these per section (`SectionGeometry.emissiveQuads`); the per-frame list is concatenated with a
power CDF. **v1 sampling** (proven): resampled importance sampling (RIS) over M candidates (MEDIUM: 8), candidate light
by power, candidate point uniform in the quad's **solid angle** when the receiver is within ~3 quad sizes (Urena 2013
spherical rectangles) and by area farther out, target = unshadowed Le_lum x cos, ONE shadow ray to the survivor.
Two findings drove this (knowledge/rt-iroh.md): area sampling has a 1/d^2 tail for floors touching glowstone (pixels
ran 30-40% low at 256 frames); RIS cut the per-sample noise near lights from 0.56 to 0.20 relative std. Checked against
Lambert's exact polygon irradiance (13 configurations, all within noise) and a brute-force CPU estimator.
**Upgrade path to ReSTIR DI** (in this order, each measurable offline): (1) candidate source by locality: per-section
light grid (lights of the 3x3x3 neighbourhood) mixed with the global CDF (Caustica's grid + Radiance's neighbourhoods)
so the light touching a receiver is not picked 1 time in 15; (2) temporal reuse: one reservoir per pixel, reprojected
with the G-buffer motion and the same plane/normal tests as the denoiser history; (3) spatial reuse (2-4 neighbours);
(4) the existing single visibility ray stays. Held/dynamic lights (api `HeldLights`) are appended to the list per frame.

## 5. Indirect diffuse and the radiance cache (own design; no SHARC)

One cosine-distributed ray per pixel (half rate in LOW/MEDIUM). At the hit: if the cache is on, the hit is farther
than one cell (footprint test: near contacts stay exact), and the cell has >= 4 resolved samples, use the cell's
radiance; otherwise compute direct light there (sun ray + one RIS light sample) and feed the cell. Misses take the sky.
Output: irradiance + hit distance (NRD's hitDist).

| cache property | v1 |
|---|---|
| structure | open-addressing hash table, 8 linear probes, 12 uints per entry; key checksum by a second hash |
| key | floor((p - 0.01 n) / cell) + face axis (6 values) + LOD level; cell = 0.5 block, doubling every 16 blocks of distance |
| leak control | the axis term keeps the two sides of a wall and floor/wall corners apart; the footprint test keeps contact shadows exact |
| accumulation | integer atomics on fixed point (x256): no float atomics needed (RADV, lavapipe) |
| temporal | resolve pass per frame: running mean capped at 64 samples, then exponential (old light fades in ~64 frames) |
| size | test 2^16 entries (3 MiB; 7,900 used); game 2^20 (48 MiB) |
| not done | eviction by age (field stored); cache queries from reflection hits; multi-bounce (cells gathering from the cache = cheap infinite bounce, for HIGH / PATH_TRACED) |

Measured on the test scene: GI without cache relMAE 0.022 vs CPU (bias -0.1%); with cache relMAE 0.023, **bias
-0.09%**, the cache answered 82% of secondary hits, GI rays per pixel 0.76 -> 0.60 (-21%; open terrain where most GI
rays reach the sky; interiors save more: expected, unverified).

## 6. Reflections

Roughness-gated (preset: no specular ray above 0.15 / 0.30 / 0.40 roughness; water always). **Water (hero case):**
mirror reflection with Schlick Fresnel (F0 0.02) and the sun disc (the glint), plus one straight transmission ray to
the bed with Beer-Lambert extinction per channel (no refraction bend in v1; Caustica's dielectric split + wave normals
are the upgrade, LGPL-reusable). **Glossy blocks:** one GGX sample from the visible-normal distribution (Heitz 2018),
weight F x G2/G1. Hits are shaded direct-only (emission + sun + one light sample); next step: add the radiance cache
at reflection hits so reflected surfaces carry bounce light. Measured: water relMAE 0.002, glossy 0.025 vs a CPU
reference that samples the GGX NDF instead (independent estimator).

## 7. Sky, atmosphere and fog: what Navidson keeps

The RT core lights; Navidson keeps the look. Order:
- BEFORE the RT passes: Navidson's sky (palette, stars, moon phases) is evaluated into a small sky-view LUT / cube per
  frame that RT misses sample (Caustica uses Hillaire LUTs; Radiance a 128^2 cube): GI and reflections then bounce
  Navidson's sky colours. Tempest's clouds give a cloud-shadow mask for the sun ray (texture lookup, not traced).
- AFTER denoising, in compose: mountain fog (owner's `NvMountainFog`, licensed to Iroh under LGPL), distance fog,
  Tempest weather volumes; fog is never fed through the denoiser history (Radiance's rule; avoids smeared fog).
  rtReplay's compose applies an analytic exponential height fog as the stand-in and proves the order and the maths
  (GPU compose equals the CPU formula to 4e-6 on identical inputs).
- AFTER the upscaler: tone map, the elemental grade `NvElementGrade`, bloom / light-bloom halo, then UI.
Complementary's code and constants stay out (navidson.md section 1): only the owner's files are ported.

## 8. Materials (vanilla textures, no PBR pack)

Per-sprite material record (the G-buffer carries the sprite/material id; a table gives the rest):
- default: Lambert, roughness 1, F0 0.04; alpha-tested sprites (cutout layer) cut at 0.5;
- water: roughness 0, F0 0.02, transmission with extinction;
- smooth list (block tags + name rules: `polished_*`, `smooth_*`, glazed terracotta, glass, ice, metal blocks): roughness
  0.25-0.35; metal blocks F0 from albedo;
- emission: blocks with `getLightEmission() > 0`; their bright texels form the emissive mask (Caustica's heuristic,
  LGPL-reusable); radiance scaled from the light level; modded light blocks glow with no data;
- LabPBR when a resource pack provides `_s` / `_n`: roughness = (1 - smoothness)^2, F0 / metal ids, emission channel,
  normal + AO (Caustica's LabPBR compile, LGPL-reusable); per-block override file shipped as data.
**Trap (knowledge):** the raster mesher bakes vanilla's face shade and AO into the vertex colour; in RT mode the mesher
must write the biome tint alone (rtReplay's scene does), or every RT surface is double-shaded.

## 9. Temporal: motion, rates, denoiser inputs

- Motion vectors come from the raster G-buffer (previous - current, pixels; NRD wants uv: scale 1/size; FSR pixels).
  Camera-only for terrain; entities need previous-frame transforms (P5-2).
- Jitter: the upscaler's Halton sequence offsets the G-buffer projection; RT passes reconstruct surfaces from the
  G-buffer, so they follow the jitter without extra work.
- Half rate: checkerboard field `(x + y + frame) & 1` for GI, light and reflections in LOW/MEDIUM; NRD's checkerboard
  mode fills the other field (designed, not built: the offline harness runs full rate).
- Denoiser inputs (mapping to card P6-0's slots in `RtDenoiseStage`): viewZ, normal+roughness, motion, diffuse
  irradiance + GI hit distance (demodulated by albedo), specular + reflection hit distance. v1 sends the sun inside the
  diffuse signal; a SIGMA shadow signal is the upgrade if NRD's shadow quality is better. Offline stand-in: temporal
  accumulation with bilinear history taps, each rejected unless its previous viewZ matches the reprojected surface
  (2%) and normals agree (dot > 0.9). Proven in a scripted 30-frame move: on 508 freshly disoccluded pixels the bias is
  +1.6% with rejection vs -13.9% without (ghosting), and converged pixels stay at relMAE 0.06 while moving; noise falls
  as n^-0.49 with frame count on a static camera.

## 10. Budget per stage (RX 9070 XT, 1707x960, MEDIUM; ALL expected, unverified)

Ray throughput datum: Radiance's ~20 ms RT pass at 1080p native on a 9070 XT (old build). Assuming ~7 rays per pixel in
that pass (UNVERIFIED) gives ~0.7 G rays/s. Measured rays per pixel per pass on the test scene at FULL rate (rtReplay):
sun 0.52, block light 0.46, GI 0.76 (0.60 with cache), reflections 0.02 (scene-dependent: caves and interiors trace more).

| stage | MEDIUM rate | rays/px | ms (est.) |
|---|---|---|---|
| raster G-buffer (replaces the raster terrain pass) | full | 0 | 0.8 |
| AS maintenance (<= 8 BLAS + TLAS) | per frame | 0 | 0.7 |
| sun shadow | full | 0.52 | 1.2 |
| block light (RIS M=8, candidate cost incl.) | full | 0.46 | 1.6 |
| GI 1 bounce + cache | half | 0.30 | 1.5 |
| reflections | half | 0.01-0.2 | 0.5-1.0 |
| denoise (NRD RELAX/REBLUR) | | | 2.0 |
| compose + fog | | | 0.5 |
| FSR 3.1 | | | 0.5 |
| tone map, grade, bloom, UI | | | 1.0 |
| total | | ~1.4 | ~10-11 |
Rays alone at 1.4 rays/px x 1.64 Mpx = 2.3 M rays ~ 3.3 ms at 0.7 G rays/s; the table adds shading, candidate and
memory costs on top. The first real-GPU card must replace this column with timestamp measurements (P5-PLAN R2).

## 11. Quality presets (`RtPreset`)

| preset | sun | block light | GI | cache | reflections | render scale (FSR) |
|---|---|---|---|---|---|---|
| LOW | half rate | half, M=4 | half | on | roughness < 0.15, half | 2.0 (Performance) |
| MEDIUM | full | full, M=8 | half | on | < 0.30, half | 1.7 (Balanced) |
| HIGH | full | full, M=8 | full | on | < 0.40, full | 1.5 (Quality) |
| PATH_TRACED (P5-4) | full | M=16 + ReSTIR | multi-bounce (cache gather) | on | all roughness | 1.5 |
History cap 32 frames in all (scaled by frame rate later, Radiance). The look switch (`RtLook`) is separate from the
preset: NAVIDSON (default; every card) or RAY_TRACED (needs acceleration structures + ray query + buffer device
address; `RtSettings.active`). No RT extensions -> the RT core is never touched (`RtRuntime.INERT`, proven by
rtSeamTest: no listener, no memory, no device extensions, no class outside `dev.elemore.iroh.rt` references it).

## 12. Built vs designed

Built and checked offline: everything in sections 2 (except the compute queue, OMM, entities), 3, 4 (except locality
and ReSTIR reuse), 5 (except eviction, specular cache, multi-bounce), 6 (except refraction bend and wave normals), the
compose/fog order of 7 (with a stand-in fog), 9 (except checkerboard and NRD) and the seams.
Designed only: Navidson sky LUT for misses, Tempest cloud shadows, entity/block-entity BLAS, per-section light grid,
ReSTIR reuse, checkerboard, NRD + FSR hookup, LabPBR and the smooth-block rules, the game-side geometry adapter, the
compute-queue build executor.
