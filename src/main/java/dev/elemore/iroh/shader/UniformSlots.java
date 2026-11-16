package dev.elemore.iroh.shader;

import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.function.Supplier;

/** Connects the members of a converted shader's uniform block to vanilla's {@code Uniform} buffers (P1-2a). */
public final class UniformSlots {
    private UniformSlots() {}

    public static void connect(UBO ubo, Map<String, com.mojang.blaze3d.shaders.Uniform> uniformMap) {
        for (Uniform member : ubo.getUniforms())
            member.setSupplier(supplier(uniformMap.get(member.getName()), member.getSize() * 4));
    }

    /**
     * A uniform the JSON does not declare reads zero, as an unset GL uniform does. When the vanilla uniform
     * holds fewer bytes than the GLSL member (JSON "float" count 3 for a GLSL vec4, a mat3 column padding),
     * its bytes are copied into a zeroed slot of the member's size on every read.
     */
    public static Supplier<MappedBuffer> supplier(com.mojang.blaze3d.shaders.Uniform uniform, int bytes) {
        if (uniform == null) {
            MappedBuffer zero = MappedBuffer.createFromBuffer(MemoryUtil.memCalloc(Math.max(bytes, 16)));
            return () -> zero;
        }

        ByteBuffer byteBuffer;
        if (uniform.getType() <= 3) {
            byteBuffer = MemoryUtil.memByteBuffer(uniform.getIntBuffer());
        } else if (uniform.getType() <= 10) {
            byteBuffer = MemoryUtil.memByteBuffer(uniform.getFloatBuffer());
        } else {
            throw new IllegalStateException("out of bounds type for uniform " + uniform.getName());
        }

        MappedBuffer src = MappedBuffer.createFromBuffer(byteBuffer);
        int have = byteBuffer.capacity();
        if (have >= bytes)
            return () -> src;

        MappedBuffer padded = MappedBuffer.createFromBuffer(MemoryUtil.memCalloc(bytes));
        return () -> {
            MemoryUtil.memCopy(src.ptr, padded.ptr, have);
            return padded;
        };
    }
}
