// libiroh_nrd: JNI shim between dev.elemore.iroh.rt.denoise.nrd.NrdNative and NVIDIA NRD v4.18.0 (stock, static, SPIR-V embedded).
// NRD makes NO graphics-API calls: this shim only creates the instance, hands the settings over and serialises what NRD
// returns (instance description, SPIR-V pointers, compute dispatch lists) into plain little-endian int32 blobs that the
// Java executor turns into Vulkan objects and dispatches. NVIDIA RTX SDKs licence applies to the NRD part (THIRD_PARTY.md,
// docs/P6-PLAN.md): this file itself is Iroh's, LGPL-3.0-only.
//
// THREAD RULE: one instance is driven by one thread (the render thread). Returned direct buffers stay valid until the next
// call that returns the same kind of buffer for that instance, or destroy(). Static functions are pure.
#include <jni.h>
#include "NRD.h"
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

using namespace nrd;

namespace {

struct Ctx {
    Instance* instance = nullptr;
    std::vector<uint8_t> descBlob, dispBlob;
    std::vector<Denoiser> denoisers;
};

struct Out {
    std::vector<uint8_t>& v;
    void u32(uint32_t x) { uint8_t b[4]; memcpy(b, &x, 4); v.insert(v.end(), b, b + 4); }
    void f32(float x) { uint32_t u; memcpy(&u, &x, 4); u32(u); }
    void bytes(const void* p, size_t n) { const uint8_t* b = (const uint8_t*)p; v.insert(v.end(), b, b + n); while (v.size() & 3) v.push_back(0); }
    void str(const char* s) { uint32_t n = s ? (uint32_t)strlen(s) : 0; u32(n); bytes(s, n); }
};

const char* resultName(Result r) {
    switch (r) {
        case Result::SUCCESS: return "SUCCESS";
        case Result::FAILURE: return "FAILURE";
        case Result::INVALID_ARGUMENT: return "INVALID_ARGUMENT";
        case Result::UNSUPPORTED: return "UNSUPPORTED";
        case Result::NON_UNIQUE_IDENTIFIER: return "NON_UNIQUE_IDENTIFIER";
        default: return "UNKNOWN";
    }
}

void throwNrd(JNIEnv* env, const char* call, const std::string& detail) {
    jclass c = env->FindClass("dev/elemore/iroh/rt/denoise/nrd/NrdException");
    if (!c) c = env->FindClass("java/lang/RuntimeException");
    if (c) env->ThrowNew(c, (std::string(call) + ": " + detail).c_str());
}
void throwResult(JNIEnv* env, const char* call, Result r) { throwNrd(env, call, std::string("NRD Result::") + resultName(r)); }

// ---- CommonSettings as laid out by Java (IrohNrdCommon, 4-byte fields, little endian, 364 bytes)
struct IrohNrdCommon {
    float viewToClip[16], viewToClipPrev[16], worldToView[16], worldToViewPrev[16];   // 0..255 column-major, non jittered
    float mvScale[3], mvBias[3];                                                      // 256..279
    float jitter[2], jitterPrev[2];                                                   // 280..295
    uint32_t resourceSize[2], resourceSizePrev[2], rectSize[2], rectSizePrev[2];      // 296..327
    float viewZScale, timeDeltaMs, denoisingRange, disocclusionThreshold, disocclusionThresholdAlt, splitScreen; // 328..351
    uint32_t frameIndex, accumulationMode;                                            // 352..359 (0 CONTINUE, 1 RESTART, 2 CLEAR_AND_RESTART)
    uint32_t flags;                                                                   // 360 bit0 mv world space, bit1 history confidence, bit2 disocclusion mix, bit3 validation
};
static_assert(sizeof(IrohNrdCommon) == 364, "IrohNrdCommon layout");

} // namespace

extern "C" {

#define J(ret, name) JNIEXPORT ret JNICALL Java_dev_elemore_iroh_rt_denoise_nrd_NrdNative_##name

J(jstring, version)(JNIEnv* env, jclass) {
    const LibraryDesc* d = GetLibraryDesc();
    char b[200];
    snprintf(b, sizeof b, "NVIDIA NRD %d.%d.%d (stock, static, SPIR-V embedded)", d->versionMajor, d->versionMinor, d->versionBuild);
    return env->NewStringUTF(b);
}
J(jint, commonStructSize)(JNIEnv*, jclass) { return (jint)sizeof(IrohNrdCommon); }

// {samplerOffset, textureOffset, constantBufferOffset, storageOffset, major, minor, build, normalEncoding, roughnessEncoding, supportedDenoisersNum, <supported denoiser enum values...>}
J(jintArray, libraryDesc)(JNIEnv* env, jclass) {
    const LibraryDesc* d = GetLibraryDesc();
    std::vector<jint> v = {(jint)d->spirvBindingOffsets.samplerOffset, (jint)d->spirvBindingOffsets.textureOffset, (jint)d->spirvBindingOffsets.constantBufferOffset,
                           (jint)d->spirvBindingOffsets.storageTextureAndBufferOffset, d->versionMajor, d->versionMinor, d->versionBuild,
                           (jint)d->normalEncoding, (jint)d->roughnessEncoding, (jint)d->supportedDenoisersNum};
    for (uint32_t i = 0; i < d->supportedDenoisersNum; i++) v.push_back((jint)d->supportedDenoisers[i]);
    jintArray a = env->NewIntArray((jsize)v.size());
    env->SetIntArrayRegion(a, 0, (jsize)v.size(), v.data());
    return a;
}

// create(denoiser enum values): identifier of denoiser i = i
J(jlong, create)(JNIEnv* env, jclass, jintArray den) {
    jsize n = env->GetArrayLength(den);
    std::vector<jint> ids(n);
    env->GetIntArrayRegion(den, 0, n, ids.data());
    Ctx* c = new Ctx();
    std::vector<DenoiserDesc> dd;
    for (jsize i = 0; i < n; i++) { dd.push_back({(Identifier)i, (Denoiser)ids[i]}); c->denoisers.push_back((Denoiser)ids[i]); }
    InstanceCreationDesc cd = {};
    cd.denoisers = dd.data();
    cd.denoisersNum = (uint32_t)dd.size();
    Result r = CreateInstance(cd, c->instance);
    if (r != Result::SUCCESS) { delete c; throwResult(env, "nrd::CreateInstance", r); return 0; }
    return (jlong)(uintptr_t)c;
}

J(void, destroy)(JNIEnv*, jclass, jlong h) {
    Ctx* c = (Ctx*)(uintptr_t)h;
    if (!c) return;
    if (c->instance) DestroyInstance(*c->instance);
    delete c;
}

// Serialised InstanceDesc (int32 LE): see NrdInstanceDesc.parse in Java.
J(jobject, instanceDesc)(JNIEnv* env, jclass, jlong h) {
    Ctx* c = (Ctx*)(uintptr_t)h;
    const InstanceDesc* d = GetInstanceDesc(*c->instance);
    c->descBlob.clear();
    Out o{c->descBlob};
    o.u32(d->constantBufferAndSamplersSpaceIndex); o.u32(d->resourcesSpaceIndex); o.u32(d->constantBufferRegisterIndex);
    o.u32(d->samplersBaseRegisterIndex); o.u32(d->resourcesBaseRegisterIndex); o.u32(d->constantBufferMaxDataSize);
    o.u32(d->samplersNum);
    for (uint32_t i = 0; i < d->samplersNum; i++) o.u32((uint32_t)d->samplers[i]);
    o.u32(d->pipelinesNum); o.u32(d->permanentPoolSize); o.u32(d->transientPoolSize);
    o.u32(d->descriptorPoolDesc.perSetTexturesMaxNum); o.u32(d->descriptorPoolDesc.perSetStorageTexturesMaxNum);
    o.u32(d->descriptorPoolDesc.totalTexturesNum); o.u32(d->descriptorPoolDesc.totalStorageTexturesNum); o.u32(d->descriptorPoolDesc.setsMaxNum);
    for (uint32_t i = 0; i < d->permanentPoolSize; i++) { o.u32((uint32_t)d->permanentPool[i].format); o.u32(d->permanentPool[i].downsampleFactor); }
    for (uint32_t i = 0; i < d->transientPoolSize; i++) { o.u32((uint32_t)d->transientPool[i].format); o.u32(d->transientPool[i].downsampleFactor); }
    for (uint32_t i = 0; i < d->pipelinesNum; i++) {
        const PipelineDesc& p = d->pipelines[i];
        o.u32(p.hasConstantData ? 1 : 0);
        o.u32((uint32_t)p.computeShaderSPIRV.size);
        o.u32(p.resourceRangesNum);
        for (uint32_t k = 0; k < 2; k++) {
            o.u32(k < p.resourceRangesNum ? (uint32_t)p.resourceRanges[k].descriptorType : 0);
            o.u32(k < p.resourceRangesNum ? p.resourceRanges[k].descriptorsNum : 0);
        }
        o.str(p.shaderIdentifier);
    }
    return env->NewDirectByteBuffer(c->descBlob.data(), (jlong)c->descBlob.size());
}

// the library's own SPIR-V for a pipeline (valid until destroy)
J(jobject, spirv)(JNIEnv* env, jclass, jlong h, jint pipeline) {
    Ctx* c = (Ctx*)(uintptr_t)h;
    const InstanceDesc* d = GetInstanceDesc(*c->instance);
    if (pipeline < 0 || (uint32_t)pipeline >= d->pipelinesNum) { throwNrd(env, "spirv", "pipeline index out of range"); return nullptr; }
    const ComputeShaderDesc& s = d->pipelines[pipeline].computeShaderSPIRV;
    if (!s.bytecode || !s.size) { throwNrd(env, "spirv", "pipeline has no embedded SPIR-V (library built without NRD_EMBEDS_SPIRV_SHADERS)"); return nullptr; }
    return env->NewDirectByteBuffer(const_cast<void*>(s.bytecode), (jlong)s.size);
}

J(void, setCommon)(JNIEnv* env, jclass, jlong h, jobject buf) {
    Ctx* c = (Ctx*)(uintptr_t)h;
    if (env->GetDirectBufferCapacity(buf) < (jlong)sizeof(IrohNrdCommon)) { throwNrd(env, "setCommon", "buffer smaller than commonStructSize()"); return; }
    IrohNrdCommon s;
    memcpy(&s, env->GetDirectBufferAddress(buf), sizeof s);
    CommonSettings cs = {};
    memcpy(cs.viewToClipMatrix, s.viewToClip, 64); memcpy(cs.viewToClipMatrixPrev, s.viewToClipPrev, 64);
    memcpy(cs.worldToViewMatrix, s.worldToView, 64); memcpy(cs.worldToViewMatrixPrev, s.worldToViewPrev, 64);
    memcpy(cs.motionVectorScale, s.mvScale, 12); memcpy(cs.motionVectorBias, s.mvBias, 12);
    memcpy(cs.cameraJitter, s.jitter, 8); memcpy(cs.cameraJitterPrev, s.jitterPrev, 8);
    for (int i = 0; i < 2; i++) {
        cs.resourceSize[i] = (uint16_t)s.resourceSize[i]; cs.resourceSizePrev[i] = (uint16_t)s.resourceSizePrev[i];
        cs.rectSize[i] = (uint16_t)s.rectSize[i]; cs.rectSizePrev[i] = (uint16_t)s.rectSizePrev[i];
    }
    cs.viewZScale = s.viewZScale; cs.timeDeltaBetweenFrames = s.timeDeltaMs; cs.denoisingRange = s.denoisingRange;
    cs.disocclusionThreshold = s.disocclusionThreshold; cs.disocclusionThresholdAlternate = s.disocclusionThresholdAlt; cs.splitScreen = s.splitScreen;
    cs.frameIndex = s.frameIndex; cs.accumulationMode = (AccumulationMode)s.accumulationMode;
    cs.isMotionVectorInWorldSpace = (s.flags & 1) != 0; cs.isHistoryConfidenceAvailable = (s.flags & 2) != 0;
    cs.isDisocclusionThresholdMixAvailable = (s.flags & 4) != 0; cs.enableValidation = (s.flags & 8) != 0;
    Result r = SetCommonSettings(*c->instance, cs);
    if (r != Result::SUCCESS) throwResult(env, "nrd::SetCommonSettings", r);
}

// setDenoiser(handle, identifier, kind (0 REBLUR, 1 RELAX, 2 SIGMA, 3 none), kv = flat {key, value, key, value, ...}); every other field keeps NRD's default
J(void, setDenoiser)(JNIEnv* env, jclass, jlong h, jint id, jint kind, jfloatArray kv) {
    Ctx* c = (Ctx*)(uintptr_t)h;
    std::vector<float> v(env->GetArrayLength(kv));
    env->GetFloatArrayRegion(kv, 0, (jsize)v.size(), v.data());
    Result r = Result::SUCCESS;
    if (kind == 0) {
        ReblurSettings s = {};
        for (size_t i = 0; i + 1 < v.size(); i += 2) {
            float x = v[i + 1];
            switch ((int)v[i]) {
                case 0: s.hitDistanceParameters.A = x; break; case 1: s.hitDistanceParameters.B = x; break; case 2: s.hitDistanceParameters.C = x; break;
                case 3: s.maxAccumulatedFrameNum = (uint32_t)x; break; case 4: s.maxFastAccumulatedFrameNum = (uint32_t)x; break;
                case 5: s.maxStabilizedFrameNum = (uint32_t)x; break; case 6: s.historyFixFrameNum = (uint32_t)x; break;
                case 7: s.diffusePrepassBlurRadius = x; break; case 8: s.specularPrepassBlurRadius = x; break;
                case 9: s.minBlurRadius = x; break; case 10: s.maxBlurRadius = x; break;
                case 11: s.lobeAngleFraction = x; break; case 12: s.roughnessFraction = x; break; case 13: s.planeDistanceSensitivity = x; break;
                case 14: s.checkerboardMode = (CheckerboardMode)(int)x; break; case 15: s.hitDistanceReconstructionMode = (HitDistanceReconstructionMode)(int)x; break;
                case 16: s.enableAntiFirefly = x != 0; break; case 17: s.antilagSettings.luminanceSigmaScale = x; break; case 18: s.antilagSettings.luminanceSensitivity = x; break;
                case 19: s.fastHistoryClampingSigmaScale = x; break; case 20: s.returnHistoryLengthInsteadOfOcclusion = x != 0; break;
                default: throwNrd(env, "setDenoiser", "unknown REBLUR key " + std::to_string((int)v[i])); return;
            }
        }
        r = SetDenoiserSettings(*c->instance, (Identifier)id, &s);
    } else if (kind == 1) {
        RelaxSettings s = {};
        for (size_t i = 0; i + 1 < v.size(); i += 2) {
            float x = v[i + 1];
            switch ((int)v[i]) {
                case 0: s.diffuseMaxAccumulatedFrameNum = (uint32_t)x; break; case 1: s.specularMaxAccumulatedFrameNum = (uint32_t)x; break;
                case 2: s.diffuseMaxFastAccumulatedFrameNum = (uint32_t)x; break; case 3: s.specularMaxFastAccumulatedFrameNum = (uint32_t)x; break;
                case 4: s.historyFixFrameNum = (uint32_t)x; break; case 5: s.diffusePrepassBlurRadius = x; break; case 6: s.specularPrepassBlurRadius = x; break;
                case 7: s.diffusePhiLuminance = x; break; case 8: s.specularPhiLuminance = x; break; case 9: s.lobeAngleFraction = x; break;
                case 10: s.roughnessFraction = x; break; case 11: s.atrousIterationNum = (uint32_t)x; break; case 12: s.depthThreshold = x; break;
                case 13: s.checkerboardMode = (CheckerboardMode)(int)x; break; case 14: s.hitDistanceReconstructionMode = (HitDistanceReconstructionMode)(int)x; break;
                case 15: s.enableAntiFirefly = x != 0; break; case 16: s.enableRoughnessEdgeStopping = x != 0; break;
                case 17: s.luminanceEdgeStoppingRelaxation = x; break; case 18: s.normalEdgeStoppingRelaxation = x; break; case 19: s.roughnessEdgeStoppingRelaxation = x; break;
                case 20: s.antilagSettings.accelerationAmount = x; break; case 21: s.antilagSettings.resetAmount = x; break;
                default: throwNrd(env, "setDenoiser", "unknown RELAX key " + std::to_string((int)v[i])); return;
            }
        }
        r = SetDenoiserSettings(*c->instance, (Identifier)id, &s);
    } else if (kind == 2) {
        SigmaSettings s = {};
        for (size_t i = 0; i + 1 < v.size(); i += 2) {
            float x = v[i + 1];
            switch ((int)v[i]) {
                case 0: s.lightDirection[0] = x; break; case 1: s.lightDirection[1] = x; break; case 2: s.lightDirection[2] = x; break;
                case 3: s.planeDistanceSensitivity = x; break; case 4: s.maxStabilizedFrameNum = (uint32_t)x; break; case 5: s.checkerboardMode = (CheckerboardMode)(int)x; break;
                default: throwNrd(env, "setDenoiser", "unknown SIGMA key " + std::to_string((int)v[i])); return;
            }
        }
        r = SetDenoiserSettings(*c->instance, (Identifier)id, &s);
    } else {
        r = SetDenoiserSettings(*c->instance, (Identifier)id, nullptr);
    }
    if (r != Result::SUCCESS) throwResult(env, "nrd::SetDenoiserSettings", r);
}

// Serialised dispatch list (int32 LE): n, then per dispatch {pipelineIndex, gridW, gridH, identifier, resourcesNum, cbSize, cbMatchesPrev, nameLen+name(padded), resources (descType, resType, indexInPool)*, cb bytes (padded)}
J(jobject, dispatches)(JNIEnv* env, jclass, jlong h, jintArray ids) {
    Ctx* c = (Ctx*)(uintptr_t)h;
    jsize n = env->GetArrayLength(ids);
    std::vector<jint> v(n);
    env->GetIntArrayRegion(ids, 0, n, v.data());
    std::vector<Identifier> idv(v.begin(), v.end());
    const DispatchDesc* dd = nullptr;
    uint32_t num = 0;
    Result r = GetComputeDispatches(*c->instance, idv.data(), (uint32_t)idv.size(), dd, num);
    if (r != Result::SUCCESS) { throwResult(env, "nrd::GetComputeDispatches", r); return nullptr; }
    c->dispBlob.clear();
    Out o{c->dispBlob};
    o.u32(num);
    for (uint32_t i = 0; i < num; i++) {
        const DispatchDesc& d = dd[i];
        o.u32(d.pipelineIndex); o.u32(d.gridWidth); o.u32(d.gridHeight); o.u32(d.identifier); o.u32(d.resourcesNum);
        o.u32(d.constantBufferDataSize); o.u32(d.constantBufferDataMatchesPreviousDispatch ? 1 : 0);
        o.str(d.name);
        for (uint32_t k = 0; k < d.resourcesNum; k++) { o.u32((uint32_t)d.resources[k].descriptorType); o.u32((uint32_t)d.resources[k].type); o.u32(d.resources[k].indexInPool); }
        o.bytes(d.constantBufferData, d.constantBufferDataSize);
    }
    return env->NewDirectByteBuffer(c->dispBlob.data(), (jlong)c->dispBlob.size());
}

J(jstring, resourceTypeName)(JNIEnv* env, jclass, jint t) { return env->NewStringUTF(GetResourceTypeString((ResourceType)t)); }
J(jstring, denoiserName)(JNIEnv* env, jclass, jint d) { return env->NewStringUTF(GetDenoiserString((Denoiser)d)); }

} // extern "C"
