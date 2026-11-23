# V-0 validation findings (int-4c, lavapipe Mesa 26.2.5, VVL 1.4.363), working notes
Run 1: tools/allsuites.sh -> /tmp/v0-run1 (validation core = core + thread safety + syncval).
| # | VUID | count | where | verdict | fix |
|---|---|---|---|---|---|
| 1 | VUID-vkDestroyDevice-device-05137 (3 obj) | 1 | apiReplay: GpuCore.zeroTailPipe never destroyed | Iroh bug (runtime) | 0b31ca4 |
| 2 | VUID-vkDestroyDevice-device-05137 (6..58 obj) | 1 each | rtReplay, rtEntities, rtLights, rtProbe | triage | |
| 3 | SYNC-HAZARD-WRITE-AFTER-WRITE vkCmdCopyBuffer | 2 | rtReplay | triage | |
| 4 | VUID-VkImageMemoryBarrier-oldLayout-01213/-01212, vkCmdClearColorImage-image-00002, CopyImageToBuffer-srcImage-00186 | 168..360 | fsr*Replay, chainReplay, fgReplay (rw_luma_history etc.) | triage (FSR SDK resource usage?) | |
| 5 | VUID-vkCmdDispatch-None-08114 r_counters | 6 | fgReplay | triage | |
| 6 | Undefined-Value-StorageImage-FormatMismatch (warning) | | fsr* | triage | |
Item 5 of the card (td-0cc003 headless): dynStateVkReplay dc73df4. Blockers: net.vulkanmod.vulkan.shader.Pipeline static init
(DEVICE = Vulkan.getVkDevice(), PIPELINE_CACHE) on any GraphicsPipeline static call; Renderer needs Minecraft.
