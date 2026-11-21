#version 130
uniform sampler2DShadow shadowtex0;
uniform sampler2D lightmap;
uniform float cloudHeight = 192.0;
uniform int customArray[2];
varying float sample;
flat in int blockId;
in vec3 neverWritten;
void main() {
    float s = shadow2D(shadowtex0, vec3(gl_TexCoord[0].xy, 0.5)).x;
    float fog = clamp((gl_FogFragCoord - gl_Fog.start) * gl_Fog.scale, 0.0, 1.0);
    vec4 l = texture2D(lightmap, gl_TexCoord[1].st);
    gl_FragData[0] = gl_Color * l * s * fog * sample * float(blockId) * cloudHeight + vec4(neverWritten, customArray[0]);
    gl_FragData[1] = vec4(1.0);
}
