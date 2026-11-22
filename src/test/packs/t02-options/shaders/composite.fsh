#version 330 compatibility
#include "/lib/settings.glsl"
in vec2 uv;
uniform sampler2D colortex0;
#ifdef BLOOM
/* DRAWBUFFERS:02 */
#else
/* DRAWBUFFERS:0 */
#endif
void main() {
    gl_FragData[0] = texture(colortex0, uv) * float(SHADOW_RES);
#ifdef BLOOM
    gl_FragData[1] = vec4(1.0);
#endif
}
