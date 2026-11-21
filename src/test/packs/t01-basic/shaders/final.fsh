#version 120
uniform sampler2D gcolor;
void main() {
    gl_FragColor = texture2D(gcolor, gl_TexCoord[0].st);
}
