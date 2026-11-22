#version 430
layout(local_size_x = 16, local_size_y = 16) in;
const vec2 workGroupsRender = vec2(1.0, 1.0);
layout(rgba8) uniform image2D colorimg0;
void main() { imageStore(colorimg0, ivec2(gl_GlobalInvocationID.xy), vec4(1.0)); }
