# Iroh Phase 2 plan: beat Sodium + Iris (card P2-D, 2026-11-24, design only)

Target (DECISIONS.md "Performance target vs Sodium + Iris"): full pack, RD24+, shaders off vs Sodium:
avg fps >= 1.3x, 1% low >= 1.5x, no scene slower; Navidson on vs Iris: avg >= 1.25x, 1% low >= 1.4x;
lower p99 and hitch count on every scene; load to world not slower.
Tags: M = measured (source named), E = estimate (no measurement yet), U = unverified claim.
Main = 58031ce (int-2b v12). Sources: proctor knowledge/perf/block2 (rc13, cap 240, load1 8-11 during
the runs), block6/7 (rc14 slow-frame attribution), R-PROF P2-P5 (rust-audit-2026-11-23.md), STATE.md.

## 0. Three findings that reshape the plan

1. **The 240 cap binds, so the avg-fps target cannot be shown as specified (M).** In every block 2
   scene the MEDIAN frame is 4.167 ms on both sides (= the 240 cap). The highest Iroh/Sodium avg ratio
   a capped run can show is 240 / Sodium avg: stand RD12 1.02x, stand RD24 1.13x, stand RD32 1.31x,
   hop 1.23x, glide 1.26x. SYSTEM SAFETY rule 3 (no uncapped frame rates, overrides everything)
   forbids the target's "cap off". Proposal (needs orchestrator + owner acceptance): avg fps is
   compared as **busy fps** = 1000 / mean(frame interval minus time inside the fps limiter), on both
   clones, cap 240; 1%/0.1% lows, p99 and hitch counts come from real frame intervals (they sit far
   below the cap, so it does not bias them). Busy time keeps GPU waits (Iroh frame fence, GL swap
   blocking), so a GPU-bound scene is still charged.
2. **Shaders off, the GPU is nearly idle; the render thread and its hitches decide (M).** Iroh GPU
   time per frame 0.2-0.6 ms (FrameProfiler timestamps, block2 p1rd32/p2/p3 logs, Proctor window
   size); Iroh's sysfs GPU busy was 0.57-0.81 of GL's (STATE "first perf comparison"). World
   render CPU 1-3 ms per frame, largest stage AFTER_ENTITIES (entities + block entities, 0.4-3 ms).
   Slow frames (block 6, glide RD24, 195 frames >= 16.7 ms): largest phase client tick 111, tasks
   (chunk packets) 53, render other 22, visibility graph 6; worst frames are `tasks` 24-33 ms at
   83-99% cpu of wall; client-tick frames run at 45-60% cpu of wall (render thread descheduled).
   GPU-side levers (barriers, VMA, bindless) therefore move nothing shaders-off at 720p; they matter
   for Navidson and at the owner's 2560x1440 (hyprctl: DP-1 2560x1440@165).
3. **One scene is already slower (M):** hop flight RD12 1% low 0.90x (45.3 vs 50.1 fps), 0.1% low
   22.5 vs 30.6, max 86 vs 57 ms, frames > 33 ms 27 vs 6. Today the gate fails "no scene slower" there.
   All block 2 runs had load1 8-11 from other sessions: **re-baseline before trusting any ratio.**

Corollary: client tick, chunk-packet parsing and mod code run on BOTH clones. A fix in an owner mod
that also runs on GL goes into both clones (it helps the pack, not the ratio). Ratio movers live in
Iroh or in Iroh-only paths (Iroh's chunk-load hooks, builders, draw path, limiter, thread policy).

## 1. Where frame time goes today (per scene)

| scene (source) | Iroh vs Sodium (M) | Iroh render thread (M) | GPU (M) | limiter |
|---|---|---|---|---|
| p1-stand RD12/24/32 (block2) | avg 1.01/1.10/1.18x (cap-bound); 1% low 1.16/1.22/1.39x; p99 7.5/9.5/12.1 vs 8.4/12.6/16.4 ms | world 1-3 ms, entities stage largest; spike cause UNKNOWN (no slowFrames run on stand) | 0.15-0.6 ms | median = cap |
| p2-hop RD12 (block2), RD24 (block6) | avg 1.11x, **1% low 0.90x**, max 86 vs 57 ms | RD24: client tick 66 / tasks 51 / render other 12 / graph 9 of 141 slow frames | ~0.2 ms | cap |
| p3-glide RD12 (block2), RD24 (block6) | avg 1.19x, 1% low 1.32x, p99 11.2 vs 16.2 | RD24: tick 111 / tasks 53 / other 22 / graph 6 of 195; slowest task class_2600 lambda 2-5 ms; areaGrow 8/195; stagingWaits 0 | ~0.2 ms | cap |
| d-citadel (R-PROF P2) | no GL pair | iroh 26%, clock_gettime/glfwGetTime 26% (vanilla limiter loop); chunk gen in window | n/a | yes |
| i-mobs200 (R-PROF P4) | no GL pair | apoli 19.5%, puzzleslib 11.8%, iroh 18.9% | n/a | |
| glide (R-PROF P5) | no GL pair | iroh 21%, dreamdisplays 13%, Polaris 0.6% | n/a | |
| g-thunderstorm (R-PROF P3) | no GL pair | Tempest StreakParticle 1.5% | n/a | |
| Navidson on | NOT measured on either side | pack CPU via iroh.pack.cpu | lavapipe only | |

R-PROF windows included chunk generation (c2me 68-73% of CPU), so client code is a thin slice there.
**Missing captures and the exact capture that fills each (all through P2-1 harness, guarded):**
- (a) Busy time on both clones: Proctor's own client mod wraps the `RenderSystem.limitDisplayFPS(I)V`
  call in `Minecraft.runTick` (the same INVOKE Iroh's SlowFramesMinecraftM uses) and logs per-frame
  interval + limiter ns into the existing perf frames file.
- (b) Render-thread CPU on BOTH clones: `asprof -e cpu -t -i 1ms -d 40 -o collapsed` armed by
  Proctor's `profile` step in an UNMEASURED flight after the measured rounds (block 9 rule), scenes
  p1-stand RD24, p3-glide RD24, p2-hop RD24, i-mobs200, d-citadel, on a pregenerated route; analyse
  with `knowledge/perf/rprof/analyze.py FILE 'Render thread'`.
- (c) Iroh GPU per pass: `-Diroh.profile=true` (FrameProfiler stage timestamps, + `EXTRA` pack
  passes) in a separate diagnostic launch at 2560x1440 and at Proctor size; GL side: sysfs
  gpu_busy_percent from sampler.py only (no GL per-pass timers; not worth a GL mod).
- (d) RGP: capture works on RADV (`MESA_VK_TRACE=rgp`, `MESA_VK_TRACE_TRIGGER=<file>`, tools.md),
  the viewer is a GUI: only when (c) shows a GPU-bound Navidson pass worth a wave-level look.
- (e) Slow-frame attribution on stand scenes and i-mobs200: `-Diroh.slowFrames=12` diagnostic launch
  (never in a measured launch).

## 2. Levers, ranked (expected gains are E unless marked)

Tier names (hardware-matrix.md, 17 profiles): **T-A** drawIndirectCount + multiDrawIndirect +
samplerFilterMinmax + descriptorIndexing + timeline semaphores (every desktop profile: NVIDIA
Pascal..Blackwell, AMD Polaris..RDNA4, Intel Arc, UHD 620, Xe); **T-B** Vulkan 1.2 low end without
drawIndirectCount / minmax / descriptorIndexing / sync2 but with multiDrawIndirect (HD 520 profile);
**T-C** no multiDrawIndirect (no profile; keep the direct-draw path). Every lever keeps today's
path as the fallback and as a debug switch for A/B.

| # | lever | moves | expected gain (E) | risk | tiers / fallback |
|---|---|---|---|---|---|
| L1 | Streaming hitches: TaskBudget v2 (simulated better on 8/8 workloads, M offline), AreaBuffer pooled arena (no render-thread vkAllocateMemory, no full-copy regrow), section-intake budget checked per section, Iroh's chunk-load hook cost (ClientChunkCacheM, setSectionDirty x24) | 1% low, 0.1% low, max, >33 ms in hop/glide | hop 1% low 0.90x -> 1.2-1.5x; >33 ms frames -50% | v1 budget was WORSE (block 7: 130 -> 249 slow frames); ABAB mandatory | CPU only, all tiers |
| L2 | Thread policy: L-T (built, RQ-L0-a), Iroh builders off the render core and SCHED_IDLE/nice, elemore-core ThreadTuner adds `Builder-` | client-tick frame wall (45-60% cpu of wall today) | slow-frame wall -10..-30% | builders starved -> slower chunk fill (gate: chunk rate not > 10% worse) | Linux nice/affinity; Windows: thread priority (U), else no-op |
| L3 | Visibility graph off the render thread: BFS on a worker, double-buffered result, one frame late, union with last frame's set on fast turns; exact rule from block 7 (structural upload -> full walk) | per-frame CPU at RD24-32, graph spikes 4-9 ms | -0.5..-2 ms per frame at RD32; graph phase out of slow frames | missed sections (graph cache failed verify by up to 229): keep `iroh.graph.verify` oracle | CPU only, all tiers |
| L4 | Entity / block-entity fast path: ModelPart cubes written straight into the mapped frame buffer (clean room from vanilla ModelPart behaviour), batch by pipeline + texture, fewer descriptor binds; vanilla path kept whenever a foreign injector targets the method (injector_scan) | AFTER_ENTITIES stage, i-mobs200, j-blockentities | entity stage -20..-40% | mod injectors into ModelPart/VertexConsumer lose effect: per-method fallback | CPU only, all tiers |
| L5 | GPU-driven terrain: (a) one vertex arena + one indirect buffer per render type, one MDI per layer instead of per-area bind/push/UBO bind; (b) compute frustum + facing cull -> `vkCmdDrawIndexedIndirectCount`; (c) two-pass Hi-Z occlusion (samplerFilterMinmax reduce), BFS demoted to rebuild scheduling at lower rate | render-thread terrain CPU, RD32+ scaling, pack shadow pass | terrain draw CPU -> ~0.1 ms flat; GPU +0.1-0.3 ms; RD32 busy fps +10-25% | pop-in on fast turns (two-pass fixes), displaced water/foliage (grow AABBs), translucent stays CPU-sorted, Vista/api 4 share | T-A full; T-B: compute writes instanceCount 0 for culled + fixed-count MDI, Hi-Z by 4-tap compute; T-C: today's CPU path |
| L6 | Mod hot spots on the render thread (apoli, puzzleslib, dreamdisplays): profile frames first (P2-2); Iroh-side call reduction (cull before mod render mixins run) moves the ratio; elemore-core fixes (Clavis precedent, never edit their jars) go into both clones | i-mobs200, glide | unknown until (b) names the frames | wrong-output risk: identical-output test per fix | CPU only |
| L7 | Frame pacing: precise limiter (sleep to deadline - 1 ms, then Thread.onSpinWait) replacing the glfwGetTime loop (26% of render-thread samples, d-citadel); optional VK_KHR_present_wait pacing; frames in flight stay 2 | jitter, SMT sibling freed for builders | p99 small; busy fps 0 | none known | present_wait where offered (RADV yes), else fence pacing |
| L8 | Pipeline hitches: count vkCreate*Pipelines inside frames (new SlowFrames counter); pre-warm known terrain/entity states at load; EXT_graphics_pipeline_library where offered (RADV exposes it) | hitch count on first sight of a state | removes first-use spikes (size U) | GPL path untested on NVIDIA/Intel | disk pipeline cache (exists) + pre-warm |
| L9 | Sync hygiene: timeline semaphores for transfer -> frame, frame waits uploads at VERTEX_INPUT/TRANSFER/FRAGMENT instead of ALL_COMMANDS (Renderer.java:519), RQ-APP1-a remainder (VulkanImage.transitionLayout 628/656, MainDepth, GpuCore 649, FsrAdapter, TemporalRuntime) | GPU overlap, Navidson frames | shaders off ~0; Navidson low single-digit % GPU (perf.md C12) | sync bugs: vkval sync + GPU-AV gate | timeline core 1.2 (all profiles); binary semaphores fallback |
| L10 | VMA budget/priority/dedicated (RQ-APP1-e) | oversubscription stalls, MemoryGuard accuracy | no fps; fewer pressure spikes | low | flags only when the extension is offered (Arc A380/A770, UHD 620, Xe, HD 520 profiles lack memory_priority) |
| L11 | Bindless textures | entity batching across textures only (terrain is one atlas: zero gain) | deferred until L4's profile shows descriptor binds > 5% of the render thread | descriptor model rewrite | descriptorIndexing (T-A); per-draw sets (T-B) |
| L12 | Navidson-specific (Phase 3 gate): shadow pass culled by L5b's compute with the shadow frustum, pass-graph barriers (DONE B-3B), transient aliasing (iris-loader resource plan), per-pass GPU ms from FrameProfiler.EXTRA | Navidson avg and lows | shadow pass CPU -> ~0; GPU frame -5..-15% | look parity per pass | same tiers as L5 |
| L13 | Upstream VulkanMod back-port (old P2-1) | unknown | unknown | regressions | n/a |

Persistent staging ring and async transfer already exist (StagingRing 16 MiB blocks / 256 MiB,
uploads on the compute-family queue; stagingWaits 0 in block 6): only L9's semaphore/stage work is left.

## 3. Cards (routing: [O] Opus, [S] Sonnet, [S/G] Gemini first attempt via tools/gemini-card.sh then
Sonnet re-runs acceptance, [P] Proctor agent; every code card ends with the offline gate of
DECISIONS "In-game tests only after significant changes"; in-game runs only at batch boundaries)

| card | what | accept: offline gate + in-game perf A/B |
|---|---|---|
| P2-1 [S/G] | Measurement harness in Proctor: busy-time hook (finding 1, capture a), `perfpair --clone-a iroh-gl3 --clone-b iroh` (ABAB across clones), suite mode (one launch runs the whole scene list in fixed order), pregenerated route snapshot (chunks generated + lit, c2me idle), window size option 2560x1440, analyzer prints busy fps / avg / 1% / 0.1% / p99 / >16.7 / >33.4 / hitches / GPU busy / load1 per segment | driver unit tests + `--dry-run`; no launch |
| P2-2 [P] | Baseline: protocol of section 4 on v12 vs iroh-gl3, shaders off; captures (b) (c) (e); one table per scene into knowledge/perf.md with spreads; also boot/create/rejoin timing gates on both | table complete; every number with launch ids and load1 |
| P2-3 [O] | Correctness before speed: port int-4c's `drawIndirectFirstInstance` enable to int-2b/3b (terrain indirect commands carry firstInstance = section offset, RQ-P2D-a); gate `indirectDraw` on `isDrawIndirectSupported` (T-C falls back to direct draws) | hwCapsRepro asserts firstInstance enabled wherever the indirect path is used; new terrain indirect harness under `-Pvalidate=gpuav` clean; hw-matrix PASS/SKIP |
| P2-4 [O] | L1 streaming hitches (TaskBudget v2 in game behind its switch, AreaBuffer arena, per-section intake budget, chunk-load hook cost) | TaskBudget simulator 8/8, AreaBuffer unit tests, vkval sync; in-game: p2-hop + p3-glide RD24, `ab` switch off/on in one session x 4 rounds, then cross-clone pair: 1% low, 0.1% low, >33 ms, max |
| P2-5 [S] | L2 thread policy A/B (L-T jar + builder policy, co-operating with Vista's Sched), owner-mod part in elemore-core installed in BOTH clones | glide + hop RD24 ABAB; chunkgen-rate within 10% |
| P2-6 [O] | L3 async visibility graph with the verify oracle | headless graph test: async result == sync result on recorded camera paths (0 missing); in-game: p1-stand32 + p3-glide RD32 busy fps and graph phase share |
| P2-7a [O] | L5a world arena + one MDI per layer (CPU-written commands) | offline terrain replay pixel-identical to the per-area path; in-game p1-stand32 busy fps |
| P2-7b [O] | L5b compute frustum/facing cull + drawIndexedIndirectCount, T-B fixed-count variant, T-C CPU path | hw-matrix all 17 profiles PASS/SKIP with the tier printed; GPU-AV clean; offline visible-set == CPU set on recorded frames; in-game p1-stand32, p3-glide RD32, parity screenshots |
| P2-7c [O] | L5c two-pass Hi-Z occlusion; the CPU graph walk (P2-6 async) stays the candidate source, Hi-Z culls its candidates (DECISIONS "GPU culling techniques as player options"; the walk is not demoted to rebuild scheduling) | offline: no visible section culled on recorded paths (Hi-Z vs ray-cast oracle); in-game glide RD32 + e-cave + pop-in check by screenshot pairs on fast turns |
| P2-8 [O] | L4 entity fast path + injector-aware fallback | injector_scan no lost injector, shaderHarness, entity parity scenes; in-game i-mobs200 + j-blockentities ABAB |
| P2-9 [S] | L6 mod hot spots from P2-2 profiles: one finding per mod, Iroh-side call reduction or elemore-core fix with identical-output test | per-fix identical-output test; in-game i-mobs200 / glide ABAB |
| P2-10 [S/G] | L7 precise limiter + optional present_wait | unit test of the deadline math; in-game frame-interval stddev at cap, busy fps unchanged |
| P2-11 [S/G] | L8 pipeline-in-frame counter + pre-warm list; GPL behind a capability check | counter in SlowFrames; in-game: first-flight hitch count on a fresh cache |
| P2-12 [S] | L9 + L10: timeline semaphores, upload wait stages, RQ-APP1-a remainder, VMA flags | vkval sync + GPU-AV + hw-matrix; in-game rides the P2-4 batch ladder |
| P2-13 [O] | L13 upstream back-port review (list ported / skipped with reason in vulkanmod.md) | all offline suites; rides the next batch |
| P2-14 [O] | L12 Navidson: shadow pass on L5b culling, per-pass GPU table vs Iris whole-frame | packReplay/packFlightRepro identical; in-game Navidson pair vs Iris clone (Phase 3 gate scenes) |
| P2-15 [P] | 30-minute pack soak on the batch-D jar (old P2-3 leak check) | memStats flat native + device memory; guard idle check 2.5 min before/after |
| Gate 2 [P] | Full protocol, shaders off, RD24 + RD32, all scenes; ladder green | targets of DECISIONS met on every scene or owner accepts the miss |

**Order to hit the targets soonest:** P2-1 -> P2-2 (we may already be >= 1.3x on busy fps; the
1% low, worst in flight, is the hard target) -> P2-3 (tiny, correctness) -> batch A: P2-4 + P2-12
(frame loop / sync / memory: one ladder + perf) -> batch B: P2-5 + P2-10 + P2-11 -> batch C: P2-6
+ P2-8 + P2-9 -> batch D: P2-7a/b/c (RD32+ scaling, also feeds P2-14) -> P2-13 -> Gate 2.
Parallel offline lanes: P2-3, P2-7a and P2-13 need no game and can start as soon as P2-1 is out.
Re-rank after P2-2: if the stand scenes show entities dominating, P2-8 moves ahead of P2-6.

## 4. Measurement protocol

- **Clones:** baseline `iroh-gl3` (frozen; Iroh jar removed, Sodium 0.8.13 + Iris + sodium-extra +
  IrisExtension + IrisSearch + cull-fewer-leaves + SmoothGL restored, native radeonsi); candidate
  `iroh`. Same owner-mod jars and options on both (update both when an owner mod changes), same
  options.txt (RD, graphics, particles, clouds), shaders off (Navidson pair for the Phase 3 gate).
- **Bounds (guard):** Vista enabled:false, C2ME OpenCL off, cap 240, vsync off, validation off,
  no `-Diroh.profile` / slowFrames / asprof in measured segments (diagnostic launches are separate).
- **World and window:** the pregenerated route snapshot (P2-1), one world per launch; gate numbers
  at 2560x1440 (owner's DP-1), regression A/Bs at Proctor's default size.
- **Scenes (RD24 unless named):** p1-stand, p1-stand32 (RD32), p2-hop, p3-glide, d-citadel
  (citadel fly-through), c-forest, e-cave, i-mobs200 (boss/particles stand-in with
  h-explosion-particles), g-thunderstorm (storm); "32-chunk vista" = p1-stand32.
- **Launches:** FOUR, interleaved A B A B (gl3, iroh, gl3, iroh), each running the whole suite in
  one fixed order with 60 s warm-up flight, then per scene 120 s measured (block 2 lengths). A
  scene's value = mean of its 2 launches per side; spread = their difference; a ratio counts only
  when the gap exceeds both spreads. Iroh-vs-Iroh A/Bs use the in-session `ab` step first (4
  rounds, one launch), cross-clone pairs only at batch ends.
- **Idle machine:** load1 < 3 at launch and between launches (perfpair waits, chain.run_perfpair),
  a segment whose sampler load1 exceeds 3 is void and re-run; no owner game, no other test game,
  no Gradle builds (the orchestrator pauses build agents for a perf block), Ollama model unloaded
  (GPU turn-taking).
- **Metrics per scene and ratio Iroh/GL:** busy fps (finding 1), avg fps, 1% and 0.1% low, p99,
  max, frames > 16.7 / > 33.4 ms, hitches (> 2x median and > 8 ms), GPU busy %, VRAM peak,
  render-thread CPU %. Load: boot-to-title, create-to-playable, rejoin-to-playable on both clones.
- **Recording:** proctor knowledge/perf/p2-<card>/ raw + analyzer output; summary row in
  iroh knowledge/perf.md with jar sha, clone, load1, launch ids.
