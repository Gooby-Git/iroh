#version 150 compatibility
in vec4 c;
uniform float rainFactor;
uniform vec2 screen;
void main() { gl_FragData[0] = c * rainFactor * screen.x; }
