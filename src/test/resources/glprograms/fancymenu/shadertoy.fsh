#version 150
// Reconstructed from FancyMenu 3.9.14 GlslShaderRuntime string constants (variant "shadertoy"); the user part is a
// small Shadertoy-style example written for this test.
uniform vec3 iResolution;
uniform float iTime;
uniform float iTimeDelta;
uniform float iFrameRate;
uniform int iFrame;
uniform vec4 iMouse;
uniform vec4 iDate;
uniform float iSampleRate;
uniform float iChannelTime[4];
uniform vec3 iChannelResolution[4];
uniform sampler2D iChannel0;
uniform sampler2D iChannel1;
uniform sampler2D iChannel2;
uniform sampler2D iChannel3;
uniform vec2 fmAreaOffset;
uniform vec2 fmAreaSize;
uniform vec2 fmScreenSize;
uniform float fmGuiScale;
uniform ivec4 fmMouseButtons;
uniform int fmInWorld;
uniform float fmOpacity;
in vec2 fmUv_FancyMenu;
out vec4 fmOutputColor_FancyMenu;
#define iGlobalTime iTime
#define texture2D texture
#define textureCube texture
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    vec3 col = 0.5 + 0.5 * cos(iTime + uv.xyx + vec3(0, 2, 4));
    fragColor = vec4(col, 1.0) * texture(iChannel0, uv) * fmOpacity;
}

void main() {
    vec4 fmColor_FancyMenu;
    mainImage(fmColor_FancyMenu, gl_FragCoord.xy - fmAreaOffset);
    fmOutputColor_FancyMenu = fmColor_FancyMenu;
}
