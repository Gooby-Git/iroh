package net.vulkanmod.vulkan.memory.buffer;

import net.vulkanmod.vulkan.memory.MemoryType;
import net.vulkanmod.vulkan.memory.MemoryTypes;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;

public class VertexBuffer extends Buffer {

    public VertexBuffer(int size) {
        this(size, MemoryTypes.HOST_MEM);
    }

    public VertexBuffer(int size, MemoryType type) {
        super(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, type);
        this.createBuffer(size);
    }

    /** Iroh P5-2: extra usage bits (the terrain arena with RT on: device address + AS build input; 0 = as above). */
    public VertexBuffer(int size, MemoryType type, int extraUsage) {
        super(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | extraUsage, type);
        this.createBuffer(size);
    }

}
