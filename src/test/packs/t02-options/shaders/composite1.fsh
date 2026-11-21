#version 330 compatibility
in vec2 uv;
void main() { gl_FragData[0] = vec4(uv, 0.0, 1.0); }
