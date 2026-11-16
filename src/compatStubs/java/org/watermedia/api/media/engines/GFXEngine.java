package org.watermedia.api.media.engines;

import org.watermedia.api.util.PixelFormat;

import java.nio.ByteBuffer;

/** Compile-only signature stub of WaterMedia's public API (see PixelFormat stub). */
public abstract class GFXEngine {
    protected PixelFormat format;
    protected int width;
    protected int height;
    protected int bits;

    protected GFXEngine() {}

    public abstract long texture();

    public abstract void upload(ByteBuffer[] buffers, int[] strides);

    public abstract void release();
}
