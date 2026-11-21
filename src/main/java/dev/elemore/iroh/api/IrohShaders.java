package dev.elemore.iroh.api;

import dev.elemore.iroh.apiimpl.DrawImpl;

import java.nio.ByteBuffer;

/**
 * Owner shaders (api 3, P1-13a): Vulkan GLSL 450 (or SPIR-V) compiled by Iroh, no GL dialect translation. Any thread;
 * compile once at load (not per frame). On error the handle is {@code valid() == false}, one log line, and draws with it
 * draw nothing. Iroh's include files ("fog.glsl", "light.glsl" ... in assets/vulkanmod/shaders/include) can be
 * {@code #include}d. Layout: {@link ShaderLayout}.
 */
public final class IrohShaders {
    private IrohShaders() {
    }

    public static ShaderHandle compile(String id, String vertexGlsl450, String fragmentGlsl450, ShaderLayout layout) {
        return DrawImpl.compile(id, vertexGlsl450, fragmentGlsl450, layout);
    }

    /** Pre-compiled SPIR-V (bytes copied). */
    public static ShaderHandle compileSpirv(String id, ByteBuffer vertexSpirv, ByteBuffer fragmentSpirv, ShaderLayout layout) {
        return DrawImpl.compileSpirv(id, vertexSpirv, fragmentSpirv, layout);
    }
}
