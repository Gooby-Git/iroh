# Iroh

A Vulkan renderer for Minecraft 1.21.1 on Fabric, forked from **[VulkanMod](https://github.com/xCollateral/VulkanMod)**.

> **Credit where it is due:** Iroh is built on VulkanMod by **xCollateral and contributors**. The
> renderer core, the chunk pipeline and most of the Vulkan plumbing are theirs. Iroh forked from
> VulkanMod tag `0.5.5` (Minecraft 1.21.1) and keeps the upstream `net.vulkanmod.*` packages so that
> upstream changes can still be merged. New Iroh code lives under `dev.elemore.iroh`. Please support the
> original project: <https://github.com/xCollateral/VulkanMod>.

Iroh is being developed for the **Elemore** modpack, where it is meant to replace both Sodium and Iris.
It is a work in progress and has no public release yet.

## What it is for

- Replace Sodium and Iris in the Elemore pack with one Vulkan renderer.
- Run the pack's own shader, [Navidson](https://github.com/Gooby-Git/navidson), through an Iris-format
  shader-pack loader on Vulkan (`dev.elemore.iroh.shaderpack`).
- Add upscaling (FSR 3.1 first; in progress) and ray tracing (planned, not working yet).

## What exists in the code today

This list only names things that are present in the source tree; it is not a claim that all of it is finished.

- VulkanMod's renderer, kept and tuned (device, memory, pipelines, chunk meshing, drawing).
- A GL compatibility layer (`glshim`) that translates the GL / GlStateManager calls other mods make into Vulkan state.
- Compatibility shims for third-party mods (for example WaterMedia and Satin), written as separate mixin packages.
- An Iris-format shader-pack loader: parsing, GLSL to SPIR-V, pass graph and runtime.
- GPU memory guarding and diagnostics (`memory`, `gpu`, `diag`), and offline Vulkan test harnesses.
- Upscaler scaffolding (`upscale`, `upscale/fsr`), temporal passes (`temporal`) and a denoiser interface with an NRD binding (`rt/denoise`).
- A small public API (`api`) for the pack's own mods.

Ray tracing and FSR 3.1 are in progress. DLSS is planned for NVIDIA cards. See `docs/` for the plan.

## Requirements

- Minecraft **1.21.1**, Fabric Loader 0.16.10 or newer, Fabric API.
- **Java 25** (the build targets Java 25 bytecode).
- A GPU and driver with Vulkan 1.2.

## Install

There is no release yet. Build the jar (below) and put the jar from `build/libs/` into the `mods` folder of a Fabric 1.21.1 instance.
Remove Sodium and Iris from that instance first; Iroh replaces them.

## Build

```sh
./build.sh
```

This runs Gradle with JDK 25. Offline test tasks are in `build.gradle`; they do not need a game window.

## Licence

LGPL-3.0-only, the same licence as VulkanMod. See `LICENSE` and `COPYING`. Upstream attribution, files
carried from other projects and what is deliberately never committed (vendor SDK binaries, reference clones)
are listed in `NOTICE` and `THIRD_PARTY.md`.

## Credits

- **VulkanMod**, by xCollateral and contributors: the base of this whole project.
- FabricMC (Fabric Rendering API implementation, Apache-2.0), carried by VulkanMod.
- Part of the Elemore pack.
