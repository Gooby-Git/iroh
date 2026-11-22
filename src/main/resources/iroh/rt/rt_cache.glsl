// Iroh RT core (P5-0): world-space hash-grid radiance cache (own design; no SHARC). Entry = 12 uints:
//   0 checksum (0 = empty), 1 samples this frame, 2..4 this frame's radiance sum (fixed point x256),
//   5..7 resolved radiance (float bits), 8 resolved sample count (capped: temporal blend), 9 last frame touched.
// Key = quantised position at a distance-dependent cell size (LOD doubles every lodDist blocks) + the face's axis
// direction (6 values): the two sides of a wall never share a cell (leak control), nor do floor and wall at a corner.
const uint CACHE_STRIDE = 12u;
const uint CACHE_PROBES = 8u;

float cacheCell(vec3 p) {
    float d = distance(p, fr.camPos.xyz);
    return fr.params.x * exp2(floor(log2(max(1.0, d / fr.params.y))));
}

uint axisId(vec3 n) {
    vec3 a = abs(n);
    if (a.x >= a.y && a.x >= a.z) return n.x > 0.0 ? 0u : 1u;
    if (a.y >= a.z) return n.y > 0.0 ? 2u : 3u;
    return n.z > 0.0 ? 4u : 5u;
}

void cacheKey(vec3 p, vec3 n, out uint slot, out uint check) {
    float s = cacheCell(p);
    ivec3 q = ivec3(floor((p - n * 0.01) / s));
    uint lv = uint(log2(s / fr.params.x) + 0.5);
    uint h1 = pcg(uint(q.x) + pcg(uint(q.y) + pcg(uint(q.z) + pcg(axisId(n) + lv * 8u))));
    uint h2 = pcg(h1 ^ 0x9E3779B9u) | 1u;
    slot = h1 & fr.counts.y;
    check = h2;
}

/** Resolved radiance and sample count of the cell, or count 0. */
vec4 cacheLookup(vec3 p, vec3 n) {
    uint slot, check;
    cacheKey(p, n, slot, check);
    for (uint k = 0u; k < CACHE_PROBES; k++) {
        uint e = ((slot + k) & fr.counts.y) * CACHE_STRIDE;
        uint c = cache[e];
        if (c == check) return vec4(uintBitsToFloat(cache[e + 5u]), uintBitsToFloat(cache[e + 6u]), uintBitsToFloat(cache[e + 7u]), float(cache[e + 8u]));
        if (c == 0u) break;
    }
    return vec4(0.0);
}

void cacheAdd(vec3 p, vec3 n, vec3 L) {
    uint slot, check;
    cacheKey(p, n, slot, check);
    for (uint k = 0u; k < CACHE_PROBES; k++) {
        uint e = ((slot + k) & fr.counts.y) * CACHE_STRIDE;
        uint prev = atomicCompSwap(cache[e], 0u, check);
        if (prev == 0u || prev == check) {
            uvec3 f = uvec3(clamp(L, 0.0, 4096.0) * 256.0 + 0.5);
            atomicAdd(cache[e + 1u], 1u);
            atomicAdd(cache[e + 2u], f.x);
            atomicAdd(cache[e + 3u], f.y);
            atomicAdd(cache[e + 4u], f.z);
            return;
        }
    }
}
