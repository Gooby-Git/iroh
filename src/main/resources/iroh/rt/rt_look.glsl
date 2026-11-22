// Iroh RT look (P5-1). Active when the frame flag F_LOOK is set; without it the RT core keeps the P5-0 analytic test
// atmosphere the CPU reference models. Contents and provenance:
//  - lookSky: OWN analytic sky (zenith/horizon gradient, sunset band, Henyey-Greenstein 1941 forward glare, hashed stars).
//    Its colours arrive in the frame block already multiplied in Java by the owner's Navidson multipliers
//    (NV_FANTASY_SKY / NV_FANTASY_SUNSET / NV_NIGHT_SKY, owner constants under the 2026-11-15 LGPL grant). No
//    Complementary code or constants (knowledge/navidson.md section 1).
//  - mountainFog: the owner's NvMountainFog formula (lib/navidson, LGPL grant), with Iroh's own value noise in place of
//    the pack's noise texture.
//  - waveNormal: own sum of directional sines (analytic gradient), for water reflection and refraction.
//  - cloudShadow: the Tempest cloud-shadow HOOK (stub: procedural cover; in game it becomes a lookup of Tempest's
//    nvTempestClouds map, R = cover, 32 blocks per texel, repeat 8192).
// Requires: fr (Frame), PI.

float lkHash(vec2 p) { uvec2 q = uvec2(ivec2(floor(p))) * uvec2(1597334673u, 3812015801u); uint n = (q.x ^ q.y) * 1597334673u; return float(n >> 8) * (1.0 / 16777216.0); }

float lkNoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(lkHash(i), lkHash(i + vec2(1, 0)), u.x), mix(lkHash(i + vec2(0, 1)), lkHash(i + vec2(1, 1)), u.x), u.y);
}

bool lookOn() { return (fr.dims.w & F_LOOK) != 0u; }

vec3 lkStars(vec3 d) {
    // cube-map cells on the unit sphere, at most one soft star per cell; fixed to the world (in game: turns with the sky)
    vec3 a = abs(d);
    vec2 uv; float face;
    if (a.x >= a.y && a.x >= a.z) { uv = d.yz / a.x; face = d.x > 0.0 ? 0.0 : 1.0; }
    else if (a.y >= a.z) { uv = d.xz / a.y; face = d.y > 0.0 ? 2.0 : 3.0; }
    else { uv = d.xy / a.z; face = d.z > 0.0 ? 4.0 : 5.0; }
    vec2 g = uv * 90.0 + face * 512.0;
    vec2 c = floor(g);
    float h = lkHash(c), h2 = lkHash(c + 71.3);
    if (h < 0.93) return vec3(0.0);
    vec2 sp = c + 0.25 + 0.5 * vec2(h2, fract(h2 * 7.7));
    float r = length(g - sp);
    float s = max(0.0, 1.0 - r / 0.55);
    vec3 col = h2 < 0.3 ? vec3(0.70, 0.80, 1.0) : h2 < 0.75 ? vec3(1.0, 0.97, 0.92) : vec3(1.0, 0.82, 0.62);
    return col * s * s * (0.4 + 3.0 * (h - 0.93) / 0.07) * 0.06;
}

/** The look sky for a world direction (radiance, scene-linear). */
vec3 lookSky(vec3 d) {
    float up = d.y;
    float hz = 1.0 - max(up, 0.0);
    vec3 c = mix(fr.skyUp.rgb, fr.skyMid.rgb, hz * hz * hz);
    float vs = max(dot(normalize(vec3(d.x, 0.0, d.z) + vec3(1e-5)), normalize(vec3(fr.sunDir.x, 0.0, fr.sunDir.z) + vec3(1e-5))), 0.0);
    float band = pow(1.0 - abs(up), 5.0) * (0.25 + 0.75 * vs * vs * vs) * fr.skyUp.w;
    c = mix(c, fr.skySunset.rgb, clamp(band, 0.0, 1.0));
    if (up < 0.0) c = mix(c, fr.skyDown.rgb, smoothstep(0.0, 0.3, -up));
    // forward glare around the NEE light (sun by day, moon by night): Henyey-Greenstein, g = 0.8
    float ct = dot(d, fr.sunDir.xyz), g = 0.8;
    float ph = (1.0 - g * g) / (4.0 * PI * pow(1.0 + g * g - 2.0 * g * ct, 1.5));
    vec3 e = fr.sunE.rgb;
    c += fr.fogColor.w * ph * e * (1.0 - 0.7 * fr.skySunset.w);
    if (fr.skyMid.w > 0.0 && up > 0.0) c += lkStars(d) * fr.skyMid.w * smoothstep(0.0, 0.2, up) * (1.0 - fr.skySunset.w);
    return max(c, vec3(0.0));
}

/** Owner's NvMountainFog (Navidson lib, LGPL grant): world-anchored mist banks + distance haze; o = camera, p = point. */
float mountainFog(vec3 o, vec3 p, float viewDist) {
    if (fr.mfog0.w <= 0.0 && fr.mfog1.y <= 0.0) return 0.0;
    float A = fr.mfog0.x, H = fr.mfog0.y, P = fr.mfog1.x, t = fr.skyDown.w;
    vec2 drift = vec2(0.35, 0.12) * t;
    float n1 = lkNoise((p.xz + drift) / 3072.0 * 64.0) , n2 = lkNoise((p.xz - 0.7 * drift) / 1100.0 * 64.0 + 0.37);
    float base = A + P * (n1 - 0.5) * 2.0 * H;
    float thickness = mix(1.0, smoothstep(0.2, 0.75, n2) * 1.6, P);
    float yc = o.y, yp = p.y;
    float Ic = yc < base ? yc - base - H : -H * exp((base - yc) / H);
    float Ip = yp < base ? yp - base - H : -H * exp((base - yp) / H);
    float density = abs(yp - yc) > 0.5 ? (Ip - Ic) / (yp - yc) : (yp < base ? 1.0 : exp((base - yp) / H));
    float dist = max(0.0, viewDist - fr.mfog1.w);
    float mist = (1.0 - exp(-dist * density * fr.mfog0.z * thickness)) * fr.mfog0.w;
    float hz = 1.0 - exp2(-dist * 0.4 / max(fr.mfog1.z, 1.0));
    float haze = fr.mfog1.y * hz * hz;
    return 1.0 - (1.0 - mist) * (1.0 - haze);
}

/** Water surface normal (own: four directional sines, analytic gradient); up = +1 seen from above, -1 from below. */
vec3 waveNormal(vec3 p, float up) {
    float t = fr.water2.y, a = fr.water2.x;
    vec2 g = vec2(0.0);
    const vec4 W[4] = vec4[4](vec4(0.83, 0.55, 1.9, 1.3), vec4(-0.42, 0.91, 2.7, 1.7), vec4(0.21, -0.98, 4.1, 2.3), vec4(-0.95, -0.31, 6.3, 3.1));
    for (int k = 0; k < 4; k++) {
        float ph = dot(W[k].xy, p.xz) * W[k].z + t * W[k].w;
        g += W[k].xy * W[k].z * cos(ph) / W[k].z * (1.0 / float(k + 1));
    }
    return normalize(vec3(-g.x * a, up, -g.y * a));
}

/** Tempest cloud-shadow hook (stub): 1 = no cloud. Cloud deck shadow projected along the sun direction. */
float cloudShadow(vec3 p) {
    if ((fr.dims.w & F_CLOUD_SHADOW) == 0u || fr.cloud.x <= 0.0) return 1.0;
    vec2 q = (p.xz + fr.sunDir.xz / max(fr.sunDir.y, 0.1) * 40.0 + fr.cloud.zw) / max(fr.cloud.y, 1.0);
    float n = lkNoise(q) * 0.65 + lkNoise(q * 2.3 + 4.1) * 0.35;
    return 1.0 - fr.cloud.x * smoothstep(0.45, 0.7, n);
}

/** Rain wetness on a surface: up-facing, open to the sky. */
float wetness(vec3 n, bool skyOpen) {
    if (!lookOn() || fr.wet.x <= 0.0 || !skyOpen) return 0.0;
    return fr.wet.x * smoothstep(0.6, 0.95, n.y);
}
