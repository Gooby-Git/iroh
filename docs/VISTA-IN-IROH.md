# Vista in Iroh: Distant Terrain as a built-in feature (card VISTA-DESIGN, 2026-11-24, design only)

Owner decision (DECISIONS "Vista becomes an Iroh feature"): "Incorporate vista into iroh so instead of having two mods Vista
gets entirely re-written still with rust in mind to work with vulkan as a feature in iroh with its own settings tab."
Iroh is public (LGPL-3.0-only). Vista's own code is the owner's and may be reused. Voxy and Distant Horizons are study-only and
were not read for this design. Facts are marked V (read in code / measured) or U (not checked). Knowledge: `knowledge/vista.md`.

Sources read: `~/Projects/vista` (DESIGN.md, docs/IROH-PORT.md, docs/RUST_PLAN.md, branch `iroh-port` e9beb8a, `native/`),
Iroh int-2b (`~/Projects/iroh/iroh-wt-v1` fe0e010d), int-3b (`~/Projects/iroh-wt-3b`), int-4c (`~/Projects/iroh-wt-p41`),
knowledge api.md (api 4 / 4.1), perf.md (P2-4, P2-6, P2-7a), vulkanmod.md (MemoryGuard, MemoryShares, P2-12), settings.md,
docs/P2-PLAN.md, docs/FSR-QUALITY-PLAN.md, P5-DESIGN.md, the pack's `config/vista*.json`.

## 0. Summary

- The feature is called **Distant Terrain** in the UI, `dev.elemore.iroh.lod` in code, `iroh_lod` for the native library.
- The data plane (capture, store, cascade, seed generation, residency reads) moves into Iroh with the Rust crate as its core;
  Java keeps only what must touch Minecraft objects. The GPU plane is Vista's proven GPU-first design (GPU meshing, two-phase
  Hi-Z cull, indirect-count draws, vertex pulling), rebuilt on Iroh's `gpu` layer instead of through the public api-4 facade.
- Existing worlds keep working: the on-disk store format (VKP1 version 2) is unchanged and read in place.
- Pack-specific bridges that link third-party code (Xaero's World Map, Chunksmith) do NOT go into public Iroh; Iroh offers
  a public LOD API, and the bridges live in an owner mod once the licence questions in section 7 are answered.
- 17 cards (VL-0..VL-15 plus VL-6a; VL-16 optional), about 109 agent-hours; parity with Vista and the pack switch-over need
  VL-0..VL-12, VL-6a and VL-15 (about 91 agent-hours, two lanes, roughly 2 weeks elapsed including in-game batches). FSR and RT integration (VL-13,
  VL-14) follow those tracks.
- Blocking owner questions: Q1 dedicated servers, Q2 to Q4 licences (section 7). Everything up to VL-6 can start before
  the answers; the seed module and the bridges wait for them before the public push.

## 1. Vista today

### 1.1 Features (V, RUST_PLAN.md section 2 and DESIGN.md)

Server half (runs on the integrated server in singleplayer and on dedicated/LAN servers): chunk capture 5 s after
generate/load and on change; write-back cache; L0 to L4 cascade every 2 s; store (Deflate, 256 B sectors, hidden-cell
normalisation, content hashes); GapFill sweep; `/vista import` of region files; visited-chunk bitmap with InhabitedTime
backfill; StructurePass (surface structures generated to LIGHT ahead of players); BiomeGrid; landmarks (Citadels site files
+ config); far-player beacons at 4 Hz; Chunksmith sink; protocol 1-4 (offers, hashes, bandwidth cap, presence, seed bundle,
biome grid, map columns).

Client half: residency (everything in range stays in VRAM, eviction coarsest/farthest first with a KEEP margin, pins);
GPU unpack, downsample, greedy mesh into a paged quad heap; GPU page allocator; two-phase cull with Hi-Z; indirect-count draws
with an early-Z list and a discard list per level region; GPU radix sort for water; reversed-infinite-Z own target, depth
prefill from real terrain, composite under the frame; crossfades between levels (blue-noise dither); per-16^3-section
hand-off to real chunks with fade-in; spyglass zoom stages; Iris/Navidson `vista_*` programs; Tempest gloom, wet, snow,
lightning, curtains; night lights; leaf sway and water ripple; far-player figures; materials from baked models + tint probe;
Xaero fill; Chunksmith client target; client-only capture; seed LODs; `/vistac` stats, bench, culltest, poptest, selftest.

### 1.2 Data flow

```
server thread            vista-worker (2..N, nice 10)            Rust (JNI)
ChunkSnapshot.capture -> Ingest.apply (state -> id) -> WriteBack -> encode, hash, normalise, deflate, pwrite, cascade L0->L4
                                                                     store: <world>/vista/<ns>/<dim>/L0..L4
seed (client, vista-seed, SCHED_IDLE, budgeted):
SeedLods.plan -> SeedGen.column (L0-1 Java NoiseChunk + aquifers; L2-4 Rust search on the exported density graph)
              -> real cells overlaid -> store.put
client:
Residency thread: store.getPacked (pread + inflate, Rust) -> byte[] ready queue (64 MiB cap)
render thread:    copy into staging (IrohBuffers.upload on Iroh) -> scatter -> mesh/alloc/copy -> cull A -> draw A
                  -> Hi-Z -> cull B -> draw B + water -> composite (depth written into the main depth)
```

### 1.3 Threads (V)

| thread | priority | job |
|---|---|---|
| vista-worker x2..CPU_THREADS | renice 10 (`Sched.LOW`) | server ingest, cascade, backlog scale-up |
| vista-seed (budgeted pool) | `chrt --idle` (SCHED_IDLE) | seed columns, biome grid, structure pass share `Sched.CPU_THREADS` = max(2, cores/6) permits |
| Residency (1) | LOW | plan + read packed sections |
| vista-client-ingest, vista-chunksmith | LOW | client-only capture, Chunksmith client |
| vista-xaero | IDLE | map fill |
| vista-net / vista-net-client | NORM-1 / NORM-2 (no-op on Linux) | protocol |

`Sched` spawns `chrt`/`renice` processes (Linux only; nothing on Windows). elemore-core ThreadTuner matches the `vista-` prefix.

### 1.4 Memory (V)

GL build: two sparse heaps (voxels, quads) 2 GiB virtual each, committed in 64 MiB steps; ~310 MiB fixed; window targets ~17 B/px;
auto budget min(6 GiB, free VRAM at join - 2 GiB). Iroh port (VkLods): chunked heaps, rung ladder, every object an api-4
`GpuResource` of owner "vista" inside a 40 % share (`iroh.gpu.ownerShare`); lavapipe batch: 315 MiB in 55 objects at 2048.
The pack runs distance 12288 with budget auto; this was part of the 2026-11-21 crash context (DECISIONS SYSTEM SAFETY).

### 1.5 Rust crate (V, `native/vista-native`, 3.65k lines, ABI 3)

| module | role | state |
|---|---|---|
| cell, packed, hash, hidden, downsample | codec, hashes, hidden-cell normalisation, L->L+1 | parity-tested vs Java |
| packfile, store | VKP1 v2 files, sector allocator, cascade | write 0.66 -> 0.32 ms/section, read 0.09 -> 0.04, cascade 4.6 -> 1.27 ms/parent |
| noise, density, jmath, column | Minecraft noises + density graph + seed search L2-4 | 40x per column (vanilla JUnit); pack graph needed a cache_2d memo |
| jni | 29 exports, panics -> Java exceptions | JNI, not FFM (JDK 21 era) |

### 1.6 Known problems (V unless marked)

- In-game state: GL renderer is what the OpenGL pack uses; on Iroh the api-4 renderer passed `i8-vista-m31` once, on
  `iroh-vanilla`, AMD only. Never run on the pack clone (Vista disabled there), never with shaders, never on NVIDIA/Intel.
- **AMD-only on Iroh**: Iroh's main depth is D24S8 wherever offered (all NVIDIA and Intel profiles); `MainDepth.copy` and the
  composite pipeline require D32F (RQ-VDES-a).
- GL defects that must not come back: a failed constructor leaked ~350 MiB; close() was not exception-safe.
- Gaps in LODs were missing store data (fixed by GapFill); seed overflow quirk at y ~ 2^31 fixed 560b58f.
- Not ported to Vulkan yet: far figures, Tempest on LODs, the shader-pack path, zoom, landmarks, GPU-read counters, the
  cull/pop/self tests, back-face culling at the pipeline.
- Seed L0-1 (aquifers, surface rules, climate) still Java; R4 continuation open (R-VISTA-R4).
- CPU share in pack scenes: Vista structure + worker threads 4-6 % (R-PROF, d-citadel).
- "Realistic LODs" (Polaris look) not started; parked by the owner.

### 1.7 Reuse, rewrite, drop

| part | verdict | why |
|---|---|---|
| Store format VKP1 v2, IdTable files, visited/structures bins | **reuse as-is** | existing worlds open unchanged; format is good (Deflate, hashes) |
| Rust codec/store/cascade/downsample | **reuse**, moved into `iroh-native/lod`, ABI 4, licence header LGPL-3.0-only | parity-tested, measured wins |
| Rust seed search (noise/density/column/jmath) | **reuse behind cargo feature `seed`**, pending Q4 | Mojang-logic port: legal gate before the public push |
| Java data model (`data`), PackFile Java fallback, Downsampler | **port** (package rename, tests along) | needed as fallback where the native lib is missing |
| Server capture/ingest/cascade/WriteBack/GapFill/Visited/StructurePass/BiomeGrid/Importer | **rewrite thin**: Java captures and maps ids; packing, hashing, normalising, encoding, cascade in Rust | "rust in mind"; ingest is the remaining per-block Java cost |
| Network protocol 1-4 | **rewrite** as protocol 5 on `iroh_lod:*` channels; same message set; Vista servers are not spoken to | clean break, no mixed-version risk |
| Seed planner (SeedLods, SeedGen, WorldgenBundle, GraphExport, Canopies, Terrain) | **port** with traps kept (C2ME density compiler, NoiseChunk aquifer width, AW entries) | hard-won exactness |
| Residency | **rewrite**: Rust reads + inflates straight into upload memory, no `byte[]` | zero-copy path |
| GPU plane (shaders, VkLods, LodSlots, VkPrograms) | **rewrite on Iroh's `gpu` layer**, same algorithms and shaders | internal access (depth, jitter, MemoryGuard, section events) without api-4 limits; offline goldens stay the oracle |
| GL renderer (ClientLods, GL shaders' GL-only parts, SparseHeap, Staging) | **drop** | Iroh is Vulkan-only |
| `mixin/sodium/*`, `mixin/iris/*`, `compat/iris/*`, SodiumReady, SectionSweep (BRD) | **drop** | Iroh gives section events; Iroh's pack loader runs `vista_*` |
| `mixin/xaero/*`, XaeroBridge, RemoteMaps | **move out of Iroh** to an owner-mod bridge on the public LOD API (Q3) | Xaero is All Rights Reserved |
| `mixin/chunksmith/*`, ChunksmithBridge/Client | **move out of Iroh**, optional (Q2) | Chunksmith is GPLv3; Iroh's own capture already sees pregen chunks |
| Citadels landmark reader | **move to citadels / elemore-core** as a landmark provider | pack-specific |
| Sched (process spawning) | **replace** by native thread priority from the Rust lib (Linux `sched_setscheduler(SCHED_IDLE)` / `setpriority`, Windows `SetThreadPriority`) | works on Windows, no process spawns |
| Cloth/ModMenu screens (VistaScreen, ClothScreen, ModMenuEntry) | **drop** | Iroh settings tab |
| Tools (TopDown, SizeProbe, Upgrade, BlockColours) | **port TopDown + Upgrade as Gradle dev tasks only** | gap diagnosis recipe (memory note) |

## 2. Target architecture inside Iroh

### 2.1 Principles

1. Iroh must not depend on any Elemore mod (vanilla check). Distant Terrain works in a plain Fabric + Iroh install.
2. GPU-first rendering stays; CPU work goes to Rust in batches (one JNI/FFM call replaces >= 50 us of Java, rust-audit rule).
3. Every GPU object is an Iroh `GpuResource` freed by Iroh at world unload/failure; one failure line, feature off for the
   session, data side keeps running (V-M3w rule).
4. Offline first: every renderer change proves itself on lavapipe goldens and validation before a game launch.
5. One feature switch: `lod.enabled` false = no thread, no GPU object, no hook cost beyond one boolean test per stage.

### 2.2 Package layout (all new files; existing Iroh files get one-line registrations only)

```
dev.elemore.iroh.lod
  LodFeature              lifecycle: detect Vista jar (feature off + reason), world join/leave, dimension change, failure
  LodApi                  public, in dev.elemore.iroh.api.lod: landmarks, map-column queries, section-change listener,
                          ingest of extra sources, "is far terrain drawn here"; used by bridges in owner mods
  config/                 LodOptions (registry rows, category DISTANT_TERRAIN), LodMigration (vista.json, vista-server.json)
  data/                   Cell, PackedSection, SectionKey, BlockClass, ClassTable, IdTable, Hidden, MapColumns
  store/                  LodStore (facade), PackFile (Java fallback), LodPaths (world dir, client cache)
  nat/                    LodNative (JNI or FFM, decided in VL-5), NativeLib reuse
  server/                 LodServer (common entrypoint), Capture, Ingest, WriteBack, Cascade, GapFill, Visited,
                          StructurePass, BiomeGrid, Landmarks, Importer, LodCommands
  net/                    Protocol v5 packets (`iroh_lod:*`), NetServer, NetClient, FarPlayers (beacons)
  seed/                   SeedPlanner, SeedGen, Terrain, GraphExport, WorldgenBundle, Canopies
  client/                 LodClient, Residency, ClientCapture (client-only mode), Materials, TintProbe, Levels, Pins
  render/                 LodRenderer (stage hooks), Heaps, Slots, Programs, Coverage, Composite, HiZ, Figures, Weather
  threads/                LodThreads (named `iroh-lod-*`, native priorities, CPU-permit budget)
  debug/                  F3 line, memStats line, commands (stats, selftest, culltest, poptest, render on|off, nativecheck)
assets/iroh/shaders/lod/  the GLSL 450 programs (from Vista's shaders + shaders/vk), one std430 block per program
assets/iroh/lang/en_us.json  Distant Terrain strings (separate namespace: no conflict with the vulkanmod lang file)
iroh-native/lod/          Cargo workspace member `iroh-lod` (cdylib iroh_lod), build-lod.sh
```

Registrations in existing files: `IrohOptions.Category` + `DISTANT_TERRAIN`, one page line in `Options`, one call in
`Initializer` (client), `fabric.mod.json` (main entrypoint + common mixin config, see 2.9), `MemoryShares` row (2.7),
`build.gradle` applies `gradle/lod.gradle` (all LOD test tasks live there to avoid build.gradle conflicts).

### 2.3 Native crate in iroh-native

- `iroh-native/lod/` = Vista's crate renamed (`iroh-lod`), `license = "LGPL-3.0-only"`, headers per file, NOTICE entries for
  Larion (Apache-2.0, the ported density types) and the crates (jni MIT/Apache-2.0, libdeflater Apache-2.0 + libdeflate MIT,
  lz4_flex MIT).
- Cargo features: `store` (always), `seed` (default on in dev; the release build switches it off until Q4 is answered; the
  Java side then uses the Java seed path, which calls Minecraft's own NoiseChunk at runtime and copies nothing).
- New Rust work ("rust in mind"): (a) ingest: Java hands one chunk as raw palette indices + a palette-to-id table + light
  nibbles + biome ids in one direct buffer; Rust builds cells, hashes, normalises, encodes, deflates, writes, schedules
  cascade parents; (b) residency read straight into a caller-provided direct buffer (upload slice), returning lengths;
  (c) thread priority calls (`setIdle`, `setLow`) for Linux and Windows; (d) batch `surfaceColumns` for R-SITES (shared with
  citadels/elemore later, same crate, same ABI).
- Build: `iroh-native/build-lod.sh` (cargo, Linux .so) and a `lod` step in `build-windows.sh` (`cargo build --target
  x86_64-pc-windows-gnu`, the rustup target is installed here, mingw from W-1), then the same import allow-list, export check
  and Wine `LoadLibrary`/`GetProcAddress` smoke as FSR/NRD. Jar layout `/natives/<os>-<arch>/iroh_lod.{so,dll}` through the
  existing `NativeLib` loader (`-Diroh.lod.native=path` for tests). macOS: no build; Java fallback (as Vista today).
- Binding: JNI stays for VL-1 (proven, panic-safe). VL-5 measures FFM (Java 25 final API: downcall with
  `Linker.Option.critical(true)` into a `MemorySegment` of the staging slice) against JNI on the read path and switches only
  on a measured win (RQ-VDES-b).

### 2.4 Data generation

- **Visited-chunk capture** (server half): same triggers as Vista (generate/load +5 s, block changes via the LevelChunk mixin,
  30 s dirty re-capture, unload). Java does only `ChunkSnapshot.capture` on the server thread (palette copy) and the
  state -> id map; the rest is one Rust call per chunk on an `iroh-lod-worker`.
- **Client-only capture** (server without Iroh LOD, e.g. a vanilla or Vista server): loaded chunks are captured on the client
  into `<gameDir>/iroh-lod-cache/<server-id>/<dim>/` (setting `lod.captureVisited`).
- **Seed LODs**: on the integrated server the client plans against the server's generator; on a remote Iroh server the server
  sends the worldgen bundle (random factory, AES-CTR, never the seed) as Vista does; L2-4 Rust, L0-1 Java until R4
  continues (aquifer, surface rules, climate in Rust: a later card, only with Q4 answered "yes").
- **C2ME interplay** (C2ME is kept: DECISIONS "C2ME verdict"): never wrap density functions (C2ME's compiler throws on
  foreign wrappers: use the game's NoiseChunk); call `doFill`/`doCreateBiomes` directly instead of the shared worldgen pool;
  `getChunkFutureMainThread` for structure pre-generation; GraphExport unwraps C2ME's `blendingFallback`. The Proctor clone
  rule "C2ME OpenCL off" is unchanged. Seed workers hold LodThreads permits so they never compete with C2ME's pool beyond
  the budget.
- **Structures ahead** (StructurePass) and **landmarks**: kept on the server half; citadel sites come from a provider
  registered through `LodApi.landmarks()` by the citadels mod (or elemore-core).

### 2.5 Rendering path (Vulkan-native)

Frame placement (unchanged from the working Vista-on-Iroh design):

| stage | work |
|---|---|
| BEFORE_MAIN_PASS | residency intake (upload slices from Rust), scatter, mesh / alloc / copy (time-sliced, adaptive 8-64 jobs) |
| AFTER_OPAQUE_TERRAIN | coverage scatter, depth prefill, cull A, draw A (early-Z list then discard list per level region, nearest first), Hi-Z, cull B, draw B, water sort + water, composite UNDER the main colour with depth written |
| shaders on | the pack's `vista_terrain(_early)` / `vista_water` programs draw the same culled lists into the pack's gbuffers (2.5.3) |

Internals:
- Heaps: chunked device-local buffers (256 MiB chunks) as in VkLods; no sparse binding in v1. Quads 8 bytes, vertex pulling,
  index buffer of 6 per quad, `gl_InstanceIndex` = section slot.
- Draws: `vkCmdDrawIndexedIndirectCount` on tier T-A; Iroh's zero-tail compute + fixed-count MDI on T-B (the api-4 fallback
  already does this); no T-C path for LODs (a device without multiDrawIndirect gets the feature greyed with a reason).
- **Depth convention**: own target reversed infinite Z (D32F, clear 0, GREATER) as now. The main depth is read through a
  depth-only sampled view in whatever format Iroh chose (D24S8 or D32F), never by a format-equal copy, and the composite
  pipeline is built for the real main depth format (fixes RQ-VDES-a). If FSR-Q Q10 lands (reversed-Z D32F main depth),
  VL-13 removes prefill + composite and draws LODs straight into the main targets after opaque terrain.
- **Early-Z**: real terrain depth prefilled into the LOD depth (pushed back 1 block) so real terrain rejects LOD fragments
  before shading; early-Z list (no discard) drawn before the discard list; nearest region first.
- **Composition with near terrain**: hand-off per real 16^3 section from Iroh's section events (built / reset), fade-in over
  `transitionMs`, level crossfades with the screen-stable dither. Iroh calls the LOD coverage update from its section intake
  directly (no listener indirection), before AFTER_OPAQUE_TERRAIN, which removes the one-frame-hole question from IROH-PORT
  11.5. Interplay with P2-6 (async visibility graph) and P2-7a (world arena): coverage follows "built", not "visible this
  frame", so neither changes the hand-off; VL-7 proves it with both switches on and off.
- **Reuse of the P2-7 work**: v1 keeps Vista's own cull and Hi-Z (they predate P2-7 and are proven by goldens). After P2-7c
  ships, VL-16 (optional) shares one Hi-Z pyramid per frame: P2-7c builds it from the main depth after opaque terrain, LOD
  cull B reads the same pyramid (one pyramid build saved). P2-7a's arena is not reused: LOD quads are GPU-written into
  GPU-allocated pages, near terrain is CPU-meshed; the two allocators answer different questions.
- **Render scale / FSR**: LOD targets are `createScaled` in the world phase, so with render scale on they are render-size
  automatically. The LOD pass uses the frame's jittered projection (FrameContext), otherwise FSR sees unjittered far
  terrain and shimmers. Motion vectors come free: Iroh's velocity pass derives camera motion from depth for every pixel
  (fsr.md B.4), and the composite writes LOD depth. Water LODs get the reactive mask bit like near water (FSR-Q reactive
  mode). Checked in VL-13 with the FSR harness on a far-terrain scene.
- **Shader packs (Navidson)**: the contract is unchanged (IROH-PORT section 10): programs `vista_terrain`,
  `vista_terrain_early`, `vista_water`; storage buffers 21-26; samplers `vistaDepthTex0/1`, `vistaRealDepth`, `vistaLoaded`,
  `vistaFade`; macro `VISTA`; 19 `vista*` uniforms; P3's `lod_vista` and `lod_vista_water` slots. The names keep "vista" on
  purpose: they are Navidson's interface. Iroh's pack loader calls the LOD provider directly (internal, no
  `registerProgramProvider` api needed). Navidson edits still pending owner approval: `vista_water` reads depthtex1, mountain
  fog `|| defined VISTA`.
- **Ray tracing**: v1 raster-only behind the RT image (P5-DESIGN). VL-14 adds far terrain to RT: one BLAS per 512-block
  tile of L2-L4 surface quads, built by compute from the quad heap when a tile's sections change, inside the `iroh-rt` share;
  rays past the near residency radius hit the LOD BLAS for shadows and sky visibility. Raster LOD stays the primary image.

### 2.6 Threading

| thread | count | priority (native, from Rust) | work |
|---|---|---|---|
| iroh-lod-worker | 2 .. budget | low (nice 10 / BELOW_NORMAL) | server capture batches, cascade, GapFill |
| iroh-lod-seed | permits from the budget | idle (SCHED_IDLE / IDLE) | seed columns, biome grid, structure pass |
| iroh-lod-residency | 1 | low | plan + Rust read into upload slices |
| iroh-lod-net | 1 server, 1 client | normal-1 | protocol v5 |

- Budget: `lod.workerThreads` (auto = max(2, physical cores / 6), as Vista's `CPU_THREADS`) running tasks across all LOD pools.
- Iroh's builders, `Iroh-VisGraph` (P2-6) and `Iroh-AreaArena` (P2-4) are unchanged; LOD threads never run on the render core
  when Iroh's ThreadPolicy keeps builders off it (same core-picking rule). IrohThreads `phase()` gates the seed pool: no seed
  work during LOADING (world open speed, DECISIONS "Load, world-gen and startup speed").
- elemore-core's ThreadTuner keeps working: it learns the `iroh-lod-` prefix (VL-11).
- Render thread work per frame: intake of ready slices (bounded by `lod.uploadMiBPerFrame`), ~10 dispatches/draws, no
  per-section CPU work, no readback.

### 2.7 Memory budget

- `MemoryShares` gets an explicit `lod` row (replaces the generic api-4 40 % owner share for this feature): default 35 % of
  Iroh's cap, and the user setting `lod.budgetMiB` (0 = auto) caps it further. Auto = min(share, driver budget headroom from
  `VK_EXT_memory_budget` (P2-12 MemoryBudget) minus 1.5 GiB).
- Distant Terrain is the **elastic** owner: on MemoryGuard pressure (85 % WARN) or a driver-budget drop, LodRenderer evicts
  coarsest/farthest sections down to a target before any other owner is refused. This closes RQ-INT4-b for this feature
  (api-4 owners never asked MemoryGuard.allowOptional).
- The rung ladder from VkLods stays for startup (a refused rung frees what it took and tries smaller heaps/levels).
- CPU side: no `byte[]` per section on the read path (Rust writes into upload slices); ready queue capped at 64 MiB as now.
- Safety: Proctor bounds move from `vista.json` to `iroh.json` keys `lod.budgetMiB` (<= 2048), `lod.distance` (<= 2048),
  `lod.detailDistance` (<= 1024) in clones (VL-11).

### 2.8 Save format and migration

- Format unchanged: VKP1 v2 files, `blocks.dat`, `biomes.dat`, `visited.bin`, `structures.bin`, `L4.built`, `server-id`, `seed/`.
- Location: Iroh reads and writes `<world>/vista/` in place (no copy, no rename) so a world can go back to the Vista mod
  during the transition. Client cache: `<gameDir>/vista-cache/` is read in place the same way; new client caches (servers
  never seen before) also go there until VL-15. A rename to `<world>/iroh-lod/` is a later optional card after Vista is
  retired, with a one-line marker file and the rename atomic on the same filesystem.
- Both mods present: Iroh turns Distant Terrain off and says why in the tab and the log (two writers on one store would
  corrupt it). Fabric `provides: ["vista"]` is NOT declared (it would block A/B installs and fool Tempest's Vista check).
- Version 1 stores still upgrade in the background (Vista's `storeUpgrade`), unchanged.

### 2.9 Network, multiplayer and the dedicated-server question

- Protocol v5 on `iroh_lod:*` channels, same message set as Vista 4. An Iroh client on a Vista server, or a Vista client on
  an Iroh server, simply sees no LOD server and falls back to client-only capture + seed LODs where possible.
- Iroh is `environment: "client"` today and the load-speed decision says "a dedicated server has no Iroh". Vista's server
  half has to run somewhere. Recommendation (Q1): Iroh becomes `environment: "*"` with a `main` entrypoint that starts only
  `LodServer` (no Vulkan/LWJGL class touched), a common mixin config for the two server mixins (LevelChunk change marks,
  RandomState factory), and every existing mixin config marked client-only. Singleplayer and LAN hosting work either way
  (the integrated server runs in the client JVM); only a dedicated server needs the jar on the server. VL-3 proves the
  server side with a headless dedicated server under the orchestrator's hashworld rules (no client, no GPU).
- Far players: beacons at 4 Hz from the server half, figures drawn by the renderer (VL-8).

### 2.10 Branch placement

- The renderer needs api 4.1-level `gpu` internals (sampled textures, runOutsideMainPass, main tokens, IrohProfile): present
  on int-3b and int-4c, absent on int-2b (API_MINOR 0). `MemoryShares` is on int-4c only.
- Recommendation: a feature line `integration/lod` cut from int-3b (it also has the pack loader for VL-9). Data-plane cards
  (VL-0..VL-5) are new files and merge anywhere; the renderer cards follow 3b and are carried to 4c at the next 3b -> 4c sync.
  If the orchestrator wants LOD in a shaders-off install from the int-2b line first, the 4.1 gpu-layer back-port to 2b is its
  own card (17 files, +1304/-195 lines between the lines' api/gpu packages).

## 3. Settings: the "Distant Terrain" tab

Stored in `config/iroh.json` under `lod`; server options in `iroh.json` `lod.server` (integrated server) and
`config/iroh-lod-server.json` (dedicated server). Rows follow settings.md conventions (tooltip, reload cost, unavailable
reason). "Pack" = the value the pack migration produces from today's `config/vista.json`.

| id | group | type / range | default | pack | controls |
|---|---|---|---|---|---|
| lod.enabled | General | bool | true | true | the whole feature; off frees every LOD GPU object and stops LOD threads (no restart) |
| lod.distance | General | 256..12288 blocks, step 256 | 4096 | 12288 | how far LODs reach |
| lod.detailDistance | Detail | 256..2048 blocks | 800 | 800 | full-detail (L0) radius; each doubling past it halves detail (L1..L4) |
| lod.blendWidth | Detail | 0..96 blocks | 0 | 10 | dithered blend band between levels; 0 = clean switch |
| lod.transitionMs | Detail | 0..1500 ms | 400 | 800 | fade of a real section over its LOD |
| lod.landmarks | Detail | bool | true | true | landmarks (citadels, listed places) kept at higher detail |
| lod.brightness | Look (shaders off) | 50..150 % | 100 | 85 | LOD brightness |
| lod.saturation | Look (shaders off) | 0..150 % | 100 | 80 | LOD colour saturation |
| lod.fogDistance | Look (shaders off) | 50..300 % of distance | 100 | 100 | where distance fog ends |
| lod.nightLights | Look | bool | true | true | lit blocks glow far away at night |
| lod.motion | Look | bool | true | true | leaf sway and water ripple on LODs |
| lod.weatherOnLods | Weather & players | bool (needs Tempest) | true | true | storms, snow, wet, dust, gloom, lightning on LODs (shaders off) |
| lod.weatherCurtains | Weather & players | bool (needs Tempest) | true | true | rain/snow/sand/lava curtains over far storms (shaders off) |
| lod.farPlayers | Weather & players | bool | true | true | other players beyond entity range as figures |
| lod.farPlayerMinPixels | Weather & players | 0..12 px | 4 | 4 | minimum figure height; 0 = true size |
| lod.nameTagDistance | Weather & players | 0..512 blocks | 128 | 128 | name tags over far figures |
| lod.seedLods | World data | bool | true | true | far terrain generated from the world generator before it is visited |
| lod.captureVisited | World data | bool | true | (new) | on servers without Iroh LOD: keep LODs of chunks you have seen in a local cache |
| lod.shareWithMaps | World data | bool (shown when a map bridge is registered) | true | true (xaeroFill) | lets map mods' bridges read LOD terrain through LodApi |
| lod.budgetMiB | Memory & performance | 0 = auto, 512..8192 MiB step 256 | 0 | 0 | VRAM cap for LODs inside Iroh's LOD share; auto = section 2.7 |
| lod.occlusionCulling | Memory & performance | bool | true | true | Hi-Z test (skip LODs hidden behind terrain) |
| lod.workerThreads | Memory & performance | 0 = auto, 1..8 | 0 | 0 (was -Dvista.cpuThreads) | background threads for capture, cascade and seed work |
| lod.uploadMiBPerFrame | Advanced | 1..32 MiB | 6 | 6 | per-frame upload cap (Vista's 6 MiB / 1.2 ms rule) |
| lod.server.structurePass | Server (this world) | bool | true | true | generate surface structures ahead so LODs show them |
| lod.server.structureRange | Server (this world) | 0..8192 blocks | 4096 | 4096 | how far ahead |
| lod.server.shareSeedTerrain | Server (this world) | bool | true | true | send the worldgen bundle so clients can make seed LODs |
| lod.server.sharePlayerPositions | Server (this world) | bool | true | true | far-player beacons |
| lod.debugLevels | Advanced | bool | false | false | colour LODs by level |
| lod.debugFreeze | Advanced | bool | false | false | freeze residency (debugging pop-in) |

Shown read-only in the tab: GPU memory used / budget, resident sections per level, seed columns per second, native library
state (Rust / Java fallback + reason), and "Distant Terrain is off: the Vista mod is installed" when both are present.

Migration (`LodMigration`, first run with no `lod` section, files never edited): every `vista.json` key maps 1:1 to the row
above (`xaeroFill` -> `lod.shareWithMaps`); `vista-server.json` `structurePass`, `structureRange`, `shareSeedTerrain`,
`sharePlayerPositions` -> `lod.server.*`; `landmarks` list -> `config/iroh-lod-server.json`; `citadelLandmarks` is dropped
with a log line (the citadels provider owns it); `-Dvista.cpuThreads` -> `lod.workerThreads` once. Values are clamped to the
ranges above; one log line per changed key. Test `lodConfigTest`: the pack's two files produce exactly the "pack" column.

## 4. Retiring the Vista mod

| coupling | today | after |
|---|---|---|
| Vulkan instance | `vista-0.1.0.jar` in mods | VL-15: jar to `disabledmods/`, iroh.json migrated, SESSION_CHANGES entry; the OpenGL main pack keeps Vista until it moves to Iroh |
| Proctor | `bounds.py` bounds vista.json; scenarios i8-vista-* | bounds iroh.json `lod.*`; scenarios i8-lod-*; Vista bounds kept for GL clones |
| Navidson | `vista_*` programs, `VISTA` macro, lib/vista | unchanged (Iroh keeps the contract names) |
| Tempest | `VISTA_READS_MAPS` when Vista + no Iroh; `irohConsumers()` under Iroh | `irohConsumers()` counts Distant Terrain weather (VL-11, Tempest branch) |
| elemore-core ThreadTuner | `vista-` prefix -> VISTA role | add `iroh-lod-` prefix to the same role (VL-11) |
| citadels | read by Vista's landmark loader | citadels registers a landmark provider through LodApi (VL-11) |
| Chunksmith | "no LOD renderer detected" without Voxy/DH; Vista mixes into `LodSupport.sinkFor` | not needed for LODs (Iroh's capture sees pregen chunks). Optional bridge only after Q2 |
| Xaero's World Map | filled by Vista's bridge (5 mixins) | owner-mod bridge on LodApi after Q3; until then no Xaero fill from far terrain |
| Other mods detecting Voxy/DH/Vista | Chunksmith (voxy/DH ids). Full scan not done | VL-11 scans every pack jar for `isModLoaded("vista"|"voxy"|"distanthorizons")` and DH API classes; any hit gets a row in pack-compat.md |
| Vista repo | `master` (GL) + `iroh-port` | archived read-only with a README pointer to Iroh; no new Vista releases; it is not pushed publicly (DECISIONS GitHub backups) |
| Worlds | `<world>/vista/` | read in place, no migration step for the player |
| Multiplayer | Vista server + Vista clients | server and clients switch together (protocol v5); mixed = client-only capture |

Switch-over order for the pack: (1) Iroh with Distant Terrain off ships while Vista runs (no change); (2) clone A/B: Vista
on Iroh (m31) vs Distant Terrain, same world copy; (3) VL-12 parity + perf pass; (4) VL-15 in the Vulkan instance.

## 5. Cards

Routing: Sonnet by default; Opus where the skill's rule says (Vulkan pipeline internals, concurrency, shader translation,
upscaler/RT integration). Every card: branch `card/VL-n` off `integration/lod`, fast checks only (DECISIONS "Agent waiting"),
`build-windows.sh --jobs 3` for anything under iroh-native or `*Native.java`, Gemini/Claude review before merge, no launches.
Estimates are agent-hours of card work, not counting review and queueing.

| card | model | owns | does | offline acceptance | in-game (batched) | est |
|---|---|---|---|---|---|---|
| VL-0 | S | `lod/LodFeature`, `lod/config/**`, `assets/iroh/lang/en_us.json`, `gradle/lod.gradle`; one line each in IrohOptions, Options, Initializer, build.gradle | skeleton, tab with all rows (greyed "not built yet" where the backend is missing), migration, Vista-jar detection | `lodConfigTest`: pack files -> "pack" column, clamps, round trip; `irohConfigTest` still green; injector_scan 0 | none | 4 h |
| VL-1 | S | `iroh-native/lod/**`, `build-lod.sh`, the lod step in `build-windows.sh`, `lod/nat/**` | crate move, rename, ABI 4, `seed` feature, LGPL headers + NOTICE, priority calls, loader | `cargo test --release`; NativeParityTest + PackFileTest ported (Java == Rust); Windows cross build + import allow-list + Wine export smoke; jar contains both natives | none | 5 h |
| VL-2 | S | `lod/data/**`, `lod/store/**` | data model + store facade + Java fallback, in-place paths | store tests ported; read-only reflink copy of an owner world store and of vista-cache: every section decodes, index hash == recomputed hash, Java == native byte-identical | none | 4 h |
| VL-3 | S (escalate after 2 fails) | `lod/server/**`, `lod/net/**`, `lod/threads/**`, `fabric.mod.json`, `iroh-lod.common.mixins.json`, the two server mixins | server half, Rust ingest batch, protocol v5, beacons, environment change (if Q1 = yes) | ingest parity: Vista's Java ingest vs new path on fixture chunks, identical cells/hashes; headless dedicated server (hashworld rules) boots with the jar, loads no `org.lwjgl.vulkan` / `net.vulkanmod` class, fills a store; protocol round-trip unit test | LAN guest later in VL-12 | 10 h |
| VL-4 | S | `lod/seed/**` | seed planner + Java/Rust columns, worldgen bundle, C2ME traps | TerrainParityTest, PackGraphTest on the pack graph (Larion in libs for tests), seed batch v3 regenerated identical to Vista's (same hashes) | seed rate line in VL-12 | 6 h |
| VL-5 | O | `lod/client/Residency*`, `lod/client/LodClient`, `memory/MemoryShares` (lod row), MemoryGuard pressure callback | zero-copy residency into upload slices, eviction, elastic budget, JNI vs FFM A/B | residency simulator (stand, glide, teleport, dimension change): identical resident sets to Vista's Residency; JFR: 0 `byte[]` allocations per section on the read path; HeadlessGpuDevice budget tests (pressure -> eviction before refusal, ladder); memorySharesTest extended; FFM/JNI numbers written to knowledge | memStats LOD line in VL-12 | 8 h |
| VL-6a | O | `gpu/game/MainDepth`, api-4 pipeline depth-format plumbing (one commit, scheduled when no P2 card owns GpuCore) | RQ-VDES-a option (b): main depth read through a depth-only view, pipelines built for the real format | hw-matrix: the depth path runs (not SKIP) on NVIDIA/Intel profiles; apiReplay with a D24S8 main depth; GPU-AV + sync validation clean | none | 3 h |
| VL-6 | O | `lod/render/**` (minus Coverage/Figures/Weather), `assets/iroh/shaders/lod/**` | renderer core on the gpu layer: heaps, programs, mesh/alloc/copy, cull A/B, Hi-Z, draws, water, composite, jittered projection, render-scale phase | ported gpuMeshTest / gpuLodTest / gpuLodFrameTest on lavapipe: goldens byte-identical to Vista iroh-port's on the same batch (P1/P2 @2048/4096, frames 0/10/19), equivalences (cull on/off, prefill on/off) 0 px; vkval sync + GPU-AV clean; hw-matrix 17 profiles PASS/SKIP with tier printed; memory flat over 20 frames, 0 bytes after close | VL-12 L1 | 12 h |
| VL-7 | S | `lod/render/Coverage*`, the one call from Iroh's section intake | hand-off, fades, crossfades | hand-over replay: 0 sky pixels where terrain must show, with `iroh.terrain.mdi` on/off and `iroh.graph.async` on/off; fade timing test | VL-12 L1/L2 (poptest) | 5 h |
| VL-8 | S | `lod/render/Figures*`, `lod/render/Weather*`, zoom, pins | far figures, night lights, motion, Tempest weather + curtains (IrohTextures externals), spyglass stages, landmark pins | lavapipe goldens per feature (figure, curtain, snow, night light); Tempest fixture maps | VL-12 L2 | 8 h |
| VL-9 | O | the LOD provider in `shaderpack/**` (coordinated with the Phase 3 owner of those files), standard-depth Hi-Z variant | Navidson path: programs, bindings 21-26, samplers, macro, both slots | packHarness compiles Navidson's vista_* units; packReplay with a LOD batch: golden stable twice; packFlightRepro; vkval clean | VL-12 L4 | 8 h |
| VL-10 | S | `lod/debug/**` | F3 + memStats lines, commands, IrohProfile pass timings, Proctor busy-fps hook | command unit tests; profile line format test | used by VL-12 | 4 h |
| VL-11 | S | branches in tempest, elemore-core, proctor, citadels | Tempest consumer flag, ThreadTable prefix, bounds.py + i8-lod scenarios, landmark provider; pack-jar detection scan; bridges only after Q2/Q3 | each repo's own tests (ThreadTableTest, test_bounds.py, Tempest build); scan output in pack-compat.md | VL-12 | 6 h |
| VL-12 | P | Proctor runs only | ladder L0 inert (off == no-LOD jar), L1 vanilla on (bounded), L2 pack parity vs gl2 (far terrain) + 0 holes + seed rate, L3 perf: busy fps / 1 % low vs Vista-on-Iroh m31 and vs GL+Vista, L4 Navidson on, L5 LAN guest (far players, client sync) | n/a | guarded, one game, bounded clone config, keep-going mode; ~8 launches per round | 6 h |
| VL-13 | O | LOD side of the FSR inputs; direct main-target path behind Q10 | jitter check, reactive bits, reversed-Z direct draw when Q10 lands | FSR harness (FSR-Q Q2) far-terrain scene: edge RMSE / flicker not worse than near terrain; depth goldens per path | FSR ladder | 6 h |
| VL-14 | O | `lod/render/rt/**`, coordinated with RT-PLAN owners | far terrain BLAS tiles for RT shadows / sky visibility (P9-2) | rtReplay-style lavapipe goldens; AS memory inside iroh-rt share; GPU-AV clean | RT ladder | 12 h |
| VL-15 | S | instance files, OUTLINE (P9 cards replaced by VL), SESSION_CHANGES | switch-over and Vista archive | install check script (sha, Vista jar in disabledmods, iroh.json lod section present) | first owner session | 2 h |

Total: about 109 agent-hours. Parity + switch-over set (VL-0..VL-12, VL-6a, VL-15): about 91 h. Optional VL-16 [S/O]
(after P2-7c): LOD cull B reads P2-7c's shared Hi-Z pyramid, about 4 h. With one Sonnet and one
Opus lane in parallel, about 6 to 8 working days of card time plus the in-game batches (single guarded game slot), so about 2
weeks elapsed to the switch-over; VL-13 and VL-14 land with the FSR-Q and RT tracks (U: calendar depends on Opus slots).

Dependencies: VL-0 -> VL-1 -> VL-2 -> {VL-3, VL-4, VL-5}; VL-6a -> VL-6 (VL-6 also needs VL-5); VL-6 -> {VL-7, VL-8, VL-9,
VL-13, VL-14}; VL-3 + VL-4 + VL-7 -> VL-12 L1/L2; VL-9 -> VL-12 L4; VL-10 any time after VL-6; VL-11 after VL-3; VL-12 -> VL-15.

## 6. Risks, critical path, parallel work

Critical path: VL-0 -> VL-1 -> VL-2 -> VL-5 -> VL-6 -> VL-7 -> VL-12 (L1/L2) -> VL-9 -> VL-12 (L4) -> VL-15 (about 55
agent-hours serial). VL-3 and VL-4 run beside VL-5; VL-6a beside VL-1..VL-5.

| risk | likelihood | effect | mitigation |
|---|---|---|---|
| Licence answers (Q2-Q4) come back "no" | medium | seed Rust held back; no Xaero fill; no Chunksmith sink | design isolates them: cargo feature `seed` off = Java seed path; bridges live outside Iroh |
| Dedicated server (Q1) declined | low-medium | no server half on dedicated servers (no visited-chunk sharing, no far players there) | client-only capture + seed LODs where the server shares the bundle; singleplayer/LAN unaffected |
| D24S8 on NVIDIA/Intel (RQ-VDES-a) | certain today | LODs off on those GPUs | VL-6a before VL-6; hw-matrix check; NVIDIA/Intel stay "unverified in game" until a real card is available |
| VRAM at 12288 on Vulkan unknown | medium | pressure, possible guard kills | elastic owner + driver budget; clone bounds; L3 measures the owner's 12288 setting only after the bounded rungs pass |
| Branch divergence (4.1 not on 2b) | certain | LOD cannot ride an int-2b install | feature line off 3b; orchestrator decides the 2b back-port |
| Hand-off timing with P2-6 async graph / P2-7a arena | low | one-frame holes | coverage from "built" events, VL-7 replays with switches on/off |
| Hi-Z duplication with P2-7c | certain, small | ~0.05-0.1 ms extra GPU (U) | VL-16 shares the pyramid after P2-7c |
| FSR shimmer on far terrain | medium | visible crawl at distance | jittered projection, depth written, reactive bits; VL-13 harness |
| Perf vs Vista on GL | unknown | LOD costs more than on GL | per-pass IrohProfile timings; draw A (fragment-bound) dominates and scales with render scale, which FSR lowers |
| Owner-visible behaviour drift (looks different from Vista GL) | medium | parity complaints | same shaders and goldens; L2 parity vs gl2 baselines (which include Vista far terrain) |
| Opus slot contention with RT | high | calendar slip | only VL-5, VL-6, VL-6a, VL-9, VL-13, VL-14 are Opus; the Sonnet lane runs VL-0..VL-4, VL-7, VL-8, VL-10, VL-11 |

Running in parallel with Phase 2 and RT without file conflicts:
- New files only for most cards: `dev.elemore.iroh.lod.**`, `dev.elemore.iroh.api.lod.**`, `iroh-native/lod/**`,
  `assets/iroh/shaders/lod/**`, `assets/iroh/lang/**`, `gradle/lod.gradle`.
- Shared files, each owned by exactly one VL card and scheduled when no other running card owns it: `IrohOptions.java` and
  `Options.java` (VL-0, one line each; the FSR-Q settings cards also edit them, so not at the same time), `build-windows.sh`
  (VL-1; native cards), `fabric.mod.json` (VL-3), `MemoryShares.java` / `MemoryGuard.java` (VL-5; RT cards also touch shares),
  `gpu/game/MainDepth.java` + gpu pipeline plumbing (VL-6a; P2-7b/7c and US/P2-12 follow-ups touch GpuCore), `shaderpack/**`
  provider hook (VL-9; Phase 3 cards on int-3b), Iroh's section intake call site (VL-7; P2-4/P2-6/P2-7a own that area, so it
  goes in after P2-7a/P2-6 settle).
- No VL card touches the terrain mesher, the frame loop, sync/barrier code or device creation, so VL cards do not trigger
  the "device creation / feature chain" heavy-gate exception, except VL-6a (run hw-matrix on that card).

## 7. Questions for the owner (Q1 decides architecture; Q2-Q4 are legal and block the public push of those parts only)

- **Q1 Dedicated servers.** Iroh is a client-only mod today. Vista's server half (visited-chunk store shared with everyone,
  far players, structures ahead, seed bundle) must run on the server. Allow the Iroh jar to load on a dedicated server with
  only the LOD server part active (recommended), or keep Iroh client-only and accept client-only LODs on dedicated servers?
- **Q2 Chunksmith (GPLv3).** Vista compiles against Chunksmith and mixes into it. Inside LGPL Iroh that is a licence
  question. Recommendation: drop it from Iroh (Iroh's own capture already sees pregen chunks); if wanted, a bridge in an
  owner mod whose licence allows GPL linking.
- **Q3 Xaero's World Map (All Rights Reserved).** Same question for the map fill (compile-against + mixins). Recommendation:
  bridge outside Iroh on the public LodApi, owner decides which mod carries it.
- **Q4 Rust ports of Minecraft code.** `noise.rs`, `density.rs`, `jmath.rs`, `column.rs` reproduce Minecraft's noise and
  NoiseChunk logic bit for bit (written from reading the game's code). Public release of those files is a legal call.
  Recommendation: ship them only with the owner's explicit OK; without it the release build turns the `seed` feature off
  and the Java seed path (which calls Minecraft's own classes) is used, about 40x slower for L2-L4 columns.
- Q5 (from IROH-PORT, still open): approve the two Navidson edits (`vista_water` reads depthtex1; mountain fog
  `|| defined VISTA`).
