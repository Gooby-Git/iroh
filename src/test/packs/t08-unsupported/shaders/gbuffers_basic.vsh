#version 120
varying vec4 c;
void main() { gl_Position = ftransform(); c = gl_LightSource[0].diffuse; }
