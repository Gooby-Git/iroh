package net.vulkanmod.gl;

import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

// TODO: This class is only used to emulate a CPU buffer for texture copying purposes
//  any other use is not supported
public class VkGlBuffer {
    private static int ID_COUNTER = 1;
    private static final Int2ReferenceOpenHashMap<VkGlBuffer> map = new Int2ReferenceOpenHashMap<>();
    private static int boundId = 0;
    private static VkGlBuffer boundBuffer;

    private static VkGlBuffer pixelPackBufferBound;
    private static VkGlBuffer pixelUnpackBufferBound;

    public static int glGenBuffers() {
        int id = ID_COUNTER;
        map.put(id, new VkGlBuffer(id));
        ID_COUNTER++;
        return id;
    }

    /** Iroh (P1-2): every target's binding is tracked for glGetIntegerv; only pixel pack/unpack buffers have storage. */
    private static final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap bindings = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();

    public static int getBinding(int target) {
        return bindings.get(target);
    }

    public static void glBindBuffer(int target, int buffer) {
        boundId = buffer;
        VkGlBuffer glBuffer = map.get(buffer);

        if (buffer > 0 && glBuffer == null)
            throw new NullPointerException("bound texture is null");

        bindings.put(target, buffer);

        switch (target) {
            case GL32.GL_PIXEL_PACK_BUFFER -> pixelPackBufferBound = glBuffer;
            case GL32.GL_PIXEL_UNPACK_BUFFER -> pixelUnpackBufferBound = glBuffer;
            // Iroh (P1-2): vertex/index/uniform/copy targets have no storage here (the base threw); tracked only.
            default -> {
                return;
            }
        }

        if (glBuffer != null) {
            glBuffer.target = target;
        }
    }

    public static void glBufferData(int target, ByteBuffer byteBuffer, int usage) {
        VkGlBuffer buffer = boundTo(target);
        if (buffer == null)
            return;

        // Iroh (P1-2): the base left this a TODO and rebound the unpack buffer to a stale object.
        int size = byteBuffer != null ? byteBuffer.remaining() : 0;
        buffer.allocate(size);
        if (byteBuffer != null && size > 0)
            MemoryUtil.memCopy(MemoryUtil.memAddress(byteBuffer), MemoryUtil.memAddress(buffer.data), size);
    }

    /** Pixel buffer bound to a target, or null (non-pixel targets have no storage under Iroh). */
    public static VkGlBuffer boundTo(int target) {
        return switch (target) {
            case GL32.GL_PIXEL_PACK_BUFFER -> pixelPackBufferBound;
            case GL32.GL_PIXEL_UNPACK_BUFFER -> pixelUnpackBufferBound;
            default -> null;
        };
    }

    /** GL_BUFFER_SIZE of the buffer bound to a target (0 when none or no storage). */
    public static int boundSize(int target) {
        VkGlBuffer b = boundTo(target);
        return b != null && b.data != null ? b.data.capacity() : 0;
    }

    public static void glBufferData(int target, long size, int usage) {
        VkGlBuffer buffer = boundTo(target);
        if (buffer == null)
            return;

        buffer.allocate((int) size);
    }

    public static ByteBuffer glMapBuffer(int target, int access) {
        VkGlBuffer buffer = boundTo(target);
        if (buffer == null || buffer.data == null)
            return null;

        ByteBuffer mappedBuffer = buffer.data;
        mappedBuffer.position(0);
        return mappedBuffer;
    }

    public static boolean glUnmapBuffer(int i) {
        return true;
    }

    public static void glDeleteBuffers(IntBuffer intBuffer) {
        for (int i = intBuffer.position(); i < intBuffer.limit(); i++) {
            glDeleteBuffers(intBuffer.get(i));
        }
    }

    public static void glDeleteBuffers(int id) {
        bindings.int2IntEntrySet().removeIf(e -> e.getIntValue() == id);
        var buffer = map.remove(id);
        if (buffer != null && buffer == pixelPackBufferBound) pixelPackBufferBound = null;
        if (buffer != null && buffer == pixelUnpackBufferBound) pixelUnpackBufferBound = null;

        if (buffer != null)
            buffer.freeData();
    }

    public static VkGlBuffer getPixelUnpackBufferBound() {
        return pixelUnpackBufferBound;
    }

    public static VkGlBuffer getPixelPackBufferBound() {
        return pixelPackBufferBound;
    }

    private static void checkTarget(int target) {
        if (target != GL32.GL_PIXEL_UNPACK_BUFFER && target != GL32.GL_PIXEL_PACK_BUFFER)
            throw new IllegalArgumentException("target %d not supported".formatted(target));
    }

    int id;
    int target;

    ByteBuffer data;

    public VkGlBuffer(int id) {
        this.id = id;
    }

    private void allocate(int size) {
        if (this.data != null)
            this.freeData();

        this.data = MemoryUtil.memAlloc(size);
    }

    private ByteBuffer getData() {
        return this.data;
    }

    private void freeData() {
        MemoryUtil.memFree(data);
        data = null;
    }

}
