// libiroh_fsr: JNI shim between dev.elemore.iroh.upscale.fsr.FsrNative and AMD FidelityFX SDK v1.1.4 (MIT, patched, see
// THIRD_PARTY.md): FSR 3.1 upscaler on the SDK's Vulkan backend. Written for Iroh (LGPL-3.0-only); links AMD's MIT code.
//
// THREAD RULE: one context is used by ONE thread at a time (the render thread, while it records the frame's command
// buffer). No call locks internally except the global message queue. create/destroy only with the GPU idle for that
// context. Static functions (jitter, phaseCount, renderSize, version, structSize) are pure and callable from any thread.
//
// Handles are raw Vulkan handles (LWJGL .address() / long). Dispatch parameters are one direct ByteBuffer holding
// IrohFsrDispatch (little endian, layout below, mirrored by FsrDispatchStruct.java; structSize() lets Java verify).
#include <jni.h>
#include <vulkan/vulkan.h>
#include <FidelityFX/host/ffx_fsr3upscaler.h>
#include <FidelityFX/host/ffx_opticalflow.h>
#include <FidelityFX/host/ffx_frameinterpolation.h>
#include <FidelityFX/host/backends/vk/ffx_vk.h>
#include <FidelityFX/host/ffx_message.h>
#include "ffx_fsr3upscaler_private.h"   // FfxFsr3UpscalerContext_Private: read back the tuning constants (setConstant clamps)
#include <cstdint>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#ifdef _WIN32
#include <malloc.h>
static void* alloc64(size_t n) { return _aligned_malloc((n + 63) & ~(size_t)63, 64); }
static void free64(void* p) { _aligned_free(p); }
#else
static void* alloc64(size_t n) { return aligned_alloc(64, (n + 63) & ~(size_t)63); }
static void free64(void* p) { free(p); }
#endif

#ifndef IROH_FSR_LABEL
#define IROH_FSR_LABEL "FidelityFX SDK 1.1.4 base, FSR 3.1.6 parity (SDK v2.4.0: 3.1.6 shaders, debug-check host deltas)"
#endif

struct ImageRef {            // 32 bytes
    uint64_t image;          // VkImage, 0 = absent (optional inputs only)
    uint32_t format;         // VkFormat
    uint32_t width, height;
    uint32_t usage;          // VkImageUsageFlags the image was created with
    uint32_t layout;         // VkImageLayout it is in NOW: SHADER_READ_ONLY_OPTIMAL (5) or GENERAL (1)
    uint32_t pad;
};
struct IrohFsrDispatch {     // 296 bytes
    uint64_t cmdBuf;         // VkCommandBuffer (recording)
    ImageRef color, depth, motionVectors, exposure, reactive, transparency, output;
    float jitterX, jitterY;  // pixels, as returned by getJitterOffset
    float mvScaleX, mvScaleY;
    uint32_t renderW, renderH, upscaleW, upscaleH;
    float sharpness, frameTimeMs, preExposure, cameraNear, cameraFar, fovY, viewSpaceToMeters;
    uint32_t flags;          // bit0 enableSharpening, bit1 reset, bit2 draw debug view
};
static_assert(sizeof(ImageRef) == 32, "ImageRef");
static_assert(offsetof(IrohFsrDispatch, color) == 8 && offsetof(IrohFsrDispatch, output) == 8 + 6 * 32, "images");
static_assert(offsetof(IrohFsrDispatch, jitterX) == 232 && offsetof(IrohFsrDispatch, renderW) == 248, "scalars");
static_assert(offsetof(IrohFsrDispatch, sharpness) == 264 && offsetof(IrohFsrDispatch, flags) == 292, "floats");
static_assert(sizeof(IrohFsrDispatch) == 296, "IrohFsrDispatch");

// FSR-Q6: ffxFsr3UpscalerContextGenerateReactiveMask (the SDK's own colour-difference pass) parameters
struct IrohReactiveDispatch {   // 128 bytes
    uint64_t cmdBuf;
    ImageRef opaque, color, out;   // opaque-only colour, final colour (both read), reactive target (R32_SFLOAT storage, GENERAL)
    uint32_t renderW, renderH;
    float scale, cutoff, binaryValue;
    uint32_t flags;                // FFX_FSR3UPSCALER_AUTOREACTIVEFLAGS_*: 1 tonemap, 2 inverse tonemap, 4 threshold, 8 components max
};
static_assert(sizeof(IrohReactiveDispatch) == 128, "IrohReactiveDispatch");

namespace {

std::mutex g_msgMutex;
std::string g_messages;                 // FFX runtime messages since the last drain (error + warning), process wide
std::mutex g_createMutex;
bool g_forceNoFp16 = false, g_forceNoWave64 = false;
FfxGetDeviceCapabilitiesFunc g_origCaps = nullptr;

void onFfxMessage(FfxMsgType type, const wchar_t* msg) {
    std::string s = type == FFX_MESSAGE_TYPE_ERROR ? "FFX error: " : "FFX warning: ";
    for (const wchar_t* p = msg; p && *p; ++p) s.push_back(*p < 128 ? (char)*p : '?');
    std::lock_guard<std::mutex> l(g_msgMutex);
    if (g_messages.size() < 16384) g_messages += s + "\n";
}

FfxErrorCode capsWrapper(FfxInterface* i, FfxDeviceCapabilities* c) {
    FfxErrorCode r = g_origCaps(i, c);
    if (g_forceNoFp16) c->fp16Supported = false;
    if (g_forceNoWave64) { c->waveLaneCountMin = 32; c->waveLaneCountMax = 32; }
    return r;
}

const char* errName(FfxErrorCode e) {
    switch ((uint32_t)e) {
        case 0: return "FFX_OK";
        case 0x80000000u: return "FFX_ERROR_INVALID_POINTER";
        case 0x80000001u: return "FFX_ERROR_INVALID_ALIGNMENT";
        case 0x80000002u: return "FFX_ERROR_INVALID_SIZE";
        case 0x80000003u: return "FFX_EOF";
        case 0x80000004u: return "FFX_ERROR_INVALID_PATH";
        case 0x80000005u: return "FFX_ERROR_EOF";
        case 0x80000006u: return "FFX_ERROR_MALFORMED_DATA";
        case 0x80000007u: return "FFX_ERROR_OUT_OF_MEMORY";
        case 0x80000008u: return "FFX_ERROR_INCOMPLETE_INTERFACE";
        case 0x80000009u: return "FFX_ERROR_INVALID_ENUM";
        case 0x8000000au: return "FFX_ERROR_INVALID_ARGUMENT";
        case 0x8000000bu: return "FFX_ERROR_OUT_OF_RANGE";
        case 0x8000000cu: return "FFX_ERROR_NULL_DEVICE";
        case 0x8000000du: return "FFX_ERROR_BACKEND_API_ERROR";
        case 0x8000000eu: return "FFX_ERROR_INSUFFICIENT_MEMORY";
        case 0x8000000fu: return "FFX_ERROR_INVALID_VERSION";
        case 0x80000010u: return "FFX_ERROR_ACCESS_DENIED";
        default: return "FFX_ERROR_UNKNOWN";
    }
}

void throwFsr(JNIEnv* env, const char* what, const std::string& detail) {
    jclass c = env->FindClass("dev/elemore/iroh/upscale/fsr/FsrException");
    if (!c) c = env->FindClass("java/lang/RuntimeException");
    if (c) env->ThrowNew(c, (std::string(what) + ": " + detail).c_str());
}
void throwFfx(JNIEnv* env, const char* call, FfxErrorCode e) {
    char b[96];
    snprintf(b, sizeof b, "%s (0x%08x)", errName(e), (uint32_t)e);
    std::string d = b;
    {
        std::lock_guard<std::mutex> l(g_msgMutex);
        if (!g_messages.empty()) d += "; " + g_messages;
    }
    throwFsr(env, call, d);
}

struct Ctx {
    VkDeviceContext dc{};
    VkPhysicalDevice phys = nullptr;
    void* scratch = nullptr;
    size_t scratchSize = 0;
    FfxInterface iface{};
    FfxFsr3UpscalerContext* fsr = nullptr;
    bool fsrCreated = false;
    // shared images the dispatch wants (owned by the shim, raw Vulkan)
    struct Shared { VkImage image = VK_NULL_HANDLE; VkDeviceMemory mem = VK_NULL_HANDLE; VkFormat fmt = VK_FORMAT_UNDEFINED; uint32_t w = 0, h = 0; bool init = false; uint64_t bytes = 0; };
    Shared dilatedDepth, dilatedMv, prevDepth;
    uint64_t sdkBytes = 0, sharedBytes = 0;
    uint32_t maxRW = 0, maxRH = 0, maxUW = 0, maxUH = 0;
};

#define VKD2(name) auto name = (PFN_##name)c->dc.vkDeviceProcAddr(c->dc.vkDevice, #name)
#define VKD(name) auto name = (PFN_##name)c->dc.vkDeviceProcAddr(c->dc.vkDevice, #name)

static const VkImageUsageFlags SHARED_USAGE = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
bool makeShared(Ctx* c, Ctx::Shared& s, VkFormat fmt, uint32_t w, uint32_t h, std::string& err) {
    VKD(vkCreateImage); VKD(vkGetImageMemoryRequirements); VKD(vkAllocateMemory); VKD(vkBindImageMemory);
    VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ci.imageType = VK_IMAGE_TYPE_2D; ci.format = fmt; ci.extent = {w, h, 1}; ci.mipLevels = 1; ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT; ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    // V-0: the SDK clears reconstructedPrevNearestDepth (vkCmdClearColorImage) and copies shared resources: TRANSFER
    // usage required (VUID-vkCmdClearColorImage-image-00002, VkImageMemoryBarrier-oldLayout-01213 under validation).
    ci.usage = SHARED_USAGE; ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(c->dc.vkDevice, &ci, nullptr, &s.image) != VK_SUCCESS) { err = "vkCreateImage(shared) failed"; return false; }
    VkMemoryRequirements mr; vkGetImageMemoryRequirements(c->dc.vkDevice, s.image, &mr);
    VkPhysicalDeviceMemoryProperties mp; vkGetPhysicalDeviceMemoryProperties(c->phys, &mp);
    uint32_t type = UINT32_MAX;
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
        if ((mr.memoryTypeBits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) { type = i; break; }
    if (type == UINT32_MAX)
        for (uint32_t i = 0; i < mp.memoryTypeCount; i++) if (mr.memoryTypeBits & (1u << i)) { type = i; break; }
    if (type == UINT32_MAX) { err = "no memory type for shared image"; return false; }
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; ai.allocationSize = mr.size; ai.memoryTypeIndex = type;
    if (vkAllocateMemory(c->dc.vkDevice, &ai, nullptr, &s.mem) != VK_SUCCESS) { err = "vkAllocateMemory(shared) failed"; return false; }
    if (vkBindImageMemory(c->dc.vkDevice, s.image, s.mem, 0) != VK_SUCCESS) { err = "vkBindImageMemory(shared) failed"; return false; }
    s.fmt = fmt; s.w = w; s.h = h; s.bytes = mr.size;
    return true;
}
void freeShared(Ctx* c, Ctx::Shared& s) {
    if (s.image) { VKD(vkDestroyImage); vkDestroyImage(c->dc.vkDevice, s.image, nullptr); }
    if (s.mem) { VKD(vkFreeMemory); vkFreeMemory(c->dc.vkDevice, s.mem, nullptr); }
    s = Ctx::Shared{};
}

FfxResource toFfx(const ImageRef& r, bool& ok, std::string& err, const char* what) {
    FfxResource out{};
    ok = true;
    if (r.image == 0) return out;                       // null resource
    VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ci.imageType = VK_IMAGE_TYPE_2D; ci.format = (VkFormat)r.format; ci.extent = {r.width, r.height, 1};
    ci.mipLevels = 1; ci.arrayLayers = 1; ci.usage = r.usage;
    FfxResourceStates st;
    if (r.layout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) st = FFX_RESOURCE_STATE_COMPUTE_READ;
    else if (r.layout == VK_IMAGE_LAYOUT_GENERAL) st = FFX_RESOURCE_STATE_UNORDERED_ACCESS;
    else { ok = false; err = std::string(what) + ": layout must be SHADER_READ_ONLY_OPTIMAL or GENERAL, got " + std::to_string(r.layout); return out; }
    return ffxGetResourceVK((void*)(uintptr_t)r.image, ffxGetImageResourceDescriptionVK((VkImage)r.image, ci), L"iroh", st);
}

void destroyCtx(Ctx* c) {
    if (!c) return;
    if (c->fsrCreated) ffxFsr3UpscalerContextDestroy(c->fsr);
    if (c->dc.vkDevice) { freeShared(c, c->dilatedDepth); freeShared(c, c->dilatedMv); freeShared(c, c->prevDepth); }
    free64(c->scratch);
    free64(c->fsr);
    delete c;
}


// ===================================================================================================================
// Frame generation without the (Win32-only) swapchain proxy: optical flow + frame interpolation components, driven in the
// sequence ffx_fsr3.cpp uses for "interpolation only": FrameInterpolationPrepare -> OpticalFlow dispatch -> FrameInterpolation dispatch,
// all into the caller's command buffer. The shim owns the shared images (optical flow vector + SCD, dilated depth / motion vectors,
// reconstructed previous depth) created from the descriptions the SDK reports. Presenting the interpolated image is the caller's job.
struct FgImage { uint64_t image; uint32_t format, width, height, usage, layout, pad; };
struct IrohFgDispatch {
    uint64_t cmdBuf;
    ImageRef color, depth, motionVectors, output;       // color = current HUD-less presentation colour (display size); output = interpolated image (display size, GENERAL)
    float jitterX, jitterY, mvScaleX, mvScaleY;
    uint32_t renderW, renderH, displayW, displayH;
    float frameTimeMs, cameraNear, cameraFar, fovY, viewSpaceToMeters;
    float camPos[3], camUp[3], camRight[3], camForward[3];
    uint64_t frameId;
    uint32_t flags;                                      // bit0 reset, bit1 debug view, bit2 colour is linear (scRGB) instead of sRGB
    uint32_t pad;
};
static_assert(sizeof(IrohFgDispatch) % 8 == 0, "IrohFgDispatch");

struct FgCtx {
    VkDeviceContext dc{};
    VkPhysicalDevice phys = nullptr;
    void* scratch = nullptr; size_t scratchSize = 0;
    FfxInterface iface{};
    FfxOpticalflowContext* of = nullptr; FfxFrameInterpolationContext* fi = nullptr;
    bool ofOk = false, fiOk = false;
    struct Sh { VkImage image = VK_NULL_HANDLE; VkDeviceMemory mem = VK_NULL_HANDLE; VkFormat fmt = VK_FORMAT_UNDEFINED; uint32_t w = 0, h = 0; bool init = false; uint64_t bytes = 0; std::string what; };
    Sh ofVec, ofScd, dilD, dilMv, recD;
    uint64_t sdkBytes = 0, sharedBytes = 0;
    uint32_t rw = 0, rh = 0, dw = 0, dh = 0;
    std::string info;
};

VkFormat vkFromFfx(FfxSurfaceFormat f) {
    switch (f) {
        case FFX_SURFACE_FORMAT_R32_UINT: return VK_FORMAT_R32_UINT;
        case FFX_SURFACE_FORMAT_R32_FLOAT: return VK_FORMAT_R32_SFLOAT;
        case FFX_SURFACE_FORMAT_R16G16_FLOAT: return VK_FORMAT_R16G16_SFLOAT;
        case FFX_SURFACE_FORMAT_R16G16_SINT: return VK_FORMAT_R16G16_SINT;
        case FFX_SURFACE_FORMAT_R16G16_UINT: return VK_FORMAT_R16G16_UINT;
        case FFX_SURFACE_FORMAT_R16_FLOAT: return VK_FORMAT_R16_SFLOAT;
        case FFX_SURFACE_FORMAT_R16_UINT: return VK_FORMAT_R16_UINT;
        case FFX_SURFACE_FORMAT_R8_UINT: return VK_FORMAT_R8_UINT;
        case FFX_SURFACE_FORMAT_R8G8_UINT: return VK_FORMAT_R8G8_UINT;
        case FFX_SURFACE_FORMAT_R8_UNORM: return VK_FORMAT_R8_UNORM;
        case FFX_SURFACE_FORMAT_R32G32_FLOAT: return VK_FORMAT_R32G32_SFLOAT;
        case FFX_SURFACE_FORMAT_R16G16B16A16_FLOAT: return VK_FORMAT_R16G16B16A16_SFLOAT;
        default: return VK_FORMAT_UNDEFINED;
    }
}

bool makeSharedFfx(FgCtx* c, FgCtx::Sh& s, const FfxCreateResourceDescription& d, const char* what, std::string& err) {
    VkFormat f = vkFromFfx(d.resourceDescription.format);
    char b[160]; snprintf(b, sizeof b, "%s: ffx format %d (vk %d) %ux%u; ", what, (int)d.resourceDescription.format, (int)f, d.resourceDescription.width, d.resourceDescription.height);
    c->info += b;
    if (f == VK_FORMAT_UNDEFINED) { err = std::string("unmapped FFX surface format for ") + what + ": " + std::to_string((int)d.resourceDescription.format); return false; }
    Ctx tmp; tmp.dc = c->dc; tmp.phys = c->phys;
    if (!makeShared(&tmp, tmp.dilatedDepth, f, d.resourceDescription.width, d.resourceDescription.height, err)) return false;
    s.image = tmp.dilatedDepth.image; s.mem = tmp.dilatedDepth.mem; s.fmt = f; s.w = d.resourceDescription.width; s.h = d.resourceDescription.height; s.bytes = tmp.dilatedDepth.bytes;
    return true;
}
void freeShFg(FgCtx* c, FgCtx::Sh& s) {
    Ctx tmp; tmp.dc = c->dc; tmp.phys = c->phys;
    tmp.dilatedDepth.image = s.image; tmp.dilatedDepth.mem = s.mem;
    freeShared(&tmp, tmp.dilatedDepth);
    s = FgCtx::Sh{};
}
FfxResource shRes(FgCtx::Sh& s) {
    VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ci.imageType = VK_IMAGE_TYPE_2D; ci.format = s.fmt; ci.extent = {s.w, s.h, 1}; ci.mipLevels = 1; ci.arrayLayers = 1; ci.usage = SHARED_USAGE;
    return ffxGetResourceVK((void*)(uintptr_t)s.image, ffxGetImageResourceDescriptionVK(s.image, ci), L"iroh-fg-shared", FFX_RESOURCE_STATE_UNORDERED_ACCESS);
}
void destroyFg(FgCtx* c) {
    if (!c) return;
    if (c->fiOk) ffxFrameInterpolationContextDestroy(c->fi);
    if (c->ofOk) ffxOpticalflowContextDestroy(c->of);
    if (c->dc.vkDevice) for (FgCtx::Sh* s : {&c->ofVec, &c->ofScd, &c->dilD, &c->dilMv, &c->recD}) freeShFg(c, *s);
    free64(c->scratch); free64(c->of); free64(c->fi);
    delete c;
}

} // namespace

extern "C" {

#define J(ret, name) JNIEXPORT ret JNICALL Java_dev_elemore_iroh_upscale_fsr_FsrNative_##name

J(jstring, version)(JNIEnv* env, jclass) {
    char b[256];
    uint32_t v = ffxFsr3UpscalerGetEffectVersion();
    snprintf(b, sizeof b, "%s; effect version 0x%08x; contextSize %zu bytes", IROH_FSR_LABEL, v, sizeof(FfxFsr3UpscalerContext));
    return env->NewStringUTF(b);
}
J(jint, structSize)(JNIEnv*, jclass) { return (jint)sizeof(IrohFsrDispatch); }
J(jint, reactiveStructSize)(JNIEnv*, jclass) { return (jint)sizeof(IrohReactiveDispatch); }

J(jint, phaseCount)(JNIEnv*, jclass, jint renderW, jint displayW) {
    return ffxFsr3UpscalerGetJitterPhaseCount(renderW, displayW);
}
J(jfloatArray, jitter)(JNIEnv* env, jclass, jint index, jint phaseCount) {
    float x = 0, y = 0;
    FfxErrorCode e = ffxFsr3UpscalerGetJitterOffset(&x, &y, index, phaseCount);
    if (e != FFX_OK) { throwFfx(env, "ffxFsr3UpscalerGetJitterOffset", e); return nullptr; }
    jfloatArray a = env->NewFloatArray(2);
    float v[2] = {x, y};
    env->SetFloatArrayRegion(a, 0, 2, v);
    return a;
}
J(jfloat, upscaleRatio)(JNIEnv*, jclass, jint mode) {
    return ffxFsr3UpscalerGetUpscaleRatioFromQualityMode((FfxFsr3UpscalerQualityMode)mode);
}
J(jintArray, renderSize)(JNIEnv* env, jclass, jint mode, jint dw, jint dh) {
    uint32_t w = 0, h = 0;
    FfxErrorCode e = ffxFsr3UpscalerGetRenderResolutionFromQualityMode(&w, &h, dw, dh, (FfxFsr3UpscalerQualityMode)mode);
    if (e != FFX_OK) { throwFfx(env, "ffxFsr3UpscalerGetRenderResolutionFromQualityMode", e); return nullptr; }
    jintArray a = env->NewIntArray(2);
    jint v[2] = {(jint)w, (jint)h};
    env->SetIntArrayRegion(a, 0, 2, v);
    return a;
}

J(jstring, drainMessages)(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> l(g_msgMutex);
    jstring s = env->NewStringUTF(g_messages.c_str());
    g_messages.clear();
    return s;
}

// create(instance, physicalDevice, device, getDeviceProcAddr (0 = libvulkan's), fsrFlags, shimFlags (1 = force no fp16,
// 2 = force no wave64), maxRenderW, maxRenderH, maxUpscaleW, maxUpscaleH) -> context handle
J(jlong, create)(JNIEnv* env, jclass, jlong instance, jlong phys, jlong device, jlong gdpa, jint fsrFlags, jint shimFlags,
                 jint mrw, jint mrh, jint muw, jint muh) {
    (void)instance;
    if (!phys || !device) { throwFsr(env, "create", "null VkPhysicalDevice/VkDevice"); return 0; }
    std::lock_guard<std::mutex> cl(g_createMutex);
    { std::lock_guard<std::mutex> l(g_msgMutex); g_messages.clear(); }
    Ctx* c = new Ctx();
    c->phys = (VkPhysicalDevice)phys;
    c->dc.vkDevice = (VkDevice)device;
    c->dc.vkPhysicalDevice = (VkPhysicalDevice)phys;
    c->dc.vkDeviceProcAddr = gdpa ? (PFN_vkGetDeviceProcAddr)gdpa : (PFN_vkGetDeviceProcAddr)vkGetDeviceProcAddr;
    c->maxRW = mrw; c->maxRH = mrh; c->maxUW = muw; c->maxUH = muh;
    c->scratchSize = ffxGetScratchMemorySizeVK(c->phys, 1);
    c->scratch = alloc64(c->scratchSize);
    if (c->scratch) memset(c->scratch, 0, c->scratchSize);
    c->fsr = (FfxFsr3UpscalerContext*)alloc64(sizeof(FfxFsr3UpscalerContext));
    if (c->fsr) memset(c->fsr, 0, sizeof(FfxFsr3UpscalerContext));
    if (!c->scratch || !c->fsr) { destroyCtx(c); throwFsr(env, "create", "out of host memory"); return 0; }
    FfxDevice d = ffxGetDeviceVK(&c->dc);
    FfxErrorCode e = ffxGetInterfaceVK(&c->iface, d, c->scratch, c->scratchSize, 1);
    if (e != FFX_OK) { destroyCtx(c); throwFfx(env, "ffxGetInterfaceVK", e); return 0; }
    g_origCaps = c->iface.fpGetDeviceCapabilities;
    g_forceNoFp16 = (shimFlags & 1) != 0;
    g_forceNoWave64 = (shimFlags & 2) != 0;
    c->iface.fpGetDeviceCapabilities = capsWrapper;
    FfxFsr3UpscalerContextDescription cd{};
    cd.flags = (uint32_t)fsrFlags;
    cd.maxRenderSize = {(uint32_t)mrw, (uint32_t)mrh};
    cd.maxUpscaleSize = {(uint32_t)muw, (uint32_t)muh};
    cd.fpMessage = onFfxMessage;
    cd.backendInterface = c->iface;
    ffxFsr3UpscalerSetGlobalDebugMessage(onFfxMessage, 0);   // upstream only honours cd.fpMessage via this global (patched to work on Linux)
    e = ffxFsr3UpscalerContextCreate(c->fsr, &cd);
    if (e != FFX_OK) { destroyCtx(c); throwFfx(env, "ffxFsr3UpscalerContextCreate", e); return 0; }
    c->fsrCreated = true;
    FfxEffectMemoryUsage mu{};
    if (ffxFsr3UpscalerContextGetGpuMemoryUsage(c->fsr, &mu) == FFX_OK) c->sdkBytes = mu.totalUsageInBytes;
    std::string err;
    if (!makeShared(c, c->dilatedDepth, VK_FORMAT_R32_SFLOAT, mrw, mrh, err) ||
        !makeShared(c, c->dilatedMv, VK_FORMAT_R16G16_SFLOAT, mrw, mrh, err) ||
        !makeShared(c, c->prevDepth, VK_FORMAT_R32_UINT, mrw, mrh, err)) {
        destroyCtx(c); throwFsr(env, "create", err); return 0;
    }
    c->sharedBytes = c->dilatedDepth.bytes + c->dilatedMv.bytes + c->prevDepth.bytes;
    return (jlong)(uintptr_t)c;
}

// bytes of device memory the context holds: [0] SDK internal (as the SDK reports), [1] shim-owned shared images, [2] scratch host bytes
J(jlongArray, memoryBytes)(JNIEnv* env, jclass, jlong h) {
    Ctx* c = (Ctx*)(uintptr_t)h;
    jlong v[3] = {(jlong)c->sdkBytes, (jlong)c->sharedBytes, (jlong)c->scratchSize};
    jlongArray a = env->NewLongArray(3);
    env->SetLongArrayRegion(a, 0, 3, v);
    return a;
}

J(void, destroy)(JNIEnv*, jclass, jlong h) { destroyCtx((Ctx*)(uintptr_t)h); }

J(void, dispatch)(JNIEnv* env, jclass, jlong h, jobject buf) {
    Ctx* c = (Ctx*)(uintptr_t)h;
    if (!c) { throwFsr(env, "dispatch", "null context"); return; }
    if (env->GetDirectBufferCapacity(buf) < (jlong)sizeof(IrohFsrDispatch)) { throwFsr(env, "dispatch", "parameter buffer smaller than structSize()"); return; }
    IrohFsrDispatch p;
    memcpy(&p, env->GetDirectBufferAddress(buf), sizeof p);
    VkCommandBuffer cb = (VkCommandBuffer)p.cmdBuf;
    if (!cb) { throwFsr(env, "dispatch", "null command buffer"); return; }

    // first use of the shim-owned shared images: UNDEFINED -> GENERAL (the SDK then tracks them as UAV)
    VkImageMemoryBarrier bars[3]; uint32_t nb = 0;
    for (Ctx::Shared* s : {&c->dilatedDepth, &c->dilatedMv, &c->prevDepth}) {
        if (s->init) continue;
        VkImageMemoryBarrier& b = bars[nb++]; b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED; b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED; b.image = s->image;
        b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        b.srcAccessMask = 0; b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        s->init = true;
    }
    if (nb) {
        VKD(vkCmdPipelineBarrier);
        vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, nb, bars);
    }

    bool ok; std::string err;
    FfxFsr3UpscalerDispatchDescription d{};
    d.commandList = ffxGetCommandListVK(cb);
    struct { const ImageRef* r; FfxResource* out; const char* n; bool req; } m[] = {
        {&p.color, &d.color, "color", true}, {&p.depth, &d.depth, "depth", true}, {&p.motionVectors, &d.motionVectors, "motionVectors", true},
        {&p.exposure, &d.exposure, "exposure", false}, {&p.reactive, &d.reactive, "reactive", false},
        {&p.transparency, &d.transparencyAndComposition, "transparencyAndComposition", false}, {&p.output, &d.output, "output", true}};
    for (auto& x : m) {
        if (x.req && !x.r->image) { throwFsr(env, "dispatch", std::string("missing required input ") + x.n); return; }
        *x.out = toFfx(*x.r, ok, err, x.n);
        if (!ok) { throwFsr(env, "dispatch", err); return; }
    }
    auto shared = [&](Ctx::Shared& s) {
        VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        ci.imageType = VK_IMAGE_TYPE_2D; ci.format = s.fmt; ci.extent = {s.w, s.h, 1}; ci.mipLevels = 1; ci.arrayLayers = 1;
        ci.usage = SHARED_USAGE;
        return ffxGetResourceVK((void*)(uintptr_t)s.image, ffxGetImageResourceDescriptionVK(s.image, ci), L"iroh-shared", FFX_RESOURCE_STATE_UNORDERED_ACCESS);
    };
    d.dilatedDepth = shared(c->dilatedDepth);
    d.dilatedMotionVectors = shared(c->dilatedMv);
    d.reconstructedPrevNearestDepth = shared(c->prevDepth);
    d.jitterOffset = {p.jitterX, p.jitterY};
    d.motionVectorScale = {p.mvScaleX, p.mvScaleY};
    d.renderSize = {p.renderW, p.renderH};
    d.upscaleSize = {p.upscaleW, p.upscaleH};
    d.enableSharpening = (p.flags & 1) != 0;
    d.sharpness = p.sharpness;
    d.frameTimeDelta = p.frameTimeMs;
    d.preExposure = p.preExposure;
    d.reset = (p.flags & 2) != 0;
    d.cameraNear = p.cameraNear; d.cameraFar = p.cameraFar; d.cameraFovAngleVertical = p.fovY;
    d.viewSpaceToMetersFactor = p.viewSpaceToMeters > 0 ? p.viewSpaceToMeters : 1.0f;
    d.flags = (p.flags & 4) ? FFX_FSR3UPSCALER_DISPATCH_DRAW_DEBUG_VIEW : 0;
    { std::lock_guard<std::mutex> l(g_msgMutex); g_messages.clear(); }
    FfxErrorCode e = ffxFsr3UpscalerContextDispatch(c->fsr, &d);
    if (e != FFX_OK) throwFfx(env, "ffxFsr3UpscalerContextDispatch", e);
}

// FSR-Q6: the SDK's auto-generated reactive mask (|final - opaque-only| scaled, optional threshold) into p.out, records into the same command buffer.
J(void, generateReactive)(JNIEnv* env, jclass, jlong h, jobject buf) {
    Ctx* c = (Ctx*)(uintptr_t)h;
    if (!c) { throwFsr(env, "generateReactive", "null context"); return; }
    if (env->GetDirectBufferCapacity(buf) < (jlong)sizeof(IrohReactiveDispatch)) { throwFsr(env, "generateReactive", "parameter buffer smaller than reactiveStructSize()"); return; }
    IrohReactiveDispatch p;
    memcpy(&p, env->GetDirectBufferAddress(buf), sizeof p);
    if (!p.cmdBuf) { throwFsr(env, "generateReactive", "null command buffer"); return; }
    bool ok; std::string err;
    FfxFsr3UpscalerGenerateReactiveDescription d{};
    d.commandList = ffxGetCommandListVK((VkCommandBuffer)p.cmdBuf);
    struct { const ImageRef* r; FfxResource* out; const char* n; } m[] = {
        {&p.opaque, &d.colorOpaqueOnly, "opaque"}, {&p.color, &d.colorPreUpscale, "color"}, {&p.out, &d.outReactive, "out"}};
    for (auto& x : m) {
        if (!x.r->image) { throwFsr(env, "generateReactive", std::string("missing input ") + x.n); return; }
        *x.out = toFfx(*x.r, ok, err, x.n);
        if (!ok) { throwFsr(env, "generateReactive", err); return; }
    }
    d.renderSize = {p.renderW, p.renderH};
    d.scale = p.scale; d.cutoffThreshold = p.cutoff; d.binaryValue = p.binaryValue; d.flags = p.flags;
    { std::lock_guard<std::mutex> l(g_msgMutex); g_messages.clear(); }
    FfxErrorCode e = ffxFsr3UpscalerContextGenerateReactiveMask(c->fsr, &d);
    if (e != FFX_OK) throwFfx(env, "ffxFsr3UpscalerContextGenerateReactiveMask", e);
}

// ---- FfxFsr3UpscalerConfigureUpscaleKey tuning constants (ffxFsr3UpscalerSetConstant). Keys 0..4:
// FVELOCITYFACTOR [0,1] def 1, FREACTIVENESSSCALE [0,inf) def 1, FSHADINGCHANGESCALE [0,inf) def 1,
// FACCUMULATIONADDEDPERFRAME [0,1] def 1/3, FMINDISOCCLUSIONACCUMULATION [-1,1] def -1/3. Values persist for the context's life.
static void readConstants(Ctx* c, float out[5]) {
    const Fsr3UpscalerConstants& k = ((FfxFsr3UpscalerContext_Private*)c->fsr)->constants;
    out[0] = k.velocityFactor; out[1] = k.reactivenessScale; out[2] = k.shadingChangeScale;
    out[3] = k.accumulationAddedPerFrame; out[4] = k.minDisocclusionAccumulation;
}
// setConstant(ctx, key, value) -> the value the SDK actually stored (after its clamp)
J(jfloat, setConstant)(JNIEnv* env, jclass, jlong h, jint key, jfloat value) {
    Ctx* c = (Ctx*)(uintptr_t)h;
    if (key < 0 || key > 4) { throwFsr(env, "setConstant", "unknown configure key"); return 0; }
    float v = value;
    FfxErrorCode e = ffxFsr3UpscalerSetConstant(c->fsr, (FfxFsr3UpscalerConfigureKey)key, &v);
    if (e != FFX_OK) { throwFfx(env, "ffxFsr3UpscalerSetConstant", e); return 0; }
    float k[5]; readConstants(c, k);
    return k[key];
}
J(jfloatArray, getConstants)(JNIEnv* env, jclass, jlong h) {
    float k[5]; readConstants((Ctx*)(uintptr_t)h, k);
    jfloatArray a = env->NewFloatArray(5);
    env->SetFloatArrayRegion(a, 0, 5, k);
    return a;
}

J(jint, fgStructSize)(JNIEnv*, jclass) { return (jint)sizeof(IrohFgDispatch); }

// fgCreate(instance, phys, device, gdpa, flags (bit0 hdr colour input), shimFlags, renderW, renderH, displayW, displayH, backBufferVkFormat) -> handle
J(jlong, fgCreate)(JNIEnv* env, jclass, jlong instance, jlong phys, jlong device, jlong gdpa, jint flags, jint shimFlags, jint rw, jint rh, jint dw, jint dh, jint backFormat) {
    (void)instance;
    if (!phys || !device) { throwFsr(env, "fgCreate", "null VkPhysicalDevice/VkDevice"); return 0; }
    std::lock_guard<std::mutex> cl(g_createMutex);
    { std::lock_guard<std::mutex> l(g_msgMutex); g_messages.clear(); }
    FgCtx* c = new FgCtx();
    c->phys = (VkPhysicalDevice)phys;
    c->dc.vkDevice = (VkDevice)device; c->dc.vkPhysicalDevice = (VkPhysicalDevice)phys;
    c->dc.vkDeviceProcAddr = gdpa ? (PFN_vkGetDeviceProcAddr)gdpa : (PFN_vkGetDeviceProcAddr)vkGetDeviceProcAddr;
    c->rw = rw; c->rh = rh; c->dw = dw; c->dh = dh;
    c->scratchSize = ffxGetScratchMemorySizeVK(c->phys, 2);
    c->scratch = alloc64(c->scratchSize);
    c->of = (FfxOpticalflowContext*)alloc64(sizeof(FfxOpticalflowContext));
    c->fi = (FfxFrameInterpolationContext*)alloc64(sizeof(FfxFrameInterpolationContext));
    if (!c->scratch || !c->of || !c->fi) { destroyFg(c); throwFsr(env, "fgCreate", "out of host memory"); return 0; }
    memset(c->scratch, 0, c->scratchSize); memset(c->of, 0, sizeof(FfxOpticalflowContext)); memset(c->fi, 0, sizeof(FfxFrameInterpolationContext));
    FfxDevice d = ffxGetDeviceVK(&c->dc);
    FfxErrorCode e = ffxGetInterfaceVK(&c->iface, d, c->scratch, c->scratchSize, 2);
    if (e != FFX_OK) { destroyFg(c); throwFfx(env, "ffxGetInterfaceVK(fg)", e); return 0; }
    g_origCaps = c->iface.fpGetDeviceCapabilities;
    g_forceNoFp16 = (shimFlags & 1) != 0; g_forceNoWave64 = (shimFlags & 2) != 0;
    c->iface.fpGetDeviceCapabilities = capsWrapper;
    FfxOpticalflowContextDescription od{};
    od.backendInterface = c->iface; od.flags = 0; od.resolution = {(uint32_t)dw, (uint32_t)dh};
    e = ffxOpticalflowContextCreate(c->of, &od);
    if (e != FFX_OK) { destroyFg(c); throwFfx(env, "ffxOpticalflowContextCreate", e); return 0; }
    c->ofOk = true;
    FfxFrameInterpolationContextDescription fd{};
    fd.flags = (flags & 1) ? FFX_FRAMEINTERPOLATION_ENABLE_HDR_COLOR_INPUT : 0;
    fd.maxRenderSize = {(uint32_t)rw, (uint32_t)rh}; fd.displaySize = {(uint32_t)dw, (uint32_t)dh};
    fd.backBufferFormat = ffxGetSurfaceFormatVK((VkFormat)backFormat); fd.previousInterpolationSourceFormat = fd.backBufferFormat;
    fd.backendInterface = c->iface;
    e = ffxFrameInterpolationContextCreate(c->fi, &fd);
    if (e != FFX_OK) { destroyFg(c); throwFfx(env, "ffxFrameInterpolationContextCreate", e); return 0; }
    c->fiOk = true;
    FfxEffectMemoryUsage mu{};
    if (ffxOpticalflowContextGetGpuMemoryUsage(c->of, &mu) == FFX_OK) c->sdkBytes += mu.totalUsageInBytes;
    if (ffxFrameInterpolationContextGetGpuMemoryUsage(c->fi, &mu) == FFX_OK) c->sdkBytes += mu.totalUsageInBytes;
    FfxOpticalflowSharedResourceDescriptions od2{};
    FfxFrameInterpolationSharedResourceDescriptions fd2{};
    std::string err;
    if (ffxOpticalflowGetSharedResourceDescriptions(c->of, &od2) != FFX_OK || ffxFrameInterpolationGetSharedResourceDescriptions(c->fi, &fd2) != FFX_OK) { destroyFg(c); throwFsr(env, "fgCreate", "GetSharedResourceDescriptions failed"); return 0; }
    if (!makeSharedFfx(c, c->ofVec, od2.opticalFlowVector, "opticalFlowVector", err) || !makeSharedFfx(c, c->ofScd, od2.opticalFlowSCD, "opticalFlowSCD", err) ||
        !makeSharedFfx(c, c->dilD, fd2.dilatedDepth, "dilatedDepth", err) || !makeSharedFfx(c, c->dilMv, fd2.dilatedMotionVectors, "dilatedMotionVectors", err) ||
        !makeSharedFfx(c, c->recD, fd2.reconstructedPrevNearestDepth, "reconstructedPrevNearestDepth", err)) { destroyFg(c); throwFsr(env, "fgCreate", err); return 0; }
    c->sharedBytes = c->ofVec.bytes + c->ofScd.bytes + c->dilD.bytes + c->dilMv.bytes + c->recD.bytes;
    return (jlong)(uintptr_t)c;
}

J(jstring, fgInfo)(JNIEnv* env, jclass, jlong h) { return env->NewStringUTF(((FgCtx*)(uintptr_t)h)->info.c_str()); }
J(jlongArray, fgMemoryBytes)(JNIEnv* env, jclass, jlong h) {
    FgCtx* c = (FgCtx*)(uintptr_t)h;
    jlong v[3] = {(jlong)c->sdkBytes, (jlong)c->sharedBytes, (jlong)c->scratchSize};
    jlongArray a = env->NewLongArray(3); env->SetLongArrayRegion(a, 0, 3, v); return a;
}
// debug/inspection: {image, width, height, vkFormat} for optical flow vector, SCD, dilated depth, dilated MV, reconstructed previous depth
J(jlongArray, fgSharedImages)(JNIEnv* env, jclass, jlong h) {
    FgCtx* c = (FgCtx*)(uintptr_t)h;
    jlong v[20]; int i = 0;
    for (FgCtx::Sh* s : {&c->ofVec, &c->ofScd, &c->dilD, &c->dilMv, &c->recD}) { v[i++] = (jlong)s->image; v[i++] = s->w; v[i++] = s->h; v[i++] = s->fmt; }
    jlongArray a = env->NewLongArray(20); env->SetLongArrayRegion(a, 0, 20, v); return a;
}
J(void, fgDestroy)(JNIEnv*, jclass, jlong h) { destroyFg((FgCtx*)(uintptr_t)h); }

J(void, fgDispatch)(JNIEnv* env, jclass, jlong h, jobject buf) {
    FgCtx* c = (FgCtx*)(uintptr_t)h;
    if (!c) { throwFsr(env, "fgDispatch", "null context"); return; }
    if (env->GetDirectBufferCapacity(buf) < (jlong)sizeof(IrohFgDispatch)) { throwFsr(env, "fgDispatch", "parameter buffer smaller than fgStructSize()"); return; }
    IrohFgDispatch p;
    memcpy(&p, env->GetDirectBufferAddress(buf), sizeof p);
    VkCommandBuffer cb = (VkCommandBuffer)p.cmdBuf;
    if (!cb) { throwFsr(env, "fgDispatch", "null command buffer"); return; }
    // first use of the shim-owned images: UNDEFINED -> GENERAL
    VkImageMemoryBarrier bars[5]; uint32_t nb = 0;
    for (FgCtx::Sh* s : {&c->ofVec, &c->ofScd, &c->dilD, &c->dilMv, &c->recD}) {
        if (s->init) continue;
        VkImageMemoryBarrier& b = bars[nb++]; b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED; b.newLayout = VK_IMAGE_LAYOUT_GENERAL; b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED; b.image = s->image;
        b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}; b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        s->init = true;
    }
    if (nb) { Ctx tmp; tmp.dc = c->dc; tmp.phys = c->phys; VKD2(vkCmdPipelineBarrier); vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, nb, bars); }
    bool ok; std::string err;
    FfxResource color = toFfx(p.color, ok, err, "color"); if (!ok) { throwFsr(env, "fgDispatch", err); return; }
    FfxResource depth = toFfx(p.depth, ok, err, "depth"); if (!ok) { throwFsr(env, "fgDispatch", err); return; }
    FfxResource mv = toFfx(p.motionVectors, ok, err, "motionVectors"); if (!ok) { throwFsr(env, "fgDispatch", err); return; }
    FfxResource out = toFfx(p.output, ok, err, "output"); if (!ok) { throwFsr(env, "fgDispatch", err); return; }
    { std::lock_guard<std::mutex> l(g_msgMutex); g_messages.clear(); }
    FfxResource dilD = shRes(c->dilD), dilMv = shRes(c->dilMv), recD = shRes(c->recD), ofv = shRes(c->ofVec), ofs = shRes(c->ofScd);
    FfxCommandList cl = ffxGetCommandListVK(cb);
    // 1. prepare (dilate depth / motion vectors, reconstruct previous depth)
    FfxFrameInterpolationPrepareDescription pd{};
    pd.flags = 0; pd.commandList = cl; pd.renderSize = {p.renderW, p.renderH}; pd.jitterOffset = {p.jitterX, p.jitterY}; pd.motionVectorScale = {p.mvScaleX, p.mvScaleY};
    pd.frameTimeDelta = p.frameTimeMs; pd.cameraNear = p.cameraNear; pd.cameraFar = p.cameraFar; pd.viewSpaceToMetersFactor = p.viewSpaceToMeters > 0 ? p.viewSpaceToMeters : 1.0f;
    pd.cameraFovAngleVertical = p.fovY; pd.depth = depth; pd.motionVectors = mv; pd.frameID = p.frameId;
    pd.dilatedDepth = dilD; pd.dilatedMotionVectors = dilMv; pd.reconstructedPrevDepth = recD;
    for (int i = 0; i < 3; i++) { pd.cameraPosition[i] = p.camPos[i]; pd.cameraUp[i] = p.camUp[i]; pd.cameraRight[i] = p.camRight[i]; pd.cameraForward[i] = p.camForward[i]; }
    FfxErrorCode e = ffxFrameInterpolationPrepare(c->fi, &pd);
    if (e != FFX_OK) { throwFfx(env, "ffxFrameInterpolationPrepare", e); return; }
    // 2. optical flow on the presentation colour
    FfxOpticalflowDispatchDescription od{};
    od.commandList = cl; od.color = color; od.opticalFlowVector = ofv; od.opticalFlowSCD = ofs; od.reset = (p.flags & 1) != 0;
    od.backbufferTransferFunction = (p.flags & 4) ? FFX_BACKBUFFER_TRANSFER_FUNCTION_SCRGB : FFX_BACKBUFFER_TRANSFER_FUNCTION_SRGB; od.minMaxLuminance = {0.0f, 0.0f};
    if ((p.flags & 8) == 0) {                          // bit3: skip the SDK optical flow (vectors supplied by the caller into the shim-owned images)
        e = ffxOpticalflowContextDispatch(c->of, &od);
        if (e != FFX_OK) { throwFfx(env, "ffxOpticalflowContextDispatch", e); return; }
    }
    // 3. interpolation
    FfxFrameInterpolationDispatchDescription fd{};
    fd.flags = (p.flags & 2) ? FFX_FRAMEINTERPOLATION_DISPATCH_DRAW_DEBUG_VIEW : 0;
    fd.commandList = cl; fd.displaySize = {p.displayW, p.displayH}; fd.renderSize = {p.renderW, p.renderH};
    fd.currentBackBuffer = color; fd.output = out;
    fd.interpolationRect = {0, 0, (int32_t)p.displayW, (int32_t)p.displayH};
    fd.opticalFlowVector = ofv; fd.opticalFlowSceneChangeDetection = ofs; fd.opticalFlowBufferSize = {p.displayW, p.displayH}; fd.opticalFlowBlockSize = 8;
    fd.opticalFlowScale = {1.0f / p.displayW, 1.0f / p.displayH};
    fd.cameraNear = p.cameraNear; fd.cameraFar = p.cameraFar; fd.cameraFovAngleVertical = p.fovY; fd.viewSpaceToMetersFactor = pd.viewSpaceToMetersFactor;
    fd.frameTimeDelta = p.frameTimeMs; fd.reset = (p.flags & 1) != 0;
    fd.backBufferTransferFunction = od.backbufferTransferFunction; fd.minMaxLuminance[0] = 0.0f; fd.minMaxLuminance[1] = 0.0f;
    fd.frameID = p.frameId; fd.dilatedDepth = dilD; fd.dilatedMotionVectors = dilMv; fd.reconstructedPrevDepth = recD;
    e = ffxFrameInterpolationDispatch(c->fi, &fd);
    if (e != FFX_OK) throwFfx(env, "ffxFrameInterpolationDispatch", e);
}

} // extern "C"
