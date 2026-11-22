// Iroh RT core (P5-0): scene access + sampling shared by the ray-query passes. Bindings 0..8 are fixed for every
// tracing pass; pass-specific buffers start at 9. All geometry comes from the RASTER vertex arena (16-byte terrain
// vertices), read through the per-instance record table: no second copy of the scene for RT.
#extension GL_EXT_ray_query : require
#include "rt_frame.glsl"

layout(set = 0, binding = 0) uniform accelerationStructureEXT tlas;
layout(std430, set = 0, binding = 1) readonly buffer FrameB { Frame fr; };
layout(std430, set = 0, binding = 2) readonly buffer Verts { uvec4 verts[]; };
layout(std430, set = 0, binding = 3) readonly buffer Inst { uvec4 inst[]; };     // first vertex of geometry 0, 1; flags
layout(std430, set = 0, binding = 4) readonly buffer Atlas { uint atlas[]; };    // RGBA8, 128x128, 8x8 tiles of 16x16
layout(std430, set = 0, binding = 5) readonly buffer Mats { vec4 mats[]; };      // 2 per tile
layout(std430, set = 0, binding = 6) readonly buffer Lights { vec4 lights[]; };  // 5 per emissive quad
layout(std430, set = 0, binding = 7) readonly buffer GBuf { uvec4 gbuf[]; };     // packed G-buffer (RtGBuffer layout)
layout(std430, set = 0, binding = 8) buffer Counters { uint counters[]; };      // rays traced per pass

const float PI = 3.14159265358979;
const float EPS = 1e-3;
const float TMAX = 1000.0;
const uint MASK_SOLID = 1u, MASK_TRANSLUCENT = 2u, MASK_ENTITY = 4u, MASK_SELF = 8u, MASK_ALL = 0xFFu;
// P5-3 entity masks: entity part instances carry SOLID|ENTITY (seen by every solid ray); the first-person player's own body
// carries SELF only, so it casts sun/block-light shadows and shows in reflections, but GI rays (SOLID) skip it.
const uint MASK_SHADOW = MASK_SOLID | MASK_SELF, MASK_REFL = MASK_SOLID | MASK_SELF;
const int ATLAS_SIZE = 128, TILE = 16, TILES_X = 8;

// ---------------------------------------------------------------------------------------------------- random
uint pcg(uint v) { uint s = v * 747796405u + 2891336453u; uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u; return (w >> 22u) ^ w; }
uint rngState;
void rngInit(uvec2 px, uint frame, uint salt) { rngState = pcg(px.x + pcg(px.y + pcg(frame * 7919u + salt))); }
float rnd() { rngState = pcg(rngState); return float(rngState >> 8) * (1.0 / 16777216.0); }

#include "rt_look.glsl"

// ---------------------------------------------------------------------------------------------------- packing
vec2 octWrap(vec2 v) { return (1.0 - abs(v.yx)) * vec2(v.x >= 0.0 ? 1.0 : -1.0, v.y >= 0.0 ? 1.0 : -1.0); }
vec3 octDecode(vec2 e) { e = e * 2.0 - 1.0; vec3 n = vec3(e.xy, 1.0 - abs(e.x) - abs(e.y)); if (n.z < 0.0) n.xy = octWrap(n.xy); return normalize(n); }

// ---------------------------------------------------------------------------------------------------- vertices
vec3 vpos(uvec4 v) { return vec3(ivec3(int(v.x << 16) >> 16, int(v.x) >> 16, int(v.y << 16) >> 16)) * (1.0 / 2048.0) + 4.0; }
vec2 vuv(uvec4 v) { return vec2(float(v.z & 0xFFFFu), float(v.z >> 16)) * (1.0 / 32768.0); }

void triVerts(uint ci, uint geom, uint prim, out uvec4 a, out uvec4 b, out uvec4 c) {
    uint first = inst[ci][geom] + (prim >> 1) * 4u;
    if ((prim & 1u) == 0u) { a = verts[first]; b = verts[first + 1u]; c = verts[first + 2u]; }
    else { a = verts[first + 2u]; b = verts[first + 3u]; c = verts[first]; }
}

int tileOf(vec2 ua, vec2 ub, vec2 uc) { vec2 m = (ua + ub + uc) * (1.0 / 3.0); return int(m.x * 8.0) + 8 * int(m.y * 8.0); }

vec4 texel(int tile, vec2 uv) {
    ivec2 t0 = ivec2(tile % TILES_X, tile / TILES_X) * TILE;
    ivec2 p = clamp(ivec2(floor(uv * float(ATLAS_SIZE))), t0, t0 + TILE - 1);
    return unpackUnorm4x8(atlas[p.y * ATLAS_SIZE + p.x]);
}

// material: m0 = (roughness, alphaMode, water, spec), m1 = (emission rgb, f0)
vec4 mat0(int tile) { return mats[tile * 2]; }
vec4 mat1(int tile) { return mats[tile * 2 + 1]; }

float candidateAlpha(uint ci, uint geom, uint prim, vec2 bary) {
    uvec4 a, b, c;
    triVerts(ci, geom, prim, a, b, c);
    vec2 ua = vuv(a), ub = vuv(b), uc = vuv(c);
    int tile = tileOf(ua, ub, uc);
    if (mat0(tile).y != 1.0) return 1.0;
    vec2 uv = ua * (1.0 - bary.x - bary.y) + ub * bary.x + uc * bary.y;
    return texel(tile, uv).a;
}

// ---------------------------------------------------------------------------------------------------- tracing
bool alphaTestOn() { return (fr.dims.w & F_ALPHA_TEST) != 0u; }

void resolveCandidates(rayQueryEXT rq) {
    while (rayQueryProceedEXT(rq)) {
        if (rayQueryGetIntersectionTypeEXT(rq, false) == gl_RayQueryCandidateIntersectionTriangleEXT) {
            uint ci = uint(rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false));
            uint g = uint(rayQueryGetIntersectionGeometryIndexEXT(rq, false));
            uint p = uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false));
            vec2 b = rayQueryGetIntersectionBarycentricsEXT(rq, false);
            if (candidateAlpha(ci, g, p, b) >= 0.5) rayQueryConfirmIntersectionEXT(rq);
        }
    }
}

/** True when nothing blocks o -> o + d * tmax (sun / light visibility). */
bool visible(vec3 o, vec3 d, float tmax, uint mask) {
    rayQueryEXT rq;
    uint flags = gl_RayFlagsTerminateOnFirstHitEXT | (alphaTestOn() ? 0u : gl_RayFlagsOpaqueEXT);
    rayQueryInitializeEXT(rq, tlas, flags, mask, o, 0.0, d, tmax);
    resolveCandidates(rq);
    return rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionNoneEXT;
}

struct Hit { bool hit; float t; vec3 pos; vec3 n; int tile; vec3 albedo; uint ci; uint prim; };

Hit traceClosest(vec3 o, vec3 d, float tmax, uint mask) {
    rayQueryEXT rq;
    rayQueryInitializeEXT(rq, tlas, alphaTestOn() ? gl_RayFlagsNoneEXT : gl_RayFlagsOpaqueEXT, mask, o, 0.0, d, tmax);
    resolveCandidates(rq);
    Hit h;
    h.hit = rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionTriangleEXT;
    if (!h.hit) return h;
    h.t = rayQueryGetIntersectionTEXT(rq, true);
    h.pos = o + d * h.t;
    h.ci = uint(rayQueryGetIntersectionInstanceCustomIndexEXT(rq, true));
    uint g = uint(rayQueryGetIntersectionGeometryIndexEXT(rq, true));
    h.prim = uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, true));
    vec2 bary = rayQueryGetIntersectionBarycentricsEXT(rq, true);
    uvec4 a, b, c;
    triVerts(h.ci, g, h.prim, a, b, c);
    // P5-3: entity parts are posed by a full 3x4 instance transform, so the face normal comes from the triangle in WORLD
    // space (exact for any affine pose, incl. non-uniform part scale; terrain's translation-only instances are unchanged)
    mat4x3 o2w = rayQueryGetIntersectionObjectToWorldEXT(rq, true);
    vec3 pa = o2w * vec4(vpos(a), 1.0), pb = o2w * vec4(vpos(b), 1.0), pc = o2w * vec4(vpos(c), 1.0);
    vec3 n = normalize(cross(pb - pa, pc - pa));
    h.n = dot(n, d) > 0.0 ? -n : n;
    vec2 ua = vuv(a), ub = vuv(b), uc = vuv(c);
    h.tile = tileOf(ua, ub, uc);
    vec2 uv = ua * (1.0 - bary.x - bary.y) + ub * bary.x + uc * bary.y;
    h.albedo = texel(h.tile, uv).rgb * unpackUnorm4x8(a.w).rgb;
    if (lookOn()) h.albedo = srgbDecode(h.albedo);
    return h;
}

// ---------------------------------------------------------------------------------------------------- sampling
void onb(vec3 n, out vec3 t, out vec3 b) {
    float s = n.z >= 0.0 ? 1.0 : -1.0;
    float a = -1.0 / (s + n.z), c = n.x * n.y * a;
    t = vec3(1.0 + s * n.x * n.x * a, s * c, -s * n.x);
    b = vec3(c, s + n.y * n.y * a, -n.y);
}

vec3 cosineDir(vec3 n) {
    float u1 = rnd(), u2 = rnd();
    float r = sqrt(u1), phi = 2.0 * PI * u2;
    vec3 t, b;
    onb(n, t, b);
    return normalize(t * (r * cos(phi)) + b * (r * sin(phi)) + n * sqrt(max(0.0, 1.0 - u1)));
}

vec3 sunSampleDir() {
    vec3 s = fr.sunDir.xyz;
    if ((fr.dims.w & F_SOFT_SUN) == 0u || fr.sunE.w <= 0.0) return s;
    float cosMax = fr.sunDir.w;
    float u1 = rnd(), u2 = rnd();
    float ct = 1.0 - u1 * (1.0 - cosMax), st = sqrt(max(0.0, 1.0 - ct * ct)), phi = 2.0 * PI * u2;
    vec3 t, b;
    onb(s, t, b);
    return normalize(t * (st * cos(phi)) + b * (st * sin(phi)) + s * ct);
}

/** One-sample sun irradiance at (x, n): E_sun * cos * visibility, cone-sampled for a soft penumbra. */
vec3 sunIrradiance(vec3 x, vec3 n, inout uint rays) {
    vec3 l = sunSampleDir();
    float c = dot(n, l);
    if (c <= 0.0) return vec3(0.0);
    rays++;
    return visible(x + n * EPS, l, TMAX, MASK_SHADOW) ? fr.sunE.rgb * c * cloudShadow(x) : vec3(0.0);
}

/**
 * Uniform sampling of the solid angle a rectangle subtends from o (Urena, Fajardo, King 2013, "An Area-Preserving
 * Parametrization for Spherical Rectangles"). Returns false when the rectangle is (nearly) degenerate from o. Removes
 * the 1/d^2 tail of area sampling for surfaces touching an emissive block (torches on walls, glowstone in floors).
 */
bool sampleSphericalRect(vec3 o, vec3 s, vec3 ex, vec3 ey, float u, float v, out vec3 p, out float solidAngle) {
    float exl = length(ex), eyl = length(ey);
    vec3 x = ex / exl, y = ey / eyl, z = cross(x, y);
    vec3 d = s - o;
    float z0 = dot(d, z);
    if (z0 > 0.0) { z = -z; z0 = -z0; }
    if (z0 > -1e-6) return false;
    float x0 = dot(d, x), y0 = dot(d, y), x1 = x0 + exl, y1 = y0 + eyl;
    vec3 v00 = vec3(x0, y0, z0), v01 = vec3(x0, y1, z0), v10 = vec3(x1, y0, z0), v11 = vec3(x1, y1, z0);
    vec3 n0 = normalize(cross(v00, v10)), n1 = normalize(cross(v10, v11)), n2 = normalize(cross(v11, v01)), n3 = normalize(cross(v01, v00));
    float g0 = acos(clamp(-dot(n0, n1), -1.0, 1.0)), g1 = acos(clamp(-dot(n1, n2), -1.0, 1.0));
    float g2 = acos(clamp(-dot(n2, n3), -1.0, 1.0)), g3 = acos(clamp(-dot(n3, n0), -1.0, 1.0));
    float b0 = n0.z, b1 = n2.z, k = 2.0 * PI - g2 - g3;
    float S = g0 + g1 - k;
    if (!(S > 1e-5)) return false;
    float au = u * S + k;
    float fu = (cos(au) * b0 - b1) / sin(au);
    float cu = clamp((fu > 0.0 ? 1.0 : -1.0) / sqrt(fu * fu + b0 * b0), -1.0, 1.0);
    float xu = clamp(-(cu * z0) / max(sqrt(1.0 - cu * cu), 1e-7), x0, x1);
    float dd = sqrt(xu * xu + z0 * z0);
    float h0 = y0 / sqrt(dd * dd + y0 * y0), h1 = y1 / sqrt(dd * dd + y1 * y1);
    float hv = h0 + v * (h1 - h0), hv2 = hv * hv;
    float yv = hv2 < 1.0 - 1e-6 ? (hv * dd) / sqrt(1.0 - hv2) : y1;
    p = o + xu * x + yv * y + z0 * z;
    solidAngle = S;
    return true;
}

/** One light candidate: light picked by power (CDF), point uniform in solid angle (near) or area (far). */
bool lightCandidate(vec3 x, vec3 n, out vec3 w, out float dist, out float invPdfOmega, out uint li) {
    uint count = fr.counts.x;
    float u = rnd();
    uint lo = 0u, hi = count - 1u;
    while (lo < hi) { uint mid = (lo + hi) >> 1; if (lights[mid * 5u + 1u].w < u) lo = mid + 1u; else hi = mid; }
    li = lo;
    vec4 L0 = lights[lo * 5u], L1 = lights[lo * 5u + 1u], L2 = lights[lo * 5u + 2u], L3 = lights[lo * 5u + 3u];
    float r1 = rnd(), r2 = rnd();
    if (dot(x - L0.xyz, L3.xyz) <= 0.0) return false;          // behind the emitting face
    // near field: solid-angle sampling (no 1/d^2 tail); far field (> ~3 sizes away): area sampling, whose variance is
    // already low there and which avoids the float cancellation of the spherical-rectangle setup at small angles
    vec3 c = L0.xyz + 0.5 * (L1.xyz + L2.xyz) - x;
    bool near = dot(c, c) < 9.0 * (dot(L1.xyz, L1.xyz) + dot(L2.xyz, L2.xyz));
    vec3 p;
    float S;
    bool solid = near && sampleSphericalRect(x, L0.xyz, L1.xyz, L2.xyz, r1, r2, p, S);
    if (!solid) p = L0.xyz + L1.xyz * r1 + L2.xyz * r2;
    w = p - x;
    float d2 = dot(w, w);
    dist = sqrt(d2);
    w /= dist;
    float cl = -dot(L3.xyz, w);
    if (dot(n, w) <= 0.0 || cl <= 0.0) return false;
    invPdfOmega = (solid ? S : cl * L0.w / d2) / L2.w;          // 1 / (p(light) * pdf_omega(point))
    return true;
}

/**
 * Emissive-quad irradiance at (x, n): resampled importance sampling (RIS) over fr.params.z candidates (target = the
 * unshadowed contribution Le_lum * cos), ONE shadow ray to the survivor. M = 1 is plain light sampling. This is the
 * single-frame half of ReSTIR DI; temporal/spatial reservoir reuse is the upgrade (docs/P5-DESIGN.md section 5).
 */
vec3 emitIrradiance(vec3 x, vec3 n, inout uint rays) {
    if (fr.counts.x == 0u) return vec3(0.0);
    uint M = max(1u, uint(fr.params.z));
    float wsum = 0.0, selPhat = 0.0, selDist = 0.0;
    vec3 selW = vec3(0.0);
    uint selL = 0u;
    for (uint k = 0u; k < M; k++) {
        vec3 w;
        float dist, invPdf;
        uint li;
        if (!lightCandidate(x, n, w, dist, invPdf, li)) continue;
        vec3 le = lights[li * 5u + 4u].rgb;
        float phat = dot(le, vec3(0.2126, 0.7152, 0.0722)) * dot(n, w);
        float wt = phat * invPdf;
        wsum += wt;
        if (rnd() * wsum < wt) { selW = w; selDist = dist; selPhat = phat; selL = li; }
    }
    if (wsum <= 0.0 || selPhat <= 0.0) return vec3(0.0);
    rays++;
    if (!visible(x + n * EPS, selW, selDist - 2.0 * EPS, MASK_SHADOW)) return vec3(0.0);
    vec3 le = lights[selL * 5u + 4u].rgb;
    return le * (dot(n, selW) / selPhat) * (wsum / float(M));
}

vec3 sky(vec3 d) {
    if (lookOn()) return lookSky(d);
    if (d.y < 0.0) return fr.skyHorizon.rgb * 0.3;
    return mix(fr.skyHorizon.rgb, fr.skyZenith.rgb, sqrt(d.y));
}

/** Radiance of the sun disc seen along d (shown on primary sky pixels and mirror reflections only; NEE covers it elsewhere). */
vec3 sunDisc(vec3 d) {
    if ((fr.dims.w & F_SOFT_SUN) == 0u || fr.sunE.w <= 0.0) return vec3(0.0);
    if (dot(d, fr.sunDir.xyz) < fr.sunDir.w) return vec3(0.0);
    return fr.sunE.rgb / (2.0 * PI * (1.0 - fr.sunDir.w));
}

float schlick(float f0, float c) { float m = clamp(1.0 - c, 0.0, 1.0); float m2 = m * m; return f0 + (1.0 - f0) * m2 * m2 * m; }

/** Direct-only radiance leaving a hit towards the ray origin: emission + diffuse (sun + one light sample). */
vec3 shadeDirect(Hit h, inout uint rays) {
    vec4 m1 = mat1(h.tile);
    return m1.rgb + h.albedo / PI * (sunIrradiance(h.pos, h.n, rays) + emitIrradiance(h.pos, h.n, rays));
}

// ---------------------------------------------------------------------------------------------------- G-buffer
struct Surf { bool valid; vec3 pos; vec3 n; vec3 albedo; int tile; float viewZ; vec2 mv; vec3 dir; bool skyOpen; };

vec3 primaryDir(uvec2 px) {
    vec2 ndc = (vec2(px) + 0.5) / vec2(fr.dims.xy) * 2.0 - 1.0;
    vec4 far = fr.invViewProj * vec4(ndc, 1.0, 1.0);
    return normalize(far.xyz / far.w - fr.camPos.xyz);
}

Surf surface(uvec2 px) {
    Surf s;
    uvec4 g = gbuf[px.y * fr.dims.x + px.x];
    s.dir = primaryDir(px);
    s.viewZ = uintBitsToFloat(g.z);
    s.valid = s.viewZ > 0.0;
    if (!s.valid) return s;
    s.n = octDecode(unpackUnorm2x16(g.x));
    vec4 al = unpackUnorm4x8(g.y);
    s.albedo = lookOn() ? srgbDecode(al.rgb) : al.rgb;   // look: textures and tints are sRGB (as Minecraft's); P5-0 steps keep raw values
    s.tile = int((g.y >> 24) & 63u);
    s.skyOpen = ((g.y >> 30) & 1u) != 0u;
    s.mv = unpackHalf2x16(g.w);
    s.pos = fr.camPos.xyz + s.dir * (s.viewZ / dot(s.dir, fr.camFwd.xyz));
    return s;
}
