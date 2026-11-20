#version 150
// Reconstructed from FancyMenu 3.9.14 GlslShaderRuntime (variant "direct_glfragcolor_compat").
#define gl_FragColor fmOutputColor_FancyMenu
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
void main() {
    gl_FragColor = vec4(fmUv_FancyMenu, 0.5 + 0.5 * sin(iTime), fmOpacity);
}
