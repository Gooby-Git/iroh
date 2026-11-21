#version 130
attribute vec4 mc_Entity;
attribute vec2 mc_midTexCoord;
attribute vec4 at_tangent;
uniform float viewWidth;
uniform mat4 gbufferModelView;
const float half_width = viewWidth * 0.5;
const float two = 2.0;
vec3 up = normalize(gbufferModelView[1].xyz);
varying float sample;
flat out int blockId;
void main() {
    gl_Position = ftransform();
    gl_FrontColor = gl_Color;
    gl_TexCoord[0] = gl_TextureMatrix[0] * gl_MultiTexCoord0;
    gl_TexCoord[1] = gl_TextureMatrix[1] * gl_MultiTexCoord1;
    gl_FogFragCoord = length((gl_ModelViewMatrix * gl_Vertex).xyz);
    sample = half_width * two + up.y + mc_midTexCoord.x + at_tangent.w + (gl_NormalMatrix * gl_Normal).z;
    blockId = int(mc_Entity.x);
}
