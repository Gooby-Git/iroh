# P4-PLAN: upscaling (Phase 4) and frame generation (Phase 8) breakdown

Card P4-0, 2026-11-22. Facts and the full API contract: `knowledge/fsr.md` section A (skill folder). Docs-only branch.

## Verdict
- FSR 3.1 upscaling on Linux needs **no Windows tool and no licence exception**. AMD SDK v1.1.4 (MIT, commit c6efa6bf) still has native Vulkan backend source with GLSL shaders; its Windows-only shader generator is replaced by a 150-line script around system `glslangValidator`. Built offline (compile+link only): `libffx_fsr3_vk.so` 4.6 MB with upscaler + frame interpolation + optical flow, no undefined symbols, 2968 SPIR-V blobs validated. Patch set vs pristine SDK: 3 files, 70 lines (context-size guard for Linux 4-byte `wchar_t`; two FSR 3.1.4 -> 3.1.6 shader back-ports). Version delivered is 3.1.4 host + 3.1.6 shaders (host deltas not fully audited).
- Binding: JNI shim (Java 21 has no final FFM; LWJGL has no FFX bindings), packaged like Vista's native lib (`natives/<os>-<arch>/`, extract + `System.load`).
- Frame generation: the SDK's swapchain proxy is Win32-only; the interpolation + optical-flow components are not, and build on Linux. Own present/pacing needed; gamescope behaviour UNVERIFIED.
- FSR 4 stays Windows-only (signed DLLs only); DLSS unchanged (UNTESTED label). One `Upscaler` interface serves all three.

## Phase 4 cards (revised)
| card | who | content | offline proof (no game) |
|---|---|---|---|
| P4-1a [O] | opus | Render-scale SceneTarget + display-size output, HUD after upscale, jitter in projection (all geometry), mip bias, `Upscaler` interface + bilinear null path, enable device features (`shaderStorageImageExtendedFormats`, `...WriteWithoutFormat`, subgroup, optional float16/int16) | unit tests: jitter sequence = SDK values, projection offset math, scale 1.0 == parity (existing orientation/parity tests) |
| P4-1b [O] | opus | Motion vectors: terrain (prev VP + camera delta), sky, hand, entities (per-entity previous matrix), reactive + T&C masks, debug views for every input | replay harness frame pair on lavapipe: MV of a static scene under a known camera move equals the analytic value (golden, tolerance); MV debug PNGs |
| P4-2a [S] | sonnet | Move `build-fsr` recipe into `iroh-native/` (CMake + generator + shim + patch file), CI-less script `./build-native.sh`, NOTICE + licence text, jar packaging + loader (Vista pattern), Windows mingw cross build (link step UNVERIFIED) | build script exit 0 on Linux; SPIR-V validation; `nm`/`ldd -r` check; mingw objects compile |
| P4-2b [O] | opus | JNI shim (`create/dispatch/destroy/jitter`), Java `FsrUpscaler`, resource states/layouts, memory-guard accounting, error/message callback | **lavapipe golden test**: one dispatch on a 96x54 -> 144x81 synthetic frame pair, compare to a pinned output with tolerance (proves bindings, barriers, descriptor layout, no validation errors); non-16bit variant first because lavapipe subgroup/float16 support is UNVERIFIED. Needs the validation layers package (owner sudo, tools.md) |
| P4-3 [O] | opus | FSR 4 via signed loader on Windows only (unchanged) | none offline; capability probe unit test |
| P4-4 [O] | opus | DLSS SR/DLAA NGX adapter behind the same interface, self-test command | none (UNTESTED); mock adapter test of the interface contract |
| P4-5/6 [S] | sonnet | other-vendor = FSR 3.1; vendor binary fetcher | unit tests |
| P4-8 [O] | opus | Pack tiers: Navidson cut (`iroh.upscale.after=composite5`, depth+prev-matrix MVs, TAA off option), undeclared packs, fallback | replay harness: Navidson passes up to composite5 on lavapipe, MV pass from pack depth |
| P4-7 [P] | proctor | stills + motion captures, ghost/shimmer list | Proctor only (ask owner), taste call |
Order: P4-1a, P4-2a (parallel), P4-1b, P4-2b, P4-8; Gate 4 needs one guarded Proctor run for FSR on raster and Navidson.

## Phase 8 cards (revised)
| card | who | content | offline proof |
|---|---|---|---|
| P8-0 [S] | sonnet | Extend the native build with FI + optical flow (already compiles), shared-resource plumbing from the upscaler | build + SPIR-V validation (done for blobs) |
| P8-1a [O] | opus | Optical flow + interpolation dispatch with HUD-less colour, shared dilated depth/MVs; HUD composited on both images | lavapipe golden: interpolated image of a pure-translation pair matches the analytic half-step within tolerance |
| P8-1b [O] | opus | Own present path: two acquires/presents per frame, pacing state machine, dedicated present thread, latency measure; Wayland/gamescope timing probe | none offline (present behaviour is driver/compositor); one guarded Proctor run |
| P8-2 [O] | opus | DLSS FG, UNTESTED | none |
| P8-3 [S] | sonnet | `VK_AMD_anti_lag` / Reflex | extension-gate unit tests |
| P8-4 [P] | proctor | latency + pacing, combat feel | Proctor |
FSR 4 frame generation: Windows signed DLL path only.

## Owner questions
1. C++ shim built by CMake, or a Rust crate (Vista pattern) whose `build.rs` compiles the SDK? (Recommendation: C++ shim; same jar layout either way.)
2. Move Iroh depth to reversed/infinite-far (AMD's strong recommendation, helps RT) or keep standard depth and give FSR a larger near plane?
3. Accept "3.1.4 host + 3.1.6 shaders" label, or require a full host audit before shipping as "3.1.6"?
