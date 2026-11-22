package dev.elemore.iroh.packtest;

import dev.elemore.iroh.shaderpack.runtime.GpuBackend;

import java.util.ArrayList;
import java.util.List;

/** a backend that does nothing per frame and allocates nothing per call: measures the executor's own allocation */
final class NullBackend implements GpuBackend {
    final List<ImageDesc> descs = new ArrayList<>();
    int pipes, bufs;
    long draws;

    public String name() { return "null"; }
    public int createImage(ImageDesc d) { descs.add(d); return descs.size() - 1; }
    public void upload(int image, float[] rgba) {}
    public int createBuffer(String name, long bytes) { return bufs++; }
    public int createVertexBuffer(String name, java.nio.ByteBuffer data) { return bufs++; }
    public void freeBuffer(int buffer) {}
    public void drawGeometry(GeoDraw d) { draws++; }
    public int createPipeline(PipelineDesc d) { return pipes++; }
    public void beginFrame() {}
    public void barrier(int image, String from, String to) {}
    public void clear(int image, float[] rgba) {}
    public void generateMips(int image) {}
    public void copy(int src, int dst) {}
    public void draw(Draw d) { draws++; }
    public void dispatch(Dispatch d) { draws++; }
    public void endFrame() {}
    public float[] readback(int image) { return null; }
    public void blit(int src, int dst) {}
    public void freePipeline(int pipeline) {}
    public void freeImage(int image) {}
    public ImageDesc desc(int image) { return descs.get(image); }
    public void close() {}
}
