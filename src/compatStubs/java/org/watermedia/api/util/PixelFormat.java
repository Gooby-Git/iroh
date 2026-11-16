package org.watermedia.api.util;

/**
 * Compile-only signature stub of WaterMedia's public API (no code from WaterMedia; never packaged). Iroh's WaterMedia
 * compat mixin compiles against it; at runtime the real class from the WaterMedia jar is used.
 */
public enum PixelFormat {
    GRAY, RGB, RGBA, BGRA, GBRA, YUYV, YUYV2, NV12, NV21, YUV420P, YUV422P, YUV444P, YUVA420P, YUVA422P, YUVA444P,
    BC1, BC2, BC3, BC5, BC7
}
