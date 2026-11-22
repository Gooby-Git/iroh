#ifndef COMMON_INCLUDED
#define COMMON_INCLUDED
#define SQR(x) ((x) * (x))
#define CAT(a, b) a ## b
#define MAX3(a, b, c) max(max(a, b), \
                          c)
#define STRENGTH 0.5 // [0.25 0.5 1.0]
#include "inner/helper.glsl"
#endif
