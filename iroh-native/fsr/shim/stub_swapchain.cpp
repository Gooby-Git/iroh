// Frame-interpolation swapchain is Win32-only in SDK 1.1.4; the upscaler library does not use it.
#include <FidelityFX/host/backends/vk/ffx_vk.h>
FfxErrorCode ffxSetFrameGenerationConfigToSwapchainVK(FfxFrameGenerationConfig const*) { return FFX_ERROR_BACKEND_API_ERROR; }
