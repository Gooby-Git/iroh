vec3 synth_tint(vec3 c) {
    return mix(c, vec3(dot(c, vec3(0.299, 0.587, 0.114))), 0.5);
}
