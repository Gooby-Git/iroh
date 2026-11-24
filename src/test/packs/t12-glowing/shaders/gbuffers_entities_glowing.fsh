#version 150 compatibility
in vec4 c;
/* RENDERTARGETS: 0 */
void main() { gl_FragData[0] = vec4(1.0, 0.0, 1.0, 1.0) * c; }
