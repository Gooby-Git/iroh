#version 150 compatibility
out Data { vec4 c; } o;
void main() { gl_Position = ftransform(); o.c = gl_Color; }
