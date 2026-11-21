# Iroh outline

Read `~/.claude/skills/iroh/SKILL.md` and `DECISIONS.md` first. This file is the work plan: phases,
task cards, who does each card, and how each is proven. Status lives in `STATE.md`, not here.

Card line format: `ID [model] title` then what to do, **Owns** (files only this card edits while it
runs), **Reads**, **Accept** (the check that makes it DONE). Models: `S` Sonnet, `O` Opus,
`P` Sonnet running Proctor, `F` orchestrator decision (no code).

Cards inside a phase can run in parallel unless **After** says otherwise. Phase N+1 starts when the
phase N gate is green, except where a card is marked `early` (research that does not touch `main`).

## Architecture in one page

```
iroh (Fabric mod, Java 21 target, runs on the pack's Java)
  core/        VulkanMod's renderer, kept and tuned: device, memory, pipelines, chunk meshing, draw
  compat/      one package per third-party mod shim (mixins + adapters). Nothing here edits other jars.
  api/         small public API our own mods use (Vista, Tempest, elemore-core): submit geometry,
               register passes, query capabilities. Replaces what they asked Sodium / Iris / GL for.
  glshim/      the subset of GL / GlStateManager / RenderSystem behaviour third-party mods call,
               translated to Vulkan state. This is the main compatibility lever.
  shaderpack/  Iris-format pack loader: parse, GLSL -> SPIR-V, gbuffers / composite / final passes,
               uniforms, block and entity ids. Navidson runs here on non-RT cards.
  rt/          BLAS/TLAS build, material table, path tracer, hybrid passes, radiance cache
  post/        upscaler interface (FSR 3.1, FSR 4, DLSS SR/DLAA), denoiser interface (NRD, DLSS RR),
               frame generation, latency. One Java interface per kind; vendors are plug-ins.
  look/        the built-in Iroh look: Navidson palette, coloured light, mountain fog, Tempest, grade
  ui/          video settings (replaces Sodium's screen), per-feature capability report
iroh-native (C++, separate Gradle/CMake subproject, LGPL)   JNI: FSR open path, AS helpers
iroh-nv     (C++, separate, links NVIDIA SDKs unmodified)   JNI: NGX DLSS, NRD. Not LGPL-mixed.
```

Three render paths share `core/`: **Raster** (vanilla look, parity target), **Pack** (Iris shader
pack on raster), **RT** (path traced or hybrid, built-in look). Upscalers and frame generation sit
after all three. The path is chosen from device capabilities and the user's setting; non-RT
hardware defaults to Pack with Navidson.

Fork base (decided 2026-11-15): tag **0.5.5** of VulkanMod, the newest public 1.21.1 source.
Improvements from `dev` (1.21.11) and tags up to 0.6.6 are back-ported where they are not tied to
newer Minecraft internals (card P2-1). The Modrinth 0.6.7+1.21.1 jar may be read for behaviour but
its decompiled code is not copied unless matching source is published.

Mod id decision (2026-11-15, from the P0-3 ledger): Iroh stays `iroh` and does **not** declare
`provides: vulkanmod`. Reason: ImmediatelyFast and Resourcify declare `breaks: vulkanmod`, so
providing it would stop the game from loading, and their jars may not be edited. Their GL hooks
(listed in `pack-compat.md`) must instead be made harmless by `glshim/` and `compat/`. Two mods
carry a real VulkanMod code path gated on the id `vulkanmod`: AsyncParticles
(`VkCompParticleRenderer`, `RendererAddon`, calling `net.vulkanmod.vulkan.*`) and DreamDisplays
(`RenderBackendCompat.isVulkanModLoaded`). Iroh keeps the upstream `net.vulkanmod` package names
and API shapes those classes call, and `compat/asyncparticles`, `compat/dreamdisplays` turn their
gates on with a runtime mixin into the gate method (a mixin applied in memory is not a change to
their jar). Any class under `net.vulkanmod.vulkan.*` that those mods call is frozen API: changing
its signature needs a retro entry.

---

## Phase 0: groundwork and build

**P0-1 [S] Repository and build.** Create the git repo in `~/Projects/iroh` (reference/ stays
untracked via .gitignore). Import VulkanMod tag 0.5.5 with history, rename mod id to `iroh`, package
root `dev.elemore.iroh` only for new code (keep upstream packages to make back-ports mergeable),
`provides: ["vulkanmod"]` is NOT set (mods that `breaks vulkanmod` must not match). Add LICENSE
(LGPL-3.0-only), NOTICE, `build.sh` with `INSTALL=1` that copies the jar into the Vulkan instance
after the game-running check. **Owns** repo root, build files. **Accept** `./build.sh` produces a
jar; `knowledge/build.md` written.

**P0-2 [S] Proctor on the Vulkan instance.** Proctor hardcodes the `test` instance. Add
`PROCTOR_INSTANCE=<name>` (default unchanged), make the `iroh` clone launch without the Zink
override, find how screenshots and the MCP Fabric bridge behave when the renderer is not GL and fix
Proctor where needed. Create `scenarios/iroh/gate/boot.yaml` and `world.yaml`. Capture OpenGL
baselines now, before anything is changed: title screen, survival world at fixed seed/pos/time
(day, night, rain, cave, Nether, citadel, Outside of Time), inventory, each pack menu.
**Owns** `~/Projects/proctor/**`. **Accept** `PROCTOR_CLONE=iroh PROCTOR_INSTANCE=Vulkan proctor test
iroh/gate/boot` passes on the untouched instance; baselines stored; `knowledge/proctor.md` written.

**P0-3 [S] Compatibility ledger.** Turn GROUNDWORK section 5 and 6 into `knowledge/pack-compat.md`:
one row per at-risk jar (62) with: what it touches (Sodium API / Iris API / raw GL / mixin target),
exact classes, predicted failure, proposed shim, status UNKNOWN. For the hard blockers (sodium,
iris, sodium-extra, iris-extension, iris_search, cull-fewer-leaves, immediatelyfast, resourcify)
state what removing each loses and what Iroh must provide instead. List every mod that
`depends` on sodium or iris. **Accept** ledger complete; no row without a named class or file.

**P0-4 [S] Minecraft 1.21.1 render map.** From the 1.21.1 sources (Loom genSources, Yarn/Mojmap as
the base uses), write `knowledge/mc1211-render.md`: frame order (LevelRenderer / GameRenderer),
RenderType list and state, vertex formats, buffer upload, shader instances and uniforms, post
chain, framebuffers, texture atlases, light map, particles, block entity and entity paths, GUI.
Then a second section: how VulkanMod 0.5.5 replaces each (`knowledge/vulkanmod.md`), with file
paths. Third: what 26.3's native Vulkan renderer does differently that could be adopted (study
only). **Accept** both files exist; each claim cites a class.

**P0-5 [S] early. Reference digests.** One digest each, in `knowledge/`: `rt.md` (Caustica: AS
build, geometry extraction, materials, path tracer structure, NGX shim; vulkanite: how it hooked
Sodium chunk data for BLAS; Dirt-RT: techniques described in prose only, no code excerpts),
`iris-loader.md` (Vitrail: how it loads Iris packs and inserts upscalers; what parts of the Iris
pack format a loader must honour), `navidson.md` (feature inventory of `~/Projects/navidson`:
every option, pass, uniform, custom block id, Tempest and Vista hooks, the colour grade maths).
**Accept** three files; each lists "what transfers to 1.21.1 + VulkanMod" and "what does not".

**Gate 0** build works, Proctor boots the untouched Vulkan instance, baselines captured.

---

## Phase 1: the pack runs on Vulkan, identical behaviour

Order matters here: boot first, then world, then mod by mod.

**P1-1 [O] Boot.** Remove sodium, iris and their extension mods from the Vulkan instance (move to
`disabledmods/`, never delete), install Iroh, reach the title screen. Fix what stops boot inside
Iroh: metadata `breaks` (immediatelyfast, resourcify) are handled by Iroh's mod id being `iroh`;
mods that hard-`depend` on sodium/iris need a decision per mod, recorded in the ledger.
**Accept** gate `boot` green with validation layers.

**P1-2 [O] GL shim foundation.** `glshim/`: catalogue every GL / GlStateManager / RenderSystem call
made by the 38 direct-GL mods (bytecode scan, script in `tools/glscan.py`), group by kind (state,
framebuffer, texture, buffer, shader program, readback), and implement the kinds in order of how
many mods use them. Unknown calls log once with the calling mod and are counted. Findings from P1-1b that shape this card: the
base's `GL11M`/`GL15M`/`GL30M` overwrite LWJGL's `GLxx` classes, but mods that call the core
`GLxxC` classes bypass them, so move every shim to the `GLxxC` classes (as `GL11CM` does); shims
answer only when no real GL context is current (DreamDisplays and WaterMedia create their own
contexts and must keep the real driver); prefer `@Inject(HEAD, cancellable)` over `@Overwrite`
so other mods' injectors keep their targets; `tools/injector_scan.py --gl` lists callers. The
design-needed groups and their callers are in `pack-compat.md` section 0b. **After** P1-1.
**Accept** `tools/glscan.py` report shows the coverage percentage; world gate green.

P1-2 starts with two retro items found by R-1 (both READY): RQ-10 (layout transitions for render
targets sampled mid-frame are recorded into the upload command buffer, which runs before the
frame) and RQ-11 (off-screen target row order depends on how it was bound; adopt one top-down
convention). Prove each in game first, fix, then build the shim on top.

**P1-3 [S] Sodium-facing API stand-ins.** For mods that only call Sodium's public API or options
(18 referencing mods): provide clean-room stand-ins under the same names only where a mod would
crash without them, behaviour written from the mod's call sites. Never copy Sodium code.
**Accept** each of the 18 has a ledger row WORKS / SHIMMED / REPORTED.

**P1-4 [S] Iris-facing API stand-ins.** Same for `net.irisshaders.iris.api.*` callers (shadow pass
queries, shader-pack-in-use checks). Answers come from Iroh's real state. **Accept** as P1-3.

**P1-5 [S x3, split by ledger rows] Mod-by-mod compat.** One card per group, each with its own
`compat/<mod>` package and a `mods/<mod>` Proctor scenario:
- a. Entity rendering: entity_texture_features, entity_model_features, enhancedblockentities,
  GeckoLib/AzureLib users, satin.
- b. Maps and HUD: xaeroworldmap, xaerominimap, Insight billboard, Jade, controlify, FancyMenu
  (incl. video loop), watermedia, dreamdisplays, replaymod.
- c. World: continuity, terrain_slabs + terrain-slabs-ext, polaris, wakes, asyncparticles,
  supplementaries, Tempest, citadels blocks, cull-fewer-leaves replacement (built into Iroh).
**Accept** scenario per mod green; ledger updated.

**P1-6 [O] Feature parity with what Sodium / Iris / extensions gave the pack.** Video settings
screen with every option the pack relied on (list from P0-3), fog, leaves culling, dynamic lights
if used, Indium-style Fabric Rendering API support (FRAPI) complete, translucency sorting,
block entity and particle culling. **Accept** FRAPI test mod scenes match baselines.

**P1-6b [O design, S build] `api/` hooks our own mods need** (from S-2, 2026-11-16). elemore-core
is now safe without Sodium/Iris, but these features go quiet on Iroh until Iroh offers a hook:
(1) `isShaderPackInUse()`, `getCurrentPackName()`, `setShadersEnabledAndApply(boolean)` for
ShaderGuard (it is simply off under Iroh until then; in Phase 3 it must again enforce Navidson on
the Pack path, and must not block the RT path); (2) held-light override so a trinket-slot lantern
lights the world (TrinketLanternHeldLightMixin); (3) face-cull veto in the mesher
`(state, view, pos, face) -> boolean` plus a leaves-quality getter (LeafCull; also replaces Cull
Fewer Leaves, `randomRejection 20`); (4) `isSectionVisible` / `isBoxVisible` on the chunk renderer
(Clavis padlock culling); (5) `isRenderingShadowPass()` (item/XP-orb skip); (6) cloud veto:
confirm `LevelRenderer.renderClouds` HEAD-cancel still works on Iroh (Outside of Time has no
clouds). The Iris buffer-sweep throttle needs nothing unless Iroh adds batched entity buffers.
Then elemore-core gets an `IrohBridge` beside `SodiumBridge`. **Accept** each feature has a
Proctor scenario that passes on OpenGL (frozen clone) and on Iroh.

**P1-13 [S audit, O/S ports] Owner mods native on Iroh (owner rule 2026-11-20).** No owner mod may
reach the screen through the GL shim or a Sodium/Iris stand-in. Audit every owner mod (static scan
for org.lwjgl.opengl / GlStateManager / RenderSystem raw state / BufferUploader / custom
ShaderInstance / RenderTarget / Sodium / Iris use, plus the `Iroh GL shim` call log by mod from a
pack run) and port each rendering path to `dev.elemore.iroh.api`, extending the api where needed
(immediate geometry submission, custom pipelines from GLSL/SPIR-V, off-screen targets, compute,
per-frame uniforms, texture upload). Vista's renderer is the big one and stays in Phase 9 as a
native Vulkan renderer, not a GL translation; Tempest clouds/funnel, elemore-core HUD/world
overlays, Polaris, citadels, jumper, grind, bosses and pad effects are Phase 1 if they draw in
Raster mode. The OpenGL path of each mod stays for the owner's real pack. **Accept** the shim log
shows zero calls from owner mods; each ported feature has a Proctor scene matching OpenGL.

**P1-7 [P] Parity sweep.** Run every baseline scene and every `mods/*` scenario; file one td bug
per difference with the two screenshots; loop with P1-5 owners until clean. Also the full existing
Proctor smoke suite (bosses, cooking, menus, character creation) to prove gameplay did not change.
**Accept** all parity scenes inside tolerance; smoke suite same pass list as on OpenGL.

**P1-8 [P] Vanilla profile.** Minecraft 1.21.1 + Fabric API + Iroh only. Boot, world, parity
against vanilla OpenGL. **Accept** green.

**Gate 1** boot, world, parity/*, mods/*, smoke suite, vanilla profile: all green, no validation
errors. Retro triage.

---

## Phase 2: performance of the core

**P2-0 [P] Perf scenes.** Fixed camera paths: citadel fly-through, dense forest, 32-chunk vista,
cave, boss fight with particles, storm. Record avg, 1% low, frame-time histogram, GPU and CPU time
on OpenGL+Sodium (from the baseline clone) and on Iroh. `knowledge/perf.md`.

**P2-1 [O] Back-port upstream improvements.** Diff tag 0.5.5 against 0.6.6 / dev; list each renderer
improvement not bound to newer Minecraft; port in separate commits. **Accept** list in
`knowledge/vulkanmod.md` with ported / skipped + reason; gates green.

**P2-2 [O] Modern Vulkan.** Target Vulkan 1.3 core where available (dynamic rendering,
synchronization2, descriptor indexing / buffer for bindless textures), multi-draw indirect with GPU
culling for terrain, persistent mapped staging, pipeline cache on disk, async pipeline compile
without hitching, VMA budget handling, frame pacing. 1.2 fallback stays. **Accept** perf scenes at
or above Sodium on every scene; no 1% low regression.

**P2-3 [S] Threading and memory.** Chunk meshing scheduler co-operating with Vista's Sched and
elemore ThreadTuner (see memory note on Java priorities being no-ops on Linux), upload budget per
frame, leak check over a 30 minute soak. **Accept** soak shows flat native memory.

**Gate 2** perf scenes: Iroh raster >= OpenGL+Sodium on all, recorded. Phase 1 gates still green.

---

## Phase 3: Iris shader packs on Vulkan (Navidson first)

**P3-0 [F] Design note.** From `iris-loader.md`: choose the GLSL-to-SPIR-V route (glslang /
shaderc via LWJGL, with a GLSL 1.x-4.x compatibility pre-pass), how Iris uniforms, samplers,
`colortex`/`depthtex`/`shadowtex`, `block.properties`, `shaders.properties`, dimension folders and
custom uniforms map onto Vulkan passes. Written into this file under P3.

**P3-1 [O] Pack front end.** Parse a pack (zip or folder), options and profiles, preprocess
includes and defines, translate each program to SPIR-V with reflection. **Accept** Navidson and
plain Complementary Unbound compile with zero errors; report lists every unsupported construct.

**P3-2 [O] Pass graph.** shadow, gbuffers_*, deferred, composite, final; render target formats and
flipping; mipmaps; clear rules; compute programs if the pack uses them. **After** P3-1.

**P3-3 [S] Uniforms and ids.** Full Iris uniform set (camera, time, world, player state, biome,
fog, weather), custom uniform expressions, block / item / entity id attributes, `at_tangent`,
`mc_midTexCoord`, per-vertex AO. Table in `iris-loader.md` with status per uniform.

**P3-4 [O] Navidson correctness.** Coloured lighting (voxel light volume), mountain fog, Tempest
cloud march and funnel, Vista hook. Our shader may be edited if a construct cannot be expressed,
but only as a last resort and recorded. **Accept** Navidson on Iroh vs Navidson on Iris baselines:
perceptual tolerance set by the orchestrator after seeing the first diff.

**P3-5 [S] Pack UI.** Shader pack list and options screen (clean-room, behaviour like Iris), key
binds, hot reload. **Accept** scenario toggles packs and options without restart.

**P3-6 [P] Pack zoo.** Complementary Unbound/Reimagined, BSL, Sildur's, Photon, Bliss, MakeUp:
compile + render + screenshot. Failures become td bugs; fix the loader, never the pack.
**Accept** Navidson and at least four others render correctly; the rest have filed bugs.

**Gate 3** Navidson on Vulkan matches, non-RT default path works end to end, earlier gates green.

---

## Phase 4: upscaling (raster, pack and later RT)

**P4-0 [S] early. Settle FSR 4 on Linux by test.** Steps in `knowledge/fsr.md`. Report evidence.
No Wine bridge without the owner's go-ahead.

**P4 design constraint (from P0-5).** Vitrail has no temporal upscaler: only FSR 1 after the whole
pack chain, and it takes no jitter or motion vectors from packs. So "any Iris pack with FSR/DLSS"
is new design work, in three tiers: (1) packs that declare a cut point and provide motion vectors
through an Iroh pack directive (`iroh.upscale.after=<program>` in shaders.properties, ignored by
Iris): Navidson gets this, cut between composite5 and composite6; (2) undeclared packs: Iroh
renders gbuffers at reduced resolution with its own jitter and its own motion vectors (from
geometry, independent of the pack), finds the last program that still works in scene-linear space
by a heuristic (first program writing the final colortex without reading it back at full res,
overridable per pack in a shipped table), and upscales there; (3) packs where that fails the
ghosting check: spatial upscale after `final` (FSR 1 / RCAS) with the reason shown in the UI.
P4-1 implements tier 1 and the interface; a later card P4-8 [O] does tier 2 and 3 with the pack zoo.

**P4-1 [O] Upscaler interface.** Internal resolution scaling, jitter sequence, motion vectors
(terrain, entities, block entities, hand, particles; pack-provided vectors when a shader pack
writes them), depth, exposure, reactive / transparency masks, HUD drawn after upscale at native
resolution, mip bias. One interface, quality modes Native AA / Quality / Balanced / Performance /
Ultra Performance / custom scale. **Accept** debug views for every input; a null upscaler
(bilinear) path matches parity when scale is 1.0.

**P4-2 [O] FSR 3.1.6 native.** Build AMD's open VK backend for Linux and Windows in `iroh-native`
(MIT, attribution in NOTICE). Works on any vendor. **Accept** perf scenes: recorded gain at
Quality; no ghosting on hand, entities, particles in the review shots.

**P4-3 [O] FSR 4.1.2.** Through AMD's signed loader on Windows only (owner decision 2026-11-15);
Linux reports "FSR 4: not available from AMD on Linux yet" in the capability screen and uses 3.1.
Runtime download + licence prompt (card P4-6). Capability detection, automatic fallback to 3.1.

**P4-4 [O] DLSS SR + DLAA.** NGX direct on Vulkan in `iroh-nv` (Linux and Windows), presets, model
selection. UNTESTED label until an RTX owner runs the test build; ship a self-test command that
prints a report they can paste.

**P4-5 [S] Other-vendor path.** XeSS is not requested; do not add. Intel/other cards use FSR 3.1.

**P4-6 [S] Vendor binary fetcher.** Licence text shown, hash-pinned downloads from the vendor's
own URLs, manual folder fallback `config/iroh/vendor/`, offline behaviour, never bundled.

**P4-7 [P] Upscaler review.** Still + motion captures of each mode on raster, Navidson, and later
RT; shimmer and ghost checklist. **Accept** owner-facing comparison page generated (taste call).

**Gate 4** upscaling works on Raster and Pack paths with FSR 3.1 on Linux; FSR 4 status proven
either way; DLSS built and self-test ready.

---

## Phase 5: ray tracing core

**P5-0 [F] Design note** from `rt.md`: geometry sources (chunk sections -> BLAS per section, entities
and block entities -> dynamic BLAS, refit vs rebuild budget), TLAS per frame, vertex/material fetch
(bindless), ray query vs ray tracing pipeline (pipeline for path tracing, ray query for hybrid),
light sampling for emissive blocks (ReSTIR DI is allowed if clean-room or from permissive source),
sky model, water and glass, transparency and alpha-tested leaves via any-hit, portals, held items,
first-person hand. Capability tiers.

**P5-1 [O] Acceleration structures.** BLAS build from Iroh's own chunk mesher output on worker
threads, compaction, streaming with render distance, memory budget, TLAS update. **Accept** debug
view (primary rays, normals, instance ids) matches raster geometry on parity scenes.

**P5-2 [O] Dynamic geometry.** Entities, block entities, particles (billboards as raster composite
or RT quads), GeckoLib/AzureLib models, EMF models, hand and held items. Captured from the same
vertex streams the raster path consumes so every modded renderer is included without per-mod work.

**P5-3 [O] Materials.** Atlas-wide auto PBR (roughness, metalness, emission, normal from albedo
heuristics + block tags + a rules file), LabPBR 1.3 `_n` / `_s` when present, emissive detection
for modded light blocks from light level + texture, per-block override table shipped as data.
`knowledge/materials.md`.

**P5-4 [O] Path tracer.** Primary + N bounce, next-event estimation for sun/moon/sky and emissive
blocks, coloured light falls out of emissive sampling, water (refraction, caustics approximation),
glass and stained glass tint, volumetrics hook for fog. Outputs: radiance, albedo, normals,
roughness, depth, motion, hit distance (denoiser guides).

**P5-5 [O] Hybrid preset.** Raster gbuffer + ray-query shadows, reflections, one-bounce GI. Shares
materials and lights with P5-4.

**P5-6 [S] Fallbacks and tiers.** Capability report screen; automatic choice: Path traced, Hybrid,
Pack (Navidson), Raster. No RT extension -> never touches RT code paths.

**P5-7 [P] RT correctness scenes.** Furnace test, Cornell-style test room built by command,
emissive colour test wall, water tank, mirror hall, every dimension, every citadel, boss arenas.

**Gate 5** path traced and hybrid presets render the parity scenes without missing geometry
(instance-id coverage check against raster) at any frame rate. Earlier gates green.

---

## Phase 6: denoising and radiance cache

**P6-1 [O] NRD.** ReBLUR / ReLAX for diffuse + specular, SIGMA for sun shadows, in `iroh-nv`
(unmodified NRD, runtime library). All vendors. **P6-2 [O] DLSS Ray Reconstruction** via NGX
(replaces NRD + SR on RTX when chosen; UNTESTED label). **P6-3 [O] Radiance cache** of our own
(world-space hash grid or probe volume; clean-room), feeding secondary bounces. **P6-4 [S]** hook
point reserved for AMD Ray Regeneration if a Vulkan build appears (standing retro trigger).
**P6-5 [P]** noise / lag / ghosting review captures; perf.

**Gate 6** 1440p output on the 9070 XT, path traced + NRD + FSR Quality: 60+ fps on the perf
scenes, or a written account of the gap and the plan (retro entries).

---

## Phase 7: the Iroh look (Navidson in RT)

Licence fence for this phase: nothing from Complementary Unbound (Navidson's base) may enter Iroh.
The owner licensed the owner-written Navidson files to Iroh under LGPL on 2026-11-15 (scope in
DECISIONS.md): port those directly with a NOTICE line. Anything in a modified Complementary base
file, or of unclear origin, is written fresh and matched by eye against screenshots.
Corrections from P0-5: Tempest cloud base and `CLOUD_ALT1` default are 720, not 575; mountain fog
has no Vista gate in `mainFog.glsl` (confirm in game).

**P7-1 [O] Colour grade and tone map** matching Navidson (see the licence fence above). **P7-2 [O] Mountain fog and
atmosphere** as volumetrics in the RT path. **P7-3 [O] Tempest**: cloud layer at 575, storms,
tornado funnel, lightning as lights, wet surfaces, via `api/` so Tempest stays a separate mod.
**P7-4 [S] Coloured lighting tuning**: per-block emission colours match Navidson's table.
**P7-5 [S] Theme pass**: citadel element palettes, Outside of Time, Nether, End, deep caves.
**P7-6 [P] Side-by-side page** Navidson vs Iroh RT for the owner (taste call; stop and ask).

**Gate 7** owner signs off on the look.

---

## Phase 8: frame generation and latency

**P8-1 [O] FSR frame generation 3.1.6** (open, native) and **4.0.2** (signed, per P4-0 outcome),
with the FG swapchain, HUD-less colour + UI composition. **P8-2 [O] DLSS Frame Generation** (and
MFG only if the Linux SDK exposes it; never DLSS 5). **P8-3 [S] Anti-Lag 2** (Vulkan extension
`VK_AMD_anti_lag`) and **Reflex** (`VK_NV_low_latency2`). **P8-4 [P]** latency and pacing
measurement, combat feel check with lock-on and rolls. Off by default.

---

## Phase 9: Vista

**P9-1 [O] Vista on Iroh `api/`** (Vista is ours: edit it). LOD draw in Raster and Pack paths at
parity with today. **P9-2 [O] Far terrain in RT**: coarse BLAS or raster-composite with RT
lighting approximations. **P9-3 [P]** Vista perf bench (`vperf` recipe) on both renderers.

---

## Phase 10: release

**P10-1 [P] Full regression**: every gate, the whole Proctor suite, vanilla profile, a 2 hour
unattended playthrough segment. **P10-2 [S] Windows build check** (compiles, native libs build via
cross toolchain or CI; untested at runtime, stated in README). **P10-3 [S] Packaging**: README,
NOTICE audit against every copied file, licence headers, GitHub repo, Modrinth draft (owner
publishes), pack `SESSION_CHANGES.md` entry. **P10-5 [O] Navidson for Vulkan (the final pass; owner request 2026-11-20).** Optimise the
owner's Navidson pack for Vulkan and for Iroh's shader-pack pipeline, on the owner-written files
plus an Iroh-specific profile: measure each pass on the 9070 XT (per-pass GPU time from Iroh's
profiler), then cut cost without changing the look: merge or drop passes, right-size render target
formats and half-resolution passes, move the voxel light volume update to a compute path Iroh
schedules well, replace work the pack does itself with data Iroh can supply natively (motion
vectors, previous-frame matrices, depth pyramids, the Vista far-terrain contract, held-light and
Tempest textures), remove GL-era workarounds that cost on Vulkan (dependent texture reads,
branch-heavy fallbacks, redundant clears and copies), and wire the upscaler cut point. Complementary
base files stay under their own licence: changes there ship only as part of Navidson, never in
Iroh. **Accept** same look by the perceptual gate against the pre-optimisation Navidson-on-Iroh
shots, measured frame-time gain recorded per change, no new validation messages.

**P10-4 [F] Owner walkthrough**: what is tested, on
what hardware, what is not.

---

## Retro cards

`R-<n>` cards are appended here by the orchestrator from `knowledge/retro-queue.md`.

**R-1 [O] Own scene target (RQ-6).** After gate `boot` is green on the unmodified base and before
P1-2. Main render target becomes Iroh's own colour + depth images (size independent of the
window, format selectable, RGBA8 at native size by default so Raster parity is unchanged), with one
blit to the swapchain at the end of the frame after GUI. `Minecraft.getMainRenderTarget()` users
(mods binding, reading, blitting or resizing it) keep working: list them from `pack-compat.md` and
test each. Screenshots read from this target. Leave hooks, unused for now, for render scale, a
float format and extra attachments. **Reads** `vulkanmod.md` sections on framebuffers and section
11, `mc1211-render.md` RenderTarget section. **Owns** `vulkan/framebuffer/**`, `vulkan/pass/**`,
`mixin/**/MainTarget*`. **Accept** gates boot + world green, parity scenes inside tolerance,
screenshot works, window resize and fullscreen toggle scenario passes, no validation errors.

**R-2 [S] Base bug fixes found by P0-4.** Confirm each by reading the code and the Vulkan spec,
fix the confirmed ones in separate commits, leave the rest documented: (1) `ImageUtil.generateMipmaps`
passing usage bits where layouts are expected; (2) swapchain images created without TRANSFER_SRC
while screenshots / blits copy from them; (3) EXCLUSIVE-sharing buffers used on both transfer and
graphics queues without ownership transfer; (4) pipeline cache never saved to disk; (5) no SPIR-V
cache for converted shaders (add an on-disk cache keyed by source hash). Not in scope: the
spherical-vs-cylinder terrain fog and other parity gaps (those are P1 work). **Owns** only the
files each fix touches; no refactors. **Accept** `./build.sh` passes; each fix has a line in
`vulkanmod.md` weak-spots list marked FIXED(commit) or NOT A BUG(reason). In-game proof comes
with the first Iroh boot gate.

## Standing jobs

- **SJ-1 [P] nightly gate run** when any card merged that day; red gate -> retro entry + td bug.
- **SJ-2 [S] knowledge compaction** when a topic file passes 300 lines.
- **SJ-3 [S] upstream watch** at each phase boundary: VulkanMod, FSR SDK, DLSS SDK, NRD, Mesa
  release notes; anything relevant -> retro entry.
