#version 120
uniform sampler2D colortex0;
uniform sampler2D depthtex0;
uniform float viewWidth;
varying vec2 texcoord;
/* DRAWBUFFERS:1 */
void main() {
    vec3 c = texture2D(colortex0, texcoord).rgb;
    gl_FragData[0] = vec4(c * texture2D(depthtex0, texcoord).r, 1.0);
}
