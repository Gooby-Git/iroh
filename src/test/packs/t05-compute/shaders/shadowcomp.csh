#version 430
layout(local_size_x = 8, local_size_y = 8, local_size_z = 8) in;
const ivec3 workGroups = ivec3(16, 8, 16);
writeonly uniform image3D light_img;
layout(r16ui) uniform uimage3D voxel_img;
uniform usampler3D voxel_sampler;
layout(std430, binding = 2) buffer Lights { vec4 lights[]; } lightBuf;
void main() {
    ivec3 p = ivec3(gl_GlobalInvocationID);
    uint id = imageLoad(voxel_img, p).r + texelFetch(voxel_sampler, p, 0).r;
    imageStore(light_img, p, vec4(float(id)) + lightBuf.lights[0]);
}
