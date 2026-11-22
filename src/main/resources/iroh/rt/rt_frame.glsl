// Iroh RT core (P5-0, look block P5-1): per-frame constants, one std430 block shared by every pass. Mirrors RtFrameData.java.
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
    vec4 jitter;        // ndc xy added to the raster clip position (content shifts by +pixels zw); motion vectors unjittered
    vec4 skyUp;         // look sky: zenith rgb, sunset amount
    vec4 skyMid;        // horizon rgb, night factor
    vec4 skySunset;     // sunset band rgb, rain
    vec4 skyDown;       // below-horizon rgb, time (s)
    vec4 mfog0;         // mountain fog: base altitude, height H, extinction, opacity cap
    vec4 mfog1;         // patches, distance haze, haze distance, near cutoff
    vec4 fogColor;      // mist rgb, glare strength
    vec4 water2;        // wave amplitude, wave time, ior, underwater density scale
    vec4 waterColor;    // underwater in-scatter rgb
    vec4 wet;           // wetness, wet roughness, wet darkening, unused
    vec4 cloud;         // cloud-shadow hook: cover, feature size, drift x, drift z
    vec4 reuse;         // checkerboard phase, ReSTIR M cap, spatial taps, light-grid share
};
#define F_ALPHA_TEST 1u
#define F_CACHE 2u
#define F_SOFT_SUN 4u
#define F_REJECT 8u
#define F_CACHE_UPDATE_ALL 16u
#define F_LOOK 32u
#define F_UNDERWATER 64u
#define F_HALF_GI 128u
#define F_HALF_REFL 256u
#define F_LIGHT_GRID 512u
#define F_RESTIR 1024u
#define F_WAVES 2048u
#define F_CLOUD_SHADOW 4096u
#define F_HALF_LIGHT 8192u
