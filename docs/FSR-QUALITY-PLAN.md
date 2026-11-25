# FSR 3.1 quality plan (cards FSR-Q1 .. FSR-Q17)

Written 2026-11-24 by card FSR-PLAN (Sonnet). Owner goal (DECISIONS "FSR 3.1 quality first; DLSS and FSR 4 parked"): make FSR 3.1 sharper and more accurate, use upscaler 3.1.6 and frame generation 3.1.7, ship the owner's option list, and if the quality target is met remove DLSS and FSR 4.

Inputs: research relay `docs/research/app-fsr31-vulkan-2026-11-24.md`; verification and the V / U / X table in `~/.claude/skills/iroh/knowledge/fsr.md` section G; code read on `integration/int-4c` (`~/Projects/iroh-wt-p41`, read-only). Everything in game is UNVERIFIED until a Proctor run; every number below marked U is a hypothesis an offline or screenshot test decides.

## 1. Where Iroh stands (facts)

| area | today (file) | verdict |
|---|---|---|
| Upscaler build | FSR 3.1.4 host + 3.1.6 shader back-ports from SDK 1.1.4 (`iroh-native/fsr`, patch in `build-fsr`) | compute behaviour already 3.1.6 (only change 3.1.4->3.1.6 in shaders is the FP16 reproject `PreviousFrameUpscaleSize`); version string and host deltas stale |
| Depth | STANDARD-Z, near 0.05, JOML infinite far, D24 (`Vulkan.use24BitsDepthFormat=true`) or D32F; FSR flag DEPTH_INFINITE only (`FsrAdapter.java:83`) | worst case for FSR depth: 0.078 / 0.31 / 1.25 blocks of depth step at 256 / 512 / 1024 blocks. D24 -> D32F alone gains nothing. Reversed-Z gives about 4000x finer. HIGH value, HIGH cost |
| Jitter | Halton(2,3), phase = trunc(8 r^2) (matches SDK code; docs say ceil), projection offset +2j/W, +2j/H (`JitterSequence`, `TemporalMath`), proven on a synthetic scene (A.2b) | correct; unproven in the real renderer |
| Motion vectors | one compute pass `velocity.comp`: camera reprojection from depth + up to 64 moving objects + hand; RG16F UV units, `motionVectorScale = renderSize`, unjittered | scale and sign right (B.1). Missing: vertex animation (waving leaves, water), block-entity motion (chests, pistons), particles |
| Masks | reactive R8 = min(0.9, 4 x |final - opaque copy|) (halved behind translucent), T&C R8 = 0.8 translucent, 0.3 objects/hand (`VelocityPass`) | heuristic, colour-difference. No SDK auto helper, no material-aware flags, no animated-atlas T&C (lava, portal, fire are opaque so get none) |
| Exposure | AUTO_EXPOSURE, LDR RGBA8 gamma-space colour, preExposure 1 | FSR 3.1 wants linear colour; benefit of feeding linear U |
| frameTimeDelta | ms, clamp 1..100 (`TemporalFrame.clampFrameTime`) | OK |
| Resets | first frame, size/mode change, adapter, level change, camera jump > 64, camera type, screen open/close, dimension (H1C) | OK; rotation-only cut missing |
| Mip bias | auto = log2(render/display) - 1 on EVERY sampler in the world phase (`Pipeline.java:414,501`), Native AA = -1.0 | likely too negative for 16x16 atlases (U), no per-material clamp, no slider |
| RCAS | wired: `iroh.upscale.sharpness` / `Config.upscaleSharpness` 0..100, default 0 | works, default off; no tuning |
| Hand / HUD / post | world phase at render size (hand included, merged depth + hand MVs), upscale at `endWorld`, then GUI at display size; outline/entity-glow chains at render size | order is right; hand-pre-FSR vs post-FSR undecided (U) |
| Debug | `FsrUpscaler.DEBUG_CHECKING` constant exists, never set; `UpscaleInputs.debugView` exists, never set; `drainMessages()` unread; own debug views velocity/reactive/depth/jitter exist (`iroh.debug.view`) | cheap to finish |
| Pack path | upscaler OFF while the Navidson pack path is on (INT-2 `packWins`, card P4-8 pending) | out of scope here, noted |
| FG | FSR frame interpolation 3.1.3 from 1.1.4 (`FsrFrameGeneration`), no swapchain proxy | swapchain 3.1.7 component not used; core 3.1.6 audit pending |
| Configure keys | `ffxFsr3UpscalerSetConstant` not exposed by the shim | missing: the 5 tuning keys |

## 2. Gap analysis per owner option and per research recommendation

"Exists" names the code today; "Fix" is the card that closes it.

| option / recommendation | exists | missing or wrong | fix (card) |
|---|---|---|---|
| RCAS sharpening | slider 0..100 (`IrohOptions.UPSCALE_SHARPNESS`), default 0 | default off; no ringing check; pack CAS not auto-disabled | Q4 |
| FXAA | nothing (only the pack macro `MC_FXAA_LEVEL=0`) | an FXAA pass for upscaler-OFF / pack-off display path | Q12 |
| Custom resolution slider | `ScaleMode.CUSTOM` (ratio 1..4, `iroh.upscale.scale`), switch only | no UI row; Quality enum has only the 5 presets | Q10 |
| Dynamic resolution bounds | nothing; FSR flag constant `DYNAMIC_RESOLUTION` unused; `FsrSession` recreates the context on every render-size change | needs max-size images + viewport sub-rect + no reset on size change, min/max scale | Q11 (Opus) |
| Target FPS + thresholds | nothing | controller (target fps, up/down thresholds, hysteresis, step) feeding Q11 | Q11 |
| Async compute toggle | `ComputeQueue` class exists, unused by FSR | FSR dispatch on the compute queue with timeline semaphores | Q15 (Opus) |
| Reactive mask: Auto-Generated | colour-diff compute of our own (not the SDK helper) | SDK `ffxFsr3UpscalerContextGenerateReactiveMask` mode via the shim | Q6 |
| Reactive mask: Voxel-Aware Custom | none | per-layer flags (translucent, tripwire, weather, particles, animated atlas sprites -> T&C) | Q7 (Opus) |
| Swapchain present mode | `vsync` bool -> FIFO or IMMEDIATE/MAILBOX (`SwapChain.getPresentMode`) | explicit FIFO / FIFO_RELAXED / MAILBOX / IMMEDIATE option | Q13 |
| Motion vector generation method | camera + objects + hand compute (always) | option + vertex-animation term + block entities + particles; camera-only fallback | Q8 (Opus) |
| Chunk build + upload pacing | `chunkBuildDeferMode`, `builderThreads`, P2 upload ring | per-frame build/upload budget as an option, tied to FSR frame cost | Q14 |
| Mipmap LOD bias sync | auto bias from render/display, all world samplers | follow dynamic render size, slider, cutout clamp, Native AA default | Q5 |
| Reversed-Z D32F | none | whole depth convention (see Q9/Q10 below) | Q9 (spike), Q10 |
| Per-entity motion vectors | yes (ObjectMotion, 64 objects, 2-frame eviction) | limbs/heads only via T&C; > 64 objects | Q8 |
| Hand after FSR | hand pre-FSR with merged depth + hand MVs | undecided | Q8 swing test decides |
| Debug checker / debug view | constants only | wire both, log messages | Q2 |
| `SetConstant` keys | none | 5 keys + defaults for voxels | Q1 (native), Q3 (defaults) |
| FP16 | gated (`DeviceCaps.fp16ShadersAllowed`) | fine | none |
| Memory aliasing | none (6.3 MB @320x180, 63 MB @720p->1080p) | low value | not scheduled |
| Linear colour feed | LDR gamma RGBA8, AUTO_EXPOSURE | possible win, U | Q16 (experiment) |
| FG 3.1.7 | interpolation 3.1.3 | core 3.1.6 audit; swapchain 3.1.7 has no Linux proxy path | Q17 |

## 3. Cards, in execution order (gain per effort)

Routing: Sonnet by default (`iroh-sonnet`); Opus (`iroh-opus`) only where the work is render-pass or queue internals. Every card commits on `card/<id>`, runs `./gradlew upscaleDefaultOffTest temporalMathTest upscaleTest fsrAdapterReplay fsrReplay` plus `python3 -I tools/injector_scan.py`, and ends with the report block. No launches by implementers: each "PROCTOR" item is a request in NEXT for an `iroh-proctor` run (one guarded game, 60 fps, shaders off, per `knowledge/proctor.md`).

Quality target (gates card Q17 removal; owner signs off): (a) static-edge test at Native AA converges within 8 frames and an offline edge RMSE within 15% of an 8x supersampled reference; (b) mob sprint and strafe: no visible ghost trail behind a moving entity after 4 frames, no fence/leaf sparkle; (c) weather, water, lava, portals free of smear; (d) hand swing without halo; (e) debug checker prints zero warnings; (f) screenshot pairs (OFF vs Native AA, Quality vs native) judged by the owner.

### Q1 [S] SDK upgrade: upscaler 3.1.6 parity and native knobs (FIRST; unblocks Q3, Q6)
Why first: the compute side is already 3.1.6, so this is the cheap, safe upgrade; it also adds the native hooks every later card needs.
- Files: `iroh-native/fsr/**` (patch, `gen_permutations.py`, `src/iroh_fsr_jni.cpp`, `fetch-sdk.sh`), `NOTICE`, `THIRD_PARTY.md`, `FsrNative.java`, `FsrDispatchStruct.java`.
- Do: (1) script the 3.1.4 -> 3.1.6 diff (SDK `ffx-1.1.4` vs `ffx-2.4.0` `gpu/fsr3upscaler/*.h`, expected: reproject only) and fail the build if another shader line differs; fetch the 2.4.0 source by commit `7539ae8b6568ad29ed78a753404351daf3b5cca7` + sha256 (MIT files only, never `signedbin/`); (2) port the host deltas that matter (exposure warning logic, auto-exposure/exposure consistency checks) and report version 3.1.6 from `FsrNative.version()`; (3) expose `ffxFsr3UpscalerSetConstant` for the 5 keys (V G.2) as `FsrNative.setConstant(ctx, key, float)`; (4) pass `DEBUG_CHECKING` through create flags and route the FFX message callback to `drainMessages()`; (5) add the dispatch debug flag plumbing (struct already carries it); (6) NOTICE entry for the 2.4.0 MIT files.
- Acceptance: `./iroh-native/build-fsr.sh` Linux + `build-windows.sh` OK; `fsrReplay` unchanged RMSE (0.0478 +- 0.0005 synthetic) with 32-bit and 16-bit permutations; new `fsrConstantsTest` (set each key, dispatch, values clamp per header, no error); shader-diff script prints exactly the reproject line.

### Q2 [S] Diagnostics and measurement harness (before any tuning)
- Files: `FsrAdapter.java`, `FsrUpscaler.java` (flags), `UpscaleSwitches.java` (`iroh.debug.fsr` = checker | view), `FsrReplay.java` + new `staticEdgeTest` / `jitterSignTest` under `src/test/.../upscaletest/`, `TemporalRuntime.java` (log only).
- Do: enable DEBUG_CHECKING when `iroh.debug.fsr` is set or in dev builds; log drained messages once per distinct text; wire `UpscaleInputs.debugView` to a switch/hotkey; add offline metrics on lavapipe: (1) static diagonal edge: edge RMSE vs 8x SSAA reference after 1..16 frames at Native AA (frames-to-converge), (2) jitter sign (+/+ vs the four alternatives; the best must be the shipped one), (3) moving-object ghost: trail length after 4 frames with and without MVs, (4) thin-feature flicker (fence-like 1 px line) per key value.
- Acceptance: all four metrics print a table; `staticEdgeTest` reports convergence frame <= 8 on the synthetic scene or fails with the number; zero checker warnings in `fsrAdapterReplay`. These numbers are the baseline for every later card.

### Q3 [S] Tuned constants and per-mode defaults
- Files: `FsrSession.java`, `FsrAdapter.java`, `UpscaleSwitches.java` (`iroh.upscale.fsr.*`), `IrohOptions.java` (Advanced rows), `en_us.json`.
- Do: apply the 5 keys per context from switches (defaults = SDK defaults V G.2: 1.0, 1.0, 1.0, 0.333, -0.333); expose them in an "Advanced" group; run the Q2 harness sweep: FMINDISOCCLUSIONACCUMULATION up for thin features, FVELOCITYFACTOR down for bright specular, FSHADINGCHANGESCALE up for lighting flips; ship measured defaults only where the harness shows a win and the ghost metric does not regress; record the table in `fsr.md`.
- Acceptance: sweep table committed; defaults unchanged unless justified; `upscaleDefaultOffTest` stays 28/28 (all OFF by default).

### Q4 [S] RCAS sharpening discipline
- Files: `UpscaleSettings.java`, `IrohOptions.java`, `TemporalRuntime.java` (sharp read), shaderpack CAS gate in `PackSwitches` (read-only check only; disable pack CAS when FSR sharpness > 0 only if the pack path exposes a CAS flag, else document).
- Do: slider 0..100 stays; default 20 at Native AA, 10 at Quality+ (U: research says 0.1..0.25 for Minecraft's hard edges); ringing metric in the Q2 harness (overshoot % on a step edge); clamp negative colours before dispatch only if the harness shows NaN (U claim).
- Acceptance: harness shows sharpness 0.2 raises edge contrast with overshoot <= 3% on the step; PROCTOR screenshot pair OFF / 0.2 / 0.5 for owner choice.

### Q5 [S] Mip bias: slider, Native AA default, cutout clamp, size sync
- Files: `TemporalFrame.java` (mipBias), `Pipeline.java` (the two bias sites), `SamplerManager.java`, `UpscaleSwitches.java`, `IrohOptions.java`.
- Do: `iroh.upscale.mipBias` slider -5..0 with mode "formula" (log2(r/d) - 1) and "pixel-art" (formula + 0.5, clamped <= -0.25 at Native AA; U heuristic); bias recomputed every frame from the CURRENT render size (needed by Q11); cutout/alpha-tested pipelines (leaves, grass, fences, bars) use bias 0 (clamp) by pipeline flag, not by texture; atlas anisotropy 8..16 stays as is.
- Acceptance: unit test of the bias per mode/size (extends `temporalMathTest`); PROCTOR static-camera shots at bias {formula, pixel-art, 0} (cobblestone, leaves, gravel at 20 and 60 blocks) plus a 5 s strafe clip each: pick the default by the owner.

### Q6 [S] Reactive mask mode 1: Auto-Generated (SDK helper)
- Files: `iroh_fsr_jni.cpp` (`ffxFsr3UpscalerContextGenerateReactiveMask` entry), `FsrNative.java`, `FsrUpscaler.java`, `TemporalRuntime.java`, `UpscaleSwitches.java` (`iroh.upscale.reactiveMode` = iroh | sdk | voxel), `IrohOptions.java`.
- Do: shim entry takes opaque copy (already kept, C.3), final colour, outReactive, scale, cutoffThreshold, binaryValue, flags; `reactiveMode=sdk` runs it instead of our colour-difference compute; keep `iroh` as default until Q7 lands.
- Acceptance: `fsrAdapterReplay` runs all three modes (voxel falls back to iroh until Q7) without error; ghost metric (Q2) table per mode.

### Q7 [O] Reactive mask mode 2: Voxel-Aware Custom Mask
- Files: `VelocityPass.java`, `velocity.comp`, translucent/particle/weather draw hooks (`net/vulkanmod/render/**`, a render-layer id attachment or a per-draw push constant), `UpscaleSwitches.java`.
- Do: material-aware mask without an extra colour copy: write an R8 value per draw class during the passes that already run (translucent chunk layer = water/glass/ice: alpha clamped 0.9 -> reactive, T&C 0.8; tripwire/cutout vines: reactive 0; weather and particles: reactive = alpha clamped 0.9; animated atlas sprites (water still/flow, lava, fire, portal, sea lantern, prismarine; from the atlas sprite animation metadata) -> T&C 0.6..1.0; clouds un-vectored -> reactive 0.3). Choose between a second colour attachment and an image store in the fragment path by measuring pipeline count touched (`injector_scan`); keep the colour-diff compute as fallback for GL-shim draws.
- Acceptance: offline: `velocityGoldenTest` extended with a synthetic layered scene (translucent + particle + animated sprite) golden values; ghost metric <= `iroh` mode on every row; PROCTOR rain, water splash, lava, nether portal, beacon beam screenshot pairs; checker view shows reactive lit only on those classes.

### Q8 [O] Motion vectors: vertex animation, block entities, method option
- Files: `VelocityPass.java`, `ObjectMotion.java`, `velocity.comp`, chunk/entity vertex shaders only if the term needs it (preferred: CPU-computed per-layer wind delta uniform), `UpscaleSwitches.java` (`iroh.upscale.mvMethod` = camera | objects | full), `IrohOptions.java`.
- Do: (1) per-layer vertex-motion term for waving leaves/grass/water surface where the pack's own shader animates (check whether Iroh or Elemore mods animate vertices: UNVERIFIED, read first; if none, skip the term and document); (2) block-entity motion (chest lids, pistons, bells, banners) via their previous poses; (3) more than 64 objects: budget by screen coverage; (4) method option: `camera` (cheapest), `objects` (today), `full` (adds 1..3); (5) decide the hand rule by the swing test: pre-FSR with merged depth + MVs (today) vs post-FSR second hand draw at display res.
- Acceptance: golden tests for each term; PROCTOR: strafe past pillars vs distant mountains, mob sprint, piston/chest, hand swing and sprint, each as OFF / Native AA screenshot pairs plus a 3 s clip; ghost metric rows.

### Q9 [S] Reversed-Z spike: measure before building (gates Q10)
- Files: `FsrReplay.java` + new `depthPrecisionTest`; docs/knowledge only otherwise.
- Do: run the Q2 harness with a far-terrain scene (stairs/slabs at 128..1024 blocks, camera pan 0.5 deg/frame) using depth quantised like standard-Z D24/D32F (near 0.05, far 2048 and infinite) versus reversed-Z D32F with `FFX_UPSCALE_ENABLE_DEPTH_INVERTED` (+ INFINITE variant); report disocclusion-induced flicker and edge RMSE. Also grep and list every consumer of depth in the repo (`VRenderSystem.clearDepth*`, `GraphicsPipeline` compare op, `GlStateManagerM`, `DrawImpl`/`StateGuard`/`IrohDraw` depth func, `GlState` glDepthFunc/glClearDepth, `Matrix4fM.perspective`, `velocity.comp`, `copyWorldDepth`, RT G-buffer depth, pack depthtex0, Vista/LOD depth) with a one-line plan each.
- Acceptance: a table with the measured gain and the consumer list. If the gain is under 10% on every metric, Q10 is dropped (record in `fsr.md`).

### Q10 [O] Reversed-Z D32F end to end (conditional on Q9)
- Files: `Matrix4fM.java`, `VRenderSystem.java`, `GraphicsPipeline.java`, `GlStateManagerM.java`, `GlState.java`, `DrawImpl.java`/`StateGuard.java`/`IrohDraw.java`, `Vulkan.java`/`DeviceManager.java` (depth format, 24-bit path off), `SceneTarget.java`, `velocity.comp`, `TemporalMath.java`, `FsrAdapter.java` (flags INVERTED + INFINITE), shaderpack/RT depth consumers via a conversion copy.
- Do: one convention translator at the state layer (depth func LEQUAL<->GEQUAL, clear 1.0->0.0, polygon-offset sign, depth bias) so third-party GL code and the 300 translated pipelines see "standard" semantics; projection reversed with infinite far (`d = near/z`); D32_SFLOAT only; every reader of depth (pack depthtex0, RT, Vista, hand clear, `copyWorldDepth`) converts or gets a flag; sky depth becomes 0.
- Acceptance: all offline suites + `shaderHarness` + `glNetTest` + `injector_scan`; depth-golden tests for each consumer; the Q9 metrics reproduced on the real path; PROCTOR: parity gate scenes (shaders off) match the OpenGL baselines within the existing tolerances, far terrain edges and F5/portal/camera-cut checks, sky/clouds/hand intact. Risk: HIGH (Vista, shader packs, RT read depth); needs its own v-gate and an `iroh.depth.reversed` switch default OFF until the gate is green.

### Q11 [O] Dynamic resolution + target FPS controller
- Files: new `dev/elemore/iroh/temporal/DynamicResolution.java`, `TemporalFrame.java`, `TemporalRuntime.java`, `FsrSession.java`, `FsrAdapter.java`, `SceneTarget.java`/world image set, `IrohOptions.java`, `en_us.json`.
- Do: options: min scale, max scale (bounds), target FPS, lower threshold (drop scale when frame time > target x 1.05 for N frames), raise threshold (< target x 0.85 for M frames), step, hysteresis; allocate world images at the MAX render size once, render into a sub-rect (viewport/scissor, viewportFix already maps mods' rects), create the FSR context at max size with `DYNAMIC_RESOLUTION`, never reset on a size step, jitter phase count follows the current size, mip bias follows (Q5). Controller is pure and unit-tested; GPU frame time from the existing `SlowFrames`/perf counters.
- Acceptance: `dynamicResolutionTest` (controller sequences: hold, drop, recover, no oscillation within a 10 s synthetic trace); `fsrAdapterReplay` steps the size across 40 frames without context recreation or reset (counter asserts); PROCTOR: a sweep scene with an artificial load, scale visible in log, no flicker at steps.

### Q12 [S] FXAA for the non-temporal display path
- Files: new shader `assets/iroh/shaders/post/fxaa.*` and a pass in the display path (`TemporalRuntime` bilinear branch / `DefaultMainPass`), `IrohOptions.java`.
- Do: clean-room edge-aware AA (luma edge detect, sub-pixel blend; write from the public algorithm description, NVIDIA's FXAA 3.11 text is not copied) at display res before the HUD; only active when the upscaler is Off or bilinear; never stacked with FSR (research: no TAA before FSR).
- Acceptance: lavapipe golden (`fxaaGoldenTest`: aliased edge gets smoothed, flat regions bit-identical); PROCTOR screenshot pair OFF / FXAA.

### Q13 [S] Swapchain present mode option
- Files: `SwapChain.java`, `Config.java`, `IrohOptions.java`, `en_us.json`.
- Do: option Auto | FIFO | FIFO_RELAXED | MAILBOX | IMMEDIATE (only modes the surface reports; fall back to FIFO with a log line); frame cap rules and safety (cap <= 240, DECISIONS SYSTEM SAFETY) unchanged.
- Acceptance: unit test of the fallback table; log shows chosen mode; no Proctor needed.

### Q14 [S] Chunk build and upload pacing options
- Files: `TaskDispatcher.java`, `Config.java`, `IrohOptions.java`, P2 upload ring knobs (read-only; reuse what P2-4/P2-6 landed).
- Do: expose existing `chunkBuildDeferMode`, `builderThreads` and a per-frame upload byte budget as options with sane auto; auto lowers the budget while FSR frame cost is above target. Pacing logic already measured by P2; this card is plumbing and defaults only.
- Acceptance: option round-trip test (`ConfigMigrationTest` extension), `uploadSyncRepro` unchanged.

### Q15 [O] Async compute workload toggle
- Files: `FsrAdapter.java`, `ComputeQueue.java`, `DeviceManager.java`, a new timeline-semaphore handoff, `IrohOptions.java`.
- Do: option "FSR on the async compute queue" default OFF: dispatch on the compute queue family with queue-ownership transfers and timeline semaphores, gated on a separate compute family existing (RADV exposes one) and on validation cleanliness. Expect a small gain; stop and report PARTIAL if the validation layer or RADV shows a stall.
- Acceptance: `vkval-extra` and the timeline-sync suites green with the option on; offline hazard test; PROCTOR perf pair (needs load1 < 3, four launches per the perf-gate rule) shows no regression.

### Q16 [S] Linear colour feed experiment (U)
- Files: `FsrReplay.java`, `FsrAdapter.java` (experiment switch only).
- Do: measure FSR RMSE / flicker on the synthetic scene with gamma-space input (today) versus linearised input and re-encoded output (a compute pass at display size). Ship only if the win is larger than the extra pass cost; otherwise record as REJECTED in `fsr.md`.
- Acceptance: a table; no default change unless the gate in the card passes.

### Q17 [S] Frame generation 3.1.7 audit (after the quality target; AMD advice: lock upscaling first)
- Files: `iroh-native/fsr/**` FI shim, `FsrFrameGeneration.java`, `NOTICE`.
- Do: diff 1.1.4 interpolation (3.1.3) against 2.4.0 interpolation core (3.1.6) the same way as Q1; port the shader/host deltas that apply; record that the swapchain 3.1.7 component (DX12 + VK provider, `framegeneration/fsr3/vk`) is a swapchain proxy Iroh does not use (A.9), so "FG 3.1.7" is met as interpolation 3.1.6 + our own present path unless the owner wants the proxy (separate decision).
- Acceptance: `fgReplay` unchanged or better; version reported.

### Q18 [S] Remove DLSS and FSR 4 code paths (RUN ONLY AFTER the quality target above is met and the owner approves; reversible from git)
Exactly what it deletes (verified by grep on int-4c; there is NO NGX, NVIDIA or AMD-loader code in the Java or native sources, only the option surface):
- `dev/elemore/iroh/temporal/UpscaleSettings.java`: enum entries `FSR4`, `DLSS` (`Upscaler { OFF, FSR3 }`), the `FSR4` and `DLSS` cases of `upscalerReason`, and `nvidia()` / `isWindows()` if nothing else uses them.
- `net/vulkanmod/config/Config.java:51` comment, `IrohOptions.java` upscaler choices (FSR4, DLSS rows) and their reasons.
- `assets/vulkanmod/lang/en_us.json`: `iroh.options.upscaler.fsr4`, `iroh.options.upscaler.dlss`, `iroh.reason.fsr4Windows`, `iroh.reason.needsNvidia`, `iroh.reason.notYet` (if unused after the above) and the "(FSR, DLSS)" wording in `iroh.shaders.upscaler.tooltip`.
- Tests: `ConfigMigrationTest.java` lines 165-168, `TooltipLayoutTest.java` lines 72-73, `HwCapsRepro.java:76` (the `dlss=` field).
- Comment-only mentions to reword: `Upscaler.java`, `UpscaleInputs.java`, `QualityMode.java`, `JitterSequence.java`, `RtUpscaleStage.java:5`, `ResourcePlan.java:337`, `CutPoint.java:119`.
- Docs and knowledge: `docs/OUTLINE.md` P4-3 (FSR 4.1.2), P4-4 (DLSS SR + DLAA), P6-2 DLSS Ray Reconstruction half, P8-2 DLSS Frame Generation, `docs/P4-PLAN.md`, `docs/P6-PLAN.md` DLSS parts, the `iroh-nv` NGX line in the OUTLINE architecture block; `knowledge/dlss.md` -> `knowledge/history/`, `fsr.md` A.7 and A.8 -> history; INDEX and `DECISIONS.md` note "removed".
- DOES NOT delete: `iroh-native/natives-nv/` (that folder holds `libiroh_nrd` / `iroh_nrd.dll`, the NRD DENOISER, not DLSS), `build-nrd`, anything in `rt/denoise`, the `Upscaler` interface itself (keep one interface; bilinear and FSR 3.1 implement it), signed AMD DLL handling (none exists).
- Acceptance: `./build.sh`; `configTest`, `tooltipLayoutTest`, `upscaleDefaultOffTest`, `injector_scan` green; `rg -i "dlss|fsr ?4|ngx"` over `src` returns only history notes; no behaviour change with `upscaler=FSR3`.

## 4. Order and parallelism

1. Q1 (native, first) then Q2 in parallel only if they touch different files (Q2 does not touch `iroh_fsr_jni.cpp`): Q1 owns `iroh-native/fsr/**`, Q2 owns the Java adapter and tests.
2. Q3, Q4, Q5 (settings and defaults, each measured by the Q2 harness) in any order; they share `IrohOptions.java`, so run them one after another or merge per card.
3. Q6, then Q7 (voxel mask) and Q8 (motion vectors) in parallel (different files except `VelocityPass.java`/`velocity.comp`: serialise those two).
4. Q9 spike, then Q10 only if it earns it; Q10 is alone on the depth convention.
5. Q11 needs Q5 (bias follows size) and Q1 (max-size context). Q12, Q13, Q14 are independent small cards. Q15 last of the render cards.
6. Q16, Q17 after the quality target. Q18 only on the owner's word.

Expected sharpness / accuracy gain per effort, best first: Q2 (enables measuring), Q3 and Q4 (settings only), Q5 (mip bias, likely the largest cheap sharpness lever), Q6, Q7, Q8 (ghosting), Q9 then Q10 (largest accuracy lever, largest risk), then options for speed (Q11 to Q15).

## 5. Open questions for the owner

1. Q4 default sharpness 0.2 at Native AA and Q5 pixel-art mip default are hypotheses (U); the owner chooses from the screenshot pairs.
2. Q10 changes the depth convention for every mod and the shader-pack path; it only runs if the Q9 spike shows a real gain.
3. Q17: "frame generation 3.1.7" is the swapchain proxy component in SDK terms; Iroh does not use a swapchain proxy. Confirm that interpolation 3.1.6 is enough.
