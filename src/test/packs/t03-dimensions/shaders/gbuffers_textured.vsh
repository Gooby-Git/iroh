#version 150 compatibility
out vec4 c;
void main() { gl_Position = ftransform(); c = gl_Color; }
