package dev.elemore.iroh.shaderpack.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Vulkan operations the pack frame executor needs, behind an interface so the executor runs on: a recording fake
 * device (order/state tests, no Vulkan at all), a headless Vulkan device for offline replay (lavapipe), and later Iroh's
 * own renderer (VulkanImage, the staging ring, MemoryGuard, deferred frees by epoch; see knowledge/iris-loader.md
 * "P3-3 runtime"). Handles are small ints owned by the backend. Images are created zeroed. All images keep one layout
 * that allows attachment, sampling, storage and transfer (GENERAL) unless a backend chooses better; the executor
 * declares every hazard through {@link #barrier}.
 */
public interface GpuBackend extends AutoCloseable {

    /** usage words: COLOR_ATTACHMENT, SAMPLED, STORAGE, TRANSFER_SRC, TRANSFER_DST, DEPTH */
    record ImageDesc(String name, String vkFormat, int width, int height, int depth, int mips, Set<String> usage) {
        public long bytes() {
            long b = (long) width * height * depth * TexelCodec.bytes(vkFormat);
            return mips > 1 ? b * 4 / 3 : b;
        }
    }

    /** a vertex input the program declares: location, GLSL type (vec4, ivec2...), and what feeds it */
    record VertexInput(int location, String type, String feed) {}

    /** descriptor binding of set 0 (set 1 for SSBOs): kind UNIFORM_BUFFER, SAMPLER, STORAGE_IMAGE, STORAGE_BUFFER */
    record Slot(int set, int binding, String kind, String glslType, String name) {}

    record PipelineDesc(String id, byte[] vertexSpirv, byte[] fragmentSpirv, byte[] computeSpirv, List<VertexInput> inputs,
                        List<String> colourFormats, List<Object> blend, List<Slot> slots, Geo geo) {
        public PipelineDesc(String id, byte[] vertexSpirv, byte[] fragmentSpirv, byte[] computeSpirv, List<VertexInput> inputs,
                            List<String> colourFormats, List<Object> blend, List<Slot> slots) {
            this(id, vertexSpirv, fragmentSpirv, computeSpirv, inputs, colourFormats, blend, slots, null);
        }
    }

    /**
     * P3-5 geometry pipeline state (null = fullscreen quad). layout: {@code wide-terrain} (36-byte WideTerrainVertex,
     * indexed quads) or {@code float-entity} (later). depthFormat: the depth attachment's VkFormat name (null = none).
     */
    record Geo(String layout, String depthFormat, boolean depthTest, boolean depthWrite, boolean cullBack, boolean alphaBlend) {}

    /**
     * One geometry pass: ONE render pass instance over the colour + depth attachments, one indexed-quad draw per batch
     * (each with its own uniform block: model-view, vertex decode). Batches in one instance need no barriers between them.
     */
    record GeoDraw(String pass, int pipeline, int[] colour, int depth, int[] viewport, Map<Integer, Bind> set0,
                   Map<Integer, Bind> set1, List<GeoBatch> batches) {}

    record GeoBatch(byte[] uniforms, int vertexBuffer, int quads) {}

    /** what feeds one slot: an image (with a sampler kind LINEAR / NEAREST / COMPARE / STORAGE) or a buffer */
    record Bind(int image, String sampler, int buffer) {
        public static Bind image(int image, String sampler) {
            return new Bind(image, sampler, -1);
        }

        public static Bind buffer(int buffer) {
            return new Bind(-1, null, buffer);
        }
    }

    record Draw(String pass, int pipeline, int[] colour, int[] viewport, Map<Integer, Bind> set0, Map<Integer, Bind> set1, byte[] uniforms) {}

    record Dispatch(String pass, int pipeline, int[] groups, Map<Integer, Bind> set0, Map<Integer, Bind> set1, byte[] uniforms) {}

    /** access kinds for {@link #barrier}: ATTACHMENT_WRITE, SAMPLED_READ, STORAGE_WRITE, STORAGE_READ, TRANSFER_WRITE, TRANSFER_READ, HOST */
    String name();

    int createImage(ImageDesc d);

    /** rgba floats, 4 per texel of mip 0, encoded by {@link TexelCodec} */
    void upload(int image, float[] rgba);

    int createBuffer(String name, long bytes);

    /** P3-5: a device vertex buffer filled with {@code data} (geometry replay; in game the section buffers are Iroh's) */
    int createVertexBuffer(String name, java.nio.ByteBuffer data);

    void freeBuffer(int buffer);

    /** P3-5: records one geometry draw (render pass with depth, LOAD/STORE) */
    void drawGeometry(GeoDraw d);

    int createPipeline(PipelineDesc d);

    void beginFrame();

    void barrier(int image, String from, String to);

    void clear(int image, float[] rgba);

    void generateMips(int image);

    void copy(int src, int dst);

    void draw(Draw d);

    void dispatch(Dispatch d);

    /** submits and waits */
    void endFrame();

    /** mip 0 decoded to rgba floats (synchronous; offline backends only) */
    float[] readback(int image);

    /**
     * Copy mip 0 of src into dst; formats may differ when both are colour (a blit converts) or equal depth formats.
     * Used to bring the raster path's scene colour/depth in (post mode) and to present the pack output.
     */
    void blit(int src, int dst);

    /** Destroys a pipeline after in-flight frames (dimension change, disable) */
    void freePipeline(int pipeline);

    /** Frees an image the backend created (deferred until no in-flight frame uses it); imported images are only forgotten. */
    void freeImage(int image);

    /** An image the backend does not own (the raster path's scene colour / depth), for blit sources/destinations. */
    /** GPU timestamp before / after one pass (in game: timestamp queries, read without waiting two frames later) */
    default void timeBegin(String pass) {
    }

    default void timeEnd(String pass) {
    }

    default int importImage(Object external, String name) {
        throw new UnsupportedOperationException(name() + " cannot import " + name);
    }

    ImageDesc desc(int image);

    @Override
    void close();
}
