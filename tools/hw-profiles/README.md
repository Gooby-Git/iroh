# Hardware profiles for the offline matrix (card H-1, 2026-11-23)

Real GPU capability profiles (gpuinfo.org device reports, shipped in KhronosGroup/Vulkan-Profiles tag vulkan-sdk-1.4.363.0, `profiles/LunarG/*`) for the
Khronos Profiles layer (`VK_LAYER_KHRONOS_profiles`) running on lavapipe. Rebuild everything with `tools/hw-profiles-build.sh`; run with `tools/hw-matrix.sh`.
gpuinfo.org has no login-free JSON export (the API URLs 404), so no profile was downloaded; Ampere (RTX 30xx) is not in the Khronos set and is HAND-MADE.

| file | vendor / generation | API in profile | RT | limits maxImage2D / pushConst / sharedMem / maxPerStageSamplers | source report |
|---|---|---|---|---|---|
| nv-turing-rtx2060 | NVIDIA Turing RTX 2060 | 1.3 | yes (query + pipeline) | 32768/256/49152/1048576 | Windows 11, 537.59 |
| nv-ampere-hand | NVIDIA Ampere-class (HAND-MADE: RTX 4070 minus VK_EXT_opacity_micromap and VK_NV_ray_tracing_invocation_reorder) | 1.3 | yes | same as Ada | derived |
| nv-ada-rtx4070 | NVIDIA Ada RTX 4070 | 1.3 | yes | 32768/256/49152/1048576 | Windows 11, 551.61 |
| nv-blackwell-rtx5080 | NVIDIA Blackwell RTX 5080 | 1.4 | yes | 32768/256/49152/1048576 | Windows 11, 610.62 |
| nv-gtx1650-no-rt | NVIDIA GTX 16xx (Turing, no RT) | 1.4 | no | 32768/256/49152/1048576 | Windows 10, 610.62 |
| nv-pascal-gtx1060 | NVIDIA Pascal GTX 1060 | 1.3 | NO ray query, but exposes VK_KHR_acceleration_structure + ray_tracing_pipeline (driver emulation): a trap for "has the extension = has RT" | 32768/256/49152/1048576 | Windows 10, 537.13 |
| amd-rdna1-rx5700xt-radv | AMD RDNA1 RX 5700 XT (RADV) | 1.4 | no | 16384/256/65536/8388606 | Arch, Mesa 26.0 |
| amd-rdna2-rx6600-radv | AMD RDNA2 RX 6600 (RADV) | 1.3 | yes | 16384/256/65536/8388606 | EndeavourOS, Mesa 23.2 |
| amd-rdna3-rx7800xt-radv | AMD RDNA3 RX 7800 XT (RADV) | 1.3 | yes | 16384/256/65536/8388606 | Arch, Mesa 24.0 |
| amd-rdna4-rx9070xt-radv | AMD RDNA4 RX 9070 XT (RADV) | 1.4 | yes | 32768/256/65536/8388606 | CachyOS, Mesa 26.1 |
| amd-polaris-rx580-radv | AMD GCN Polaris RX 580 (RADV) | 1.3 | no | 16384/256/65536/8388606 | Arch, Mesa 23.1 |
| intel-arc-a770-anv | Intel Arc A-series A770 (ANV) | 1.4 | yes | 16384/256/49152/67108864 | Arch, Mesa 26.1 |
| intel-arc-a380-anv | Intel Arc A380 (ANV) | 1.4 | yes | same | openSUSE, Mesa 26.1 |
| intel-arc-b580-win | Intel Arc B-series B580 (Windows) | 1.4 | yes | 16384/256/49152/33554432 | Windows 10 |
| intel-xe-tgl-igpu-anv | Intel Xe iGPU (Tiger Lake, ANV) | 1.4 | no | 16384/256/49152/402653184 | Void, Mesa 26.1 |
| intel-uhd620-gen9-anv | Intel Gen9.5 UHD 620 (ANV) | 1.3 | no | 16384/128/65536/65535 | Arch, Mesa 23.1 |
| intel-hd520-skl-vk12-lowend | low-end Vulkan 1.2 baseline (Intel HD 520 Skylake) | 1.2 | no | 16384/128/65536/65535 (maxBoundDescriptorSets 8) | Arch, Mesa 21.1 |

## How the profiles are made usable on lavapipe (what the layer cannot do)
The layer only simulates; it cannot add capabilities. Applied by `hw-profiles-build.sh`:
1. `VkPhysicalDeviceHostImageCopyProperties*` removed: its `pCopySrcLayouts` holds numeric enum values and the layer's JSON loader throws `Json::LogicError` (abort, rc 134).
2. Every feature the layer warns "enabled in the profile, but the device does not support it" is set false (otherwise `vkCreateDevice` on lavapipe fails -8 with the profile's true value, e.g. `inheritedQueries`).
3. Queue-family simulation is OFF (lavapipe has one family; a simulated multi-family layout cannot be backed). Dedicated-queue paths (async compute / transfer) are therefore NOT covered.
4. Limits larger than lavapipe's are simulated as given; the layer cannot enforce them, so only code that READS limits is covered (that is the point: overflow / assumption bugs).
5. Profile extension lists hide extensions the profile lacks (the main fallback test); extensions lavapipe lacks stay absent.
`vulkaninfo` segfaults after enumerating a profiled device (tool bug, not the layer); use a harness or `tools/hw-profiles/../hw-matrix.sh`.
