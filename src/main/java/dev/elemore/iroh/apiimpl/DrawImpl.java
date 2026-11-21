package dev.elemore.iroh.apiimpl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.elemore.iroh.api.GeometryWriter;
import dev.elemore.iroh.api.PipelineDecl;
import dev.elemore.iroh.api.PipelineHandle;
import dev.elemore.iroh.api.ShaderHandle;
import dev.elemore.iroh.api.ShaderLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Iroh internal (P1-13a): {@link dev.elemore.iroh.api.IrohDraw} / {@link dev.elemore.iroh.api.IrohShaders}. Vertices go
 * into one reusable native buffer; consecutive batches with the same pipeline and textures merge into one draw;
 * {@link #flush} records them (Drawer's per-frame vertex ring, auto index buffers for quads), with the declared state
 * applied and the previous state restored. No allocation per vertex, batch or flush.
 */
public final class DrawImpl {
    // ------------------------------------------------------------------ shaders

    static final class ShaderImpl implements ShaderHandle {
        final String id;
        final ShaderLayout layout;
        final byte[] vert, frag;

        ShaderImpl(String id, ShaderLayout layout, byte[] vert, byte[] frag) {
            this.id = id;
            this.layout = layout;
            this.vert = vert;
            this.frag = frag;
        }

        @Override public String id() { return id; }
        @Override public ShaderLayout layout() { return layout; }
        @Override public boolean valid() { return vert != null && frag != null; }
    }

    public static ShaderHandle compile(String id, String vsh, String fsh, ShaderLayout layout) {
        try {
            SPIRVUtils.SPIRV v = SPIRVUtils.compileShader(id + ".vsh", vsh, SPIRVUtils.ShaderKind.VERTEX_SHADER);
            SPIRVUtils.SPIRV f = SPIRVUtils.compileShader(id + ".fsh", fsh, SPIRVUtils.ShaderKind.FRAGMENT_SHADER);
            ShaderImpl s = new ShaderImpl(id, layout, bytes(v.bytecode()), bytes(f.bytecode()));
            v.free();
            f.free();
            return s;
        } catch (Throwable t) {
            Initializer.LOGGER.error("Iroh api: shader {} did not compile; draws with it draw nothing", id, t);
            return new ShaderImpl(id, layout, null, null);
        }
    }

    public static ShaderHandle compileSpirv(String id, ByteBuffer v, ByteBuffer f, ShaderLayout layout) {
        return new ShaderImpl(id, layout, bytes(v), bytes(f));
    }

    private static byte[] bytes(ByteBuffer b) {
        ByteBuffer d = b.duplicate();
        byte[] out = new byte[d.remaining()];
        d.get(out);
        return out;
    }

    private static SPIRVUtils.SPIRV spirv(byte[] b) {
        ByteBuffer buf = MemoryUtil.memAlloc(b.length);
        buf.put(b).flip();
        return new SPIRVUtils.SPIRV(0, buf); // free() releases the copy
    }

    /** Field type / count of the built-in uniform names a layout may use. */
    private static final Map<String, Object[]> UNIFORMS = Map.ofEntries(
            Map.entry("MVP", new Object[]{"matrix4x4", 16}), Map.entry("ModelViewMat", new Object[]{"matrix4x4", 16}),
            Map.entry("ProjMat", new Object[]{"matrix4x4", 16}), Map.entry("ColorModulator", new Object[]{"float", 4}),
            Map.entry("FogColor", new Object[]{"float", 4}), Map.entry("FogStart", new Object[]{"float", 1}),
            Map.entry("FogEnd", new Object[]{"float", 1}), Map.entry("GameTime", new Object[]{"float", 1}),
            Map.entry("LineWidth", new Object[]{"float", 1}), Map.entry("ScreenSize", new Object[]{"float", 2}),
            Map.entry("FogShape", new Object[]{"int", 1}));

    /** The Pipeline.Builder JSON for a layout (bindings 0 / 1 / 2..). Package-private for apiTest. */
    public static JsonObject bindingsJson(ShaderLayout l) {
        JsonObject o = new JsonObject();
        JsonArray ubos = new JsonArray();
        if (!l.vertexUniforms().isEmpty()) ubos.add(ubo("vertex", 0, l.vertexUniforms()));
        if (!l.fragmentUniforms().isEmpty()) ubos.add(ubo("fragment", 1, l.fragmentUniforms()));
        o.add("UBOs", ubos);
        JsonArray samplers = new JsonArray();
        for (String s : l.samplers()) {
            JsonObject so = new JsonObject();
            so.addProperty("name", s);
            samplers.add(so);
        }
        o.add("samplers", samplers);
        return o;
    }

    private static JsonObject ubo(String type, int binding, List<String> names) {
        JsonObject u = new JsonObject();
        u.addProperty("type", type);
        u.addProperty("binding", binding);
        JsonArray fields = new JsonArray();
        for (String n : names) {
            Object[] t = UNIFORMS.get(n);
            if (t == null) throw new IllegalArgumentException("unknown uniform " + n + " (allowed: " + UNIFORMS.keySet() + ")");
            JsonObject f = new JsonObject();
            f.addProperty("name", n);
            f.addProperty("type", (String) t[0]);
            f.addProperty("count", (Integer) t[1]);
            fields.add(f);
        }
        u.add("fields", fields);
        return u;
    }

    // ------------------------------------------------------------------ pipelines

    static final class PipelineImpl implements PipelineHandle {
        final PipelineDecl decl;
        final VertexFormat format;
        final VertexFormat.Mode mode;
        GraphicsPipeline pipeline;
        boolean failed;

        PipelineImpl(PipelineDecl decl) {
            this.decl = decl;
            this.format = switch (decl.layout()) {
                case NONE -> CustomVertexFormat.NONE;
                case POSITION_COLOR -> DefaultVertexFormat.POSITION_COLOR;
                case POSITION_TEX_COLOR -> DefaultVertexFormat.POSITION_TEX_COLOR;
            };
            this.mode = switch (decl.topology()) {
                case TRIANGLES -> VertexFormat.Mode.TRIANGLES;
                case QUADS -> VertexFormat.Mode.QUADS;
                case LINES -> VertexFormat.Mode.DEBUG_LINES;
                case LINE_STRIP -> VertexFormat.Mode.DEBUG_LINE_STRIP;
            };
        }

        @Override public PipelineDecl decl() { return decl; }
        @Override public boolean valid() { return !failed && decl.shader().valid(); }

        GraphicsPipeline get() {
            if (pipeline != null || failed) return pipeline;
            try {
                ShaderImpl s = (ShaderImpl) decl.shader();
                Pipeline.Builder b = new Pipeline.Builder(format, "iroh-api/" + s.id);
                b.parseBindings(bindingsJson(s.layout));
                b.setSPIRVs(spirv(s.vert), spirv(s.frag));
                pipeline = b.createGraphicsPipeline();
            } catch (Throwable t) {
                failed = true;
                Initializer.LOGGER.error("Iroh api: pipeline for shader {} could not be built; its draws draw nothing", decl.shader().id(), t);
            }
            return pipeline;
        }
    }

    private static final Map<PipelineDecl, PipelineImpl> PIPELINES = new ConcurrentHashMap<>();

    public static PipelineHandle pipeline(PipelineDecl decl) {
        return PIPELINES.computeIfAbsent(decl, PipelineImpl::new);
    }

    // ------------------------------------------------------------------ batching

    private static ByteBuffer buf = MemoryUtil.memAlloc(64 * 1024);
    private static PipelineImpl batchPipeline;
    private static int batchVertices;
    private static final int[] textures = new int[8];
    private static final int[] batchTextures = new int[8];
    private static final Matrix4f mv = new Matrix4f(), proj = new Matrix4f();
    private static boolean customTransform, batchCustom, loggedOutside;
    static boolean insideHook;

    private static final Writer WRITER = new Writer();
    private static final GeometryWriter NOOP = new GeometryWriter() {
        @Override public GeometryWriter vertex(float x, float y, float z, int argb) { return this; }
        @Override public GeometryWriter vertex(float x, float y, float z, float u, float v, int argb) { return this; }
        @Override public void end() {}
    };

    public static void texture(int slot, ResourceLocation id) {
        if (slot < 0 || slot >= textures.length) throw new IllegalArgumentException("slot " + slot);
        textures[slot] = Minecraft.getInstance().getTextureManager().getTexture(id).getId();
    }

    public static void transform(Matrix4fc modelView, Matrix4fc projection) {
        if (modelView == null || projection == null) {
            customTransform = false;
            return;
        }
        mv.set(modelView);
        proj.set(projection);
        customTransform = true;
    }

    public static GeometryWriter begin(PipelineHandle handle) {
        if (!insideHook || Renderer.recordingCommandBuffer("IrohDraw.begin") == null) {
            if (!loggedOutside) {
                loggedOutside = true;
                Initializer.LOGGER.warn("Iroh api: IrohDraw used outside a frame hook; ignored (logged once)", new Throwable("called from"));
            }
            return NOOP;
        }
        PipelineImpl p = (PipelineImpl) handle;
        if (p != batchPipeline || customTransform || batchCustom || !java.util.Arrays.equals(textures, batchTextures))
            flush();
        batchPipeline = p;
        batchCustom = customTransform;
        System.arraycopy(textures, 0, batchTextures, 0, textures.length);
        return WRITER;
    }

    private static final class Writer implements GeometryWriter {
        @Override public GeometryWriter vertex(float x, float y, float z, int argb) {
            ensure(16);
            int p = buf.position();
            buf.putFloat(p, x).putFloat(p + 4, y).putFloat(p + 8, z).putInt(p + 12, abgr(argb));
            buf.position(p + 16);
            batchVertices++;
            return this;
        }

        @Override public GeometryWriter vertex(float x, float y, float z, float u, float v, int argb) {
            ensure(24);
            int p = buf.position();
            buf.putFloat(p, x).putFloat(p + 4, y).putFloat(p + 8, z).putFloat(p + 12, u).putFloat(p + 16, v).putInt(p + 20, abgr(argb));
            buf.position(p + 24);
            batchVertices++;
            return this;
        }

        @Override public void end() {
        }
    }

    /** 0xAARRGGBB -> bytes R,G,B,A in memory (little endian int 0xAABBGGRR), vanilla's vertex colour layout. */
    public static int abgr(int argb) {
        return (argb & 0xFF00FF00) | ((argb >>> 16) & 0xFF) | ((argb & 0xFF) << 16);
    }

    private static void ensure(int n) {
        if (buf.remaining() < n) {
            ByteBuffer b = MemoryUtil.memAlloc(Math.max(buf.capacity() * 2, buf.position() + n));
            buf.flip();
            b.put(buf);
            MemoryUtil.memFree(buf);
            buf = b;
        }
    }

    public static void drawInstanced(PipelineHandle handle, int vertexCount, int instanceCount) {
        flush();
        if (!insideHook) return;
        PipelineImpl p = (PipelineImpl) handle;
        record(p, 0, vertexCount, instanceCount);
    }

    /** Records the open batch. Called by IrohDraw.flush and at the end of every hook stage. */
    public static void flush() {
        PipelineImpl p = batchPipeline;
        int n = batchVertices;
        batchPipeline = null;
        batchVertices = 0;
        if (p != null && n > 0) {
            buf.flip();
            record(p, n, 0, 0);
        }
        buf.clear();
    }

    // saved state
    private static boolean sBlend, sDepthTest, sDepthMask, sCull;
    private static int sDepthFun, sSrcRgb, sDstRgb, sSrcA, sDstA, sOp;
    private static final int[] sTex = new int[8];

    private static void record(PipelineImpl p, int vertices, int drawVertices, int instances) {
        if (!p.valid()) return;
        GraphicsPipeline gp = p.get();
        VkCommandBuffer cb = Renderer.recordingCommandBuffer("IrohDraw");
        if (gp == null || cb == null) return;
        PipelineDecl d = p.decl;
        PipelineState.BlendInfo bi = PipelineState.blendInfo;
        sBlend = bi.enabled; sSrcRgb = bi.srcRgbFactor; sDstRgb = bi.dstRgbFactor; sSrcA = bi.srcAlphaFactor; sDstA = bi.dstAlphaFactor; sOp = bi.blendOp;
        sDepthTest = VRenderSystem.depthTest; sDepthMask = VRenderSystem.depthMask; sDepthFun = VRenderSystem.depthFun; sCull = VRenderSystem.cull;
        for (int i = 0; i < 8; i++) sTex[i] = RenderSystem.getShaderTexture(i);
        try {
            switch (d.blend()) {
                case OPAQUE -> bi.enabled = false;
                case TRANSLUCENT -> { bi.enabled = true; bi.srcRgbFactor = VK_BLEND_FACTOR_SRC_ALPHA; bi.dstRgbFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
                    bi.srcAlphaFactor = VK_BLEND_FACTOR_ONE; bi.dstAlphaFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA; bi.blendOp = VK_BLEND_OP_ADD; }
                case ADDITIVE -> { bi.enabled = true; bi.srcRgbFactor = VK_BLEND_FACTOR_SRC_ALPHA; bi.dstRgbFactor = VK_BLEND_FACTOR_ONE;
                    bi.srcAlphaFactor = VK_BLEND_FACTOR_ONE; bi.dstAlphaFactor = VK_BLEND_FACTOR_ONE; bi.blendOp = VK_BLEND_OP_ADD; }
            }
            VRenderSystem.depthTest = d.depthTest();
            VRenderSystem.depthMask = d.depthWrite();
            VRenderSystem.depthFun = 515; // GL_LEQUAL
            VRenderSystem.cull = d.cull();
            VRenderSystem.setPrimitiveTopologyGL(p.mode.asGLMode);
            if (batchCustom) VRenderSystem.applyMVP(mv, proj);
            else if (FrameHooks.inWorld()) VRenderSystem.applyMVP(FrameHooks.view(), FrameHooks.projection());
            else VRenderSystem.applyMVP(RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix());
            for (int i = 0; i < 8; i++) if (batchTextures[i] != 0) RenderSystem.setShaderTexture(i, batchTextures[i]);

            Renderer r = Renderer.getInstance();
            r.bindGraphicsPipeline(gp);
            VTextureSelector.bindShaderTextures(gp);
            r.uploadAndBindUBOs(gp);
            if (vertices > 0) Renderer.getDrawer().draw(buf, p.mode, p.format, vertices);
            else vkCmdDraw(cb, drawVertices, instances, 0, 0);
        } finally {
            bi.enabled = sBlend; bi.srcRgbFactor = sSrcRgb; bi.dstRgbFactor = sDstRgb; bi.srcAlphaFactor = sSrcA; bi.dstAlphaFactor = sDstA; bi.blendOp = sOp;
            VRenderSystem.depthTest = sDepthTest; VRenderSystem.depthMask = sDepthMask; VRenderSystem.depthFun = sDepthFun; VRenderSystem.cull = sCull;
            for (int i = 0; i < 8; i++) RenderSystem.setShaderTexture(i, sTex[i]);
            VRenderSystem.applyMVP(RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix());
        }
    }

    private DrawImpl() {
    }
}
