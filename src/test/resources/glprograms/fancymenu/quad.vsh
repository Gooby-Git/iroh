#version 150
in vec2 Position;
out vec2 fmUv_FancyMenu;
void main() {
    fmUv_FancyMenu = (Position + 1.0) * 0.5;
    gl_Position = vec4(Position, 0.0, 1.0);
}
