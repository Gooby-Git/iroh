#version 150 compatibility
in vec4 c;
/* RENDERTARGETS: 0 */
void main() { gl_FragData[0] = c; }
