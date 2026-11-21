#version 150 compatibility
#include "/lib/common.glsl"
#include "/lib/Common.glsl"
in vec4 c;
#if STRENGTH > 0.3 && defined(COMMON_INCLUDED) && MC_VERSION == 12101 && defined IS_IRIS
float CAT(my, Value) = MAX3(1.0, 2.0,
                            3.0);
#else
#error "float #if broken"
#endif
#if !defined MC_RENDER_STAGE_TERRAIN_SOLID || MC_RENDER_STAGE_TERRAIN_SOLID != 8
#error "render stage macros"
#endif
void main() { gl_FragData[0] = c * helper(myValue) * __LINE__; }
