#!/usr/bin/env python3
"""Replacement for AMD's Windows-only FidelityFX_SC.exe for the FSR 3.1 upscaler (SDK v1.1.4 GLSL tree).

For each pass x variant (base, wave64, 16bit, wave64_16bit) x option permutation:
  glslangValidator (system) -> SPIR-V -> reflect bindings from the SPIR-V -> dedupe -> write
  <pass>[_variant]_permutations.h in the layout ffx_fsr3upscaler_shaderblobs.cpp expects.
No Windows tool, no DXC. Offline compile only; never touches a GPU.
usage: gen_permutations.py SDK_ROOT OUT_DIR [--jobs N] [--only-passes a,b] [--max-perms N]
"""
import hashlib, itertools, os, struct, subprocess, sys, concurrent.futures as cf, argparse

ap = argparse.ArgumentParser()
ap.add_argument("sdk"); ap.add_argument("out")
ap.add_argument("--jobs", type=int, default=4)
ap.add_argument("--only-passes", default="")
ap.add_argument("--variants", default="base,wave64,16bit,wave64_16bit")
ap.add_argument("--effect", default="fsr3upscaler", choices=["fsr3upscaler","frameinterpolation","opticalflow"])
ap.add_argument("--glslang", default="glslangValidator")
a = ap.parse_args()

SDK = a.sdk; OUT = a.out
GPU = f"{SDK}/sdk/include/FidelityFX/gpu"
os.makedirs(OUT, exist_ok=True); os.makedirs(OUT + "/spv", exist_ok=True)
SH = f"{SDK}/sdk/src/backends/vk/shaders/{a.effect}"
P = {"fsr3upscaler": "FFX_FSR3UPSCALER_OPTION_", "frameinterpolation": "FFX_FRAMEINTERPOLATION_OPTION_", "opticalflow": "FFX_OPTICALFLOW_OPTION_"}[a.effect]
if a.effect == "fsr3upscaler":
    BASE = ["-DFFX_GPU=1", "-DFFX_GLSL=1",
        "-DFFX_FSR3UPSCALER_OPTION_UPSAMPLE_SAMPLERS_USE_DATA_HALF=0",
        "-DFFX_FSR3UPSCALER_OPTION_ACCUMULATE_SAMPLERS_USE_DATA_HALF=0",
        "-DFFX_FSR3UPSCALER_OPTION_REPROJECT_SAMPLERS_USE_DATA_HALF=1",
        "-DFFX_FSR3UPSCALER_OPTION_POSTPROCESSLOCKSTATUS_SAMPLERS_USE_DATA_HALF=0",
        "-DFFX_FSR3UPSCALER_OPTION_UPSAMPLE_USE_LANCZOS_TYPE=2"]
    OPTS = ["REPROJECT_USE_LANCZOS_TYPE", "HDR_COLOR_INPUT", "LOW_RESOLUTION_MOTION_VECTORS",
        "JITTERED_MOTION_VECTORS", "INVERTED_DEPTH", "APPLY_SHARPENING"]
elif a.effect == "frameinterpolation":
    BASE = ["-DFFX_GPU=1", "-DFFX_GLSL=1",
        "-DFFX_FRAMEINTERPOLATION_OPTION_UPSAMPLE_SAMPLERS_USE_DATA_HALF=0",
        "-DFFX_FRAMEINTERPOLATION_OPTION_ACCUMULATE_SAMPLERS_USE_DATA_HALF=0",
        "-DFFX_FRAMEINTERPOLATION_OPTION_REPROJECT_SAMPLERS_USE_DATA_HALF=1",
        "-DFFX_FRAMEINTERPOLATION_OPTION_POSTPROCESSLOCKSTATUS_SAMPLERS_USE_DATA_HALF=0",
        "-DFFX_FRAMEINTERPOLATION_OPTION_UPSAMPLE_USE_LANCZOS_TYPE=2"]
    OPTS = ["LOW_RES_MOTION_VECTORS", "JITTER_MOTION_VECTORS", "INVERTED_DEPTH"]
else:
    BASE = ["-DFFX_GPU=1", "-DFFX_GLSL=1"]
    OPTS = ["HDR_COLOR_INPUT"]
NPERM = 1 << len(OPTS)
PASSES = sorted(f[:-5] for f in os.listdir(SH) if f.endswith(".glsl"))
if a.only_passes:
    PASSES = [p for p in PASSES if p in a.only_passes.split(",")]
VARIANTS = {"base": ("", 0, 0), "wave64": ("_wave64", 1, 0), "16bit": ("_16bit", 0, 1), "wave64_16bit": ("_wave64_16bit", 1, 1)}

def compile_one(job):
    pas, var, idx = job
    suffix, w64, h16 = VARIANTS[var]
    spv = f"{OUT}/spv/{pas}{suffix}.{idx}.spv"
    d = [f"-D{P}{o}={(idx >> i) & 1}" for i, o in enumerate(OPTS)]
    d.append(f"-DFFX_HALF={h16}")
    if w64:  # GLSL path: SC passed nothing wave specific that we can see; keep a marker define only
        d.append("-DFFX_PREFER_WAVE64=1")
    cmd = [a.glslang, "-e", "main", "-S", "comp", "-V", "--target-env", "vulkan1.2", "-Os", *BASE, *d,
           f"-I{GPU}", f"-I{GPU}/{a.effect}", "-o", spv, f"{SH}/{pas}.glsl"]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        return job, None, (r.stdout + r.stderr)[-600:]
    return job, spv, ""

def reflect(path):
    w = struct.unpack("<%dI" % (os.path.getsize(path) // 4), open(path, "rb").read())
    i = 5; names = {}; bind = {}; setn = {}; deco = {}; types = {}; ptrs = {}; vars_ = []
    while i < len(w):
        n = w[i] >> 16; op = w[i] & 0xFFFF; ins = w[i:i + n]
        if op == 5:  # OpName
            raw = struct.pack("<%dI" % (n - 2), *ins[2:]); names[ins[1]] = raw.split(b"\0")[0].decode()
        elif op == 71:  # OpDecorate
            if ins[2] == 33: bind[ins[1]] = ins[3]
            elif ins[2] == 34: setn[ins[1]] = ins[3]
            deco.setdefault(ins[1], set()).add(ins[2])
        elif op in (25, 26, 27, 28, 30): types[ins[1]] = (op, ins)
        elif op == 32: ptrs[ins[1]] = (ins[2], ins[3])
        elif op == 59: vars_.append((ins[2], ins[1], ins[3]))
        i += n
    res = {k: [] for k in ("cb", "srvt", "uavt", "srvb", "uavb", "smp")}
    for vid, ptype, sc in vars_:
        if vid not in bind: continue
        _, pointee = ptrs[ptype]
        t = types.get(pointee)
        count = 1
        while t and t[0] == 28:  # array
            t = types.get(t[1][2])
        if not t: continue
        op, ins = t
        entry = (names.get(vid, f"var{vid}"), bind[vid], count, setn.get(vid, 0))
        if op == 25: res["srvt" if ins[7] == 1 else "uavt"].append(entry)
        elif op == 26: res["smp"].append(entry)
        elif op == 30:
            if sc == 2: res["cb"].append(entry)
            else: res["srvb" if 24 in deco.get(pointee, ()) else "uavb"].append(entry)
    return res

jobs = [(p, v, i) for p in PASSES for v in a.variants.split(",") for i in range(NPERM)]
results = {}
fail = 0
with cf.ThreadPoolExecutor(a.jobs) as ex:
    for job, spv, err in ex.map(compile_one, jobs):
        if spv is None:
            fail += 1
            if fail <= 5: print("FAIL", job, err, file=sys.stderr)
        else:
            results[job] = spv
print(f"compiled {len(results)} / {len(jobs)}, failed {fail}", file=sys.stderr)
if fail: sys.exit(2)

def carr(name, items, ctype):
    return f"static const {ctype} {name}[] = {{ {', '.join(items) if items else '0'} }};\n"

total_unique = 0; total_bytes = 0
for pas in PASSES:
    for var, (suffix, _, _) in VARIANTS.items():
        if var not in a.variants.split(","): continue
        base = f"{pas}{suffix}"
        uniq = {}; table = []
        for idx in range(NPERM):
            data = open(results[(pas, var, idx)], "rb").read()
            h = hashlib.sha1(data).hexdigest()
            if h not in uniq: uniq[h] = (len(uniq), data, reflect(results[(pas, var, idx)]))
            table.append(uniq[h][0])
        total_unique += len(uniq); total_bytes += sum(len(u[1]) for u in uniq.values())
        o = ["// GENERATED by gen_permutations.py (replacement for AMD FidelityFX_SC.exe). Do not edit.\n#pragma once\n#include <stdint.h>\n"]
        o.append("typedef union %s_PermutationKey {\n  struct {\n" % base)
        o += [f"    uint32_t {P}{n} : 1;\n" for n in OPTS]
        o.append("  };\n  uint32_t index;\n} %s_PermutationKey;\n" % base)
        o.append("typedef struct %s_PermutationInfo {\n  const uint32_t blobSize; const unsigned char* blobData;\n" % base)
        o.append("  uint32_t numConstantBuffers; uint32_t numSRVTextures; uint32_t numUAVTextures; uint32_t numSRVBuffers; uint32_t numUAVBuffers; uint32_t numSamplers; uint32_t numRTAccelerationStructures;\n")
        for pre in ("constantBuffer", "srvTexture", "uavTexture", "srvBuffer", "uavBuffer", "sampler", "rtAccelerationStructure"):
            o.append(f"  const char** {pre}Names; const uint32_t* {pre}Bindings; const uint32_t* {pre}Counts; const uint32_t* {pre}Spaces;\n")
        o.append("} %s_PermutationInfo;\n" % base)
        o.append("static const int32_t g_%s_IndirectionTable[] = { %s };\n" % (base, ", ".join(map(str, table))))
        infos = []
        for h, (k, data, rf) in sorted(uniq.items(), key=lambda kv: kv[1][0]):
            o.append("static const unsigned char g_%s_blob_%d[] = { %s };\n" % (base, k, ",".join(str(b) for b in data)))
            fields = []
            for key, pre in (("cb", "constantBuffer"), ("srvt", "srvTexture"), ("uavt", "uavTexture"), ("srvb", "srvBuffer"), ("uavb", "uavBuffer"), ("smp", "sampler")):
                es = sorted(rf[key], key=lambda e: e[1])
                o.append(f"static const char* g_{base}_{k}_{pre}N[] = {{ {', '.join('\"%s\"' % e[0] for e in es) or '0'} }};\n")
                o.append(carr(f"g_{base}_{k}_{pre}B", [str(e[1]) for e in es], "uint32_t"))
                o.append(carr(f"g_{base}_{k}_{pre}C", [str(e[2]) for e in es], "uint32_t"))
                o.append(carr(f"g_{base}_{k}_{pre}S", [str(e[3]) for e in es], "uint32_t"))
            row = [f"sizeof(g_{base}_blob_{k})", f"g_{base}_blob_{k}", *[str(len(rf[x])) for x in ("cb", "srvt", "uavt", "srvb", "uavb", "smp")], "0"]
            for pre in ("constantBuffer", "srvTexture", "uavTexture", "srvBuffer", "uavBuffer", "sampler"):
                row += [f"g_{base}_{k}_{pre}N", f"g_{base}_{k}_{pre}B", f"g_{base}_{k}_{pre}C", f"g_{base}_{k}_{pre}S"]
            row += ["0", "0", "0", "0"]
            infos.append("  { " + ", ".join(row) + " }")
        o.append("static const %s_PermutationInfo g_%s_PermutationInfo[] = {\n%s\n};\n" % (base, base, ",\n".join(infos)))
        open(f"{OUT}/{base}_permutations.h", "w").write("".join(o))
print(f"unique blobs {total_unique}, total SPIR-V bytes {total_bytes}", file=sys.stderr)
