#version 120
attribute vec3 Position;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
varying vec4 c;
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    c = vec4(1.0);
}
