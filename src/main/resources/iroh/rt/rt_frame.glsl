// Iroh RT core (P5-0): per-frame constants, one std430 block shared by every pass. Mirrors RtFrameData.java.
struct Frame {
    mat4 invViewProj;   // current, unjittered
    mat4 viewProj;
    mat4 prevViewProj;
    vec4 camPos;        // xyz world
    vec4 camFwd;        // xyz unit forward (viewZ = dot(p - camPos, camFwd))
    vec4 prevCamPos;
    vec4 prevCamFwd;
    vec4 sunDir;        // xyz towards the sun, w = cos(angular radius)
    vec4 sunE;          // rgb irradiance at normal incidence, w = angular radius (rad), 0 = hard
    vec4 skyZenith;
    vec4 skyHorizon;
    vec4 fog;           // density, height falloff, base height, enabled (>0)
    uvec4 dims;         // width, height, frame index, flags
    uvec4 counts;       // light count, cache mask (size-1), min cache samples, max cache samples
    vec4 params;        // cache cell base, cache LOD distance, RIS light candidates, polished roughness override (0 = material)
    vec4 water;         // extinction per block rgb, unused
};
#define F_ALPHA_TEST 1u
#define F_CACHE 2u
#define F_SOFT_SUN 4u
#define F_REJECT 8u
#define F_CACHE_UPDATE_ALL 16u
