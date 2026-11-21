#version 150 compatibility
in vec4 c;
void main() { gl_FragData[0] = c * undefinedThing; }
