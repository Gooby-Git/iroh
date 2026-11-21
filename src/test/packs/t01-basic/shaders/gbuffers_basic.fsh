#version 120
uniform sampler2D texture;
varying vec4 color;
varying vec2 uv;
void main() {
/* DRAWBUFFERS:0 */
    gl_FragData[0] = texture2D(texture, uv) * color;
}
