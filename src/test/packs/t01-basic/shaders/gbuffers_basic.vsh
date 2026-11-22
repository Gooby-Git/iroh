#version 120
varying vec4 color;
varying vec2 uv;
void main() {
    gl_Position = ftransform();
    color = gl_Color;
    uv = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
}
