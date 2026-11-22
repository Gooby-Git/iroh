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
#include <FidelityFX/host/backends/vk/ffx_vk.h>
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
#define IROH_FSR_LABEL "FidelityFX SDK 1.1.4 (FSR 3.1.4 host + 3.1.6 shaders)"
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

#define VKD(name) auto name = (PFN_##name)c->dc.vkDeviceProcAddr(c->dc.vkDevice, #name)

bool makeShared(Ctx* c, Ctx::Shared& s, VkFormat fmt, uint32_t w, uint32_t h, std::string& err) {
    VKD(vkCreateImage); VKD(vkGetImageMemoryRequirements); VKD(vkAllocateMemory); VKD(vkBindImageMemory);
    VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ci.imageType = VK_IMAGE_TYPE_2D; ci.format = fmt; ci.extent = {w, h, 1}; ci.mipLevels = 1; ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT; ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT; ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
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
        ci.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
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

} // extern "C"
