package net.vulkanmod.render.chunk.build.renderer;

import net.fabricmc.fabric.api.renderer.v1.material.BlendMode;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.material.ShadeMode;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.build.frapi.mesh.MutableQuadViewImpl;
import net.vulkanmod.render.chunk.build.frapi.render.AbstractBlockRenderContext;
import net.vulkanmod.render.chunk.build.light.LightPipeline;
import net.vulkanmod.render.chunk.build.light.data.QuadLightData;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.render.model.quad.QuadUtils;
import net.vulkanmod.render.model.quad.ModelQuadView;
import net.vulkanmod.render.vertex.TerrainBufferBuilder;
import net.vulkanmod.render.vertex.TerrainBuilder;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.render.vertex.format.I32_SNorm;
import net.vulkanmod.vulkan.util.ColorUtil;
import org.joml.Vector3f;

public class BlockRenderer extends AbstractBlockRenderContext {
    private Vector3f pos;

    private BuilderResources resources;
    private TerrainBuilder terrainBuilder;

    final boolean backFaceCulling = Initializer.CONFIG.backFaceCulling;

    private TerrainRenderType renderType;

    public void setResources(BuilderResources resources) {
        this.resources = resources;
    }

    public BlockRenderer(LightPipeline flatLightPipeline, LightPipeline smoothLightPipeline) {
        super();
        this.setupLightPipelines(flatLightPipeline, smoothLightPipeline);

        this.random = new SingleThreadedRandomSource(42L);
    }

    /**
     * Iroh (rc3, td-242adb): {@code -Diroh.faceTrace=x,y,z} logs every quad the mesher writes for that block, with its
     * face, cull face, light-face, AO, layer, per-vertex packed light and brightness, and the culling verdict of all six
     * faces, once per rebuild of its section. For comparing a block's mesh with what vanilla draws (powder snow walls).
     */
    private static final BlockPos FACE_TRACE = parseTrace(System.getProperty("iroh.faceTrace"));

    private static BlockPos parseTrace(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            String[] p = v.split(",");
            return new BlockPos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
        } catch (RuntimeException e) {
            Initializer.LOGGER.warn("Iroh: bad -Diroh.faceTrace={} (want x,y,z)", v);
            return null;
        }
    }

    private void traceQuad(MutableQuadViewImpl quad, boolean ao, BlendMode blend) {
        QuadLightData d = this.quadLightData;
        // rc10 (td-242adb): also the quad's plane (x, z), its facing bucket and the GPU-visible normal, so a missing
        // inner face (powder snow's model has inward faces 0.002 inside the block) shows up in the log
        Initializer.LOGGER.info("[iroh-face] {} {} nominal={} cull={} light={} facing={} ao={} blend={} layer={} lm=[{},{},{},{}] br=[{},{},{},{}] uv0=({},{}) x=[{},{},{},{}] y=[{},{},{},{}] z=[{},{},{},{}]",
                this.blockPos.toShortString(), this.blockState, quad.nominalFace(), quad.cullFace(), quad.lightFace(), quad.getQuadFacing(), ao, blend,
                this.renderType, Integer.toHexString(d.lm[0]), Integer.toHexString(d.lm[1]), Integer.toHexString(d.lm[2]), Integer.toHexString(d.lm[3]),
                d.br[0], d.br[1], d.br[2], d.br[3], quad.u(0), quad.v(0), quad.x(0), quad.x(1), quad.x(2), quad.x(3),
                quad.y(0), quad.y(1), quad.y(2), quad.y(3), quad.z(0), quad.z(1), quad.z(2), quad.z(3));
    }

    private void traceFaces() {
        StringBuilder sb = new StringBuilder();
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            BlockPos adj = this.blockPos.relative(dir);
            sb.append(dir).append('=').append(this.shouldRenderFace(dir) ? "draw" : "cull")
              .append('(').append(this.renderRegion.getBlockState(adj).getBlock().getDescriptionId()).append(") ");
        }
        Initializer.LOGGER.info("[iroh-face] {} {} faces: {}", this.blockPos.toShortString(), this.blockState, sb);
    }

    public void renderBlock(BlockState blockState, BlockPos blockPos, Vector3f pos) {
        this.pos = pos;
        this.blockPos = blockPos;
        this.blockState = blockState;
        this.seed = blockState.getSeed(blockPos);

        TerrainRenderType renderType = TerrainRenderType.get(ItemBlockRenderTypes.getChunkRenderType(blockState));
        renderType = TerrainRenderType.getRemapped(renderType);
        this.renderType = renderType;
        this.terrainBuilder = this.resources.builderPack.builder(renderType);
        this.terrainBuilder.setBlockAttributes(blockState);

        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(blockState);

        BlockAndTintGetter renderRegion = this.renderRegion;
        Vec3 offset = blockState.getOffset(renderRegion, blockPos);
        pos.add((float) offset.x, (float) offset.y, (float) offset.z);

        this.prepareForBlock(blockState, blockPos, model.useAmbientOcclusion());

        if (FACE_TRACE != null && FACE_TRACE.equals(blockPos))
            traceFaces();
        model.emitBlockQuads(renderRegion, blockState, blockPos, this.randomSupplier, this);
    }

    protected void endRenderQuad(MutableQuadViewImpl quad) {
        final RenderMaterial mat = quad.material();
        final int colorIndex = mat.disableColorIndex() ? -1 : quad.colorIndex();
        final TriState aoMode = mat.ambientOcclusion();
        final boolean ao = this.useAO && (aoMode == TriState.TRUE || (aoMode == TriState.DEFAULT && this.defaultAO));
        final boolean emissive = mat.emissive();
        final boolean vanillaShade = mat.shadeMode() == ShadeMode.VANILLA;

        TerrainBuilder terrainBuilder = getBufferBuilder(mat.blendMode());

        LightPipeline lightPipeline = ao ? this.smoothLightPipeline : this.flatLightPipeline;

        colorizeQuad(quad, colorIndex);
        shadeQuad(quad, lightPipeline, emissive, vanillaShade);
        if (FACE_TRACE != null && FACE_TRACE.equals(this.blockPos))
            traceQuad(quad, ao, mat.blendMode());
        bufferQuad(terrainBuilder, this.pos, quad, this.quadLightData);
    }

    /** Iroh (P1-6b FRAPI): the layer the current quad goes to (its material's blend mode, else the block's layer). */
    private TerrainRenderType quadRenderType;

    private TerrainBuilder getBufferBuilder(BlendMode blendMode) {
        if (blendMode == BlendMode.DEFAULT) {
            this.quadRenderType = this.renderType;
            return this.terrainBuilder;
        } else {
            TerrainRenderType renderType = TerrainRenderType.get(blendMode.blockRenderLayer);
            renderType = TerrainRenderType.getRemapped(renderType);
            this.quadRenderType = renderType;
            TerrainBuilder bufferBuilder = this.resources.builderPack.builder(renderType);
            bufferBuilder.setBlockAttributes(this.blockState);

            return bufferBuilder;
        }
    }

    /** Iroh P3-6: sections are meshed in the 36-byte pack vertex (startup-only, -Diroh.pack.mode=full) */
    private static final boolean PACK_WIDE = net.vulkanmod.render.PipelineManager.terrainVertexFormat
            == net.vulkanmod.render.vertex.CustomVertexFormat.PACK_WIDE_TERRAIN;

    /** the per-quad fields the 16-byte vertex() call does not carry: sprite bounds (from the quad's uvs, as Iris'
     *  mc_midTexCoord is the quad's uv centre), block origin in the section, face normal, emission, block id, layer */
    private void packQuadContext(ModelQuadView quad, int startIdx, TerrainRenderType target) {
        var c = dev.elemore.iroh.shaderpack.mesh.WideVertexBuilder.CONTEXT.get();
        float u0 = Float.MAX_VALUE, v0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            u0 = Math.min(u0, quad.getU(i));
            u1 = Math.max(u1, quad.getU(i));
            v0 = Math.min(v0, quad.getV(i));
            v1 = Math.max(v1, quad.getV(i));
        }
        c.sprite[0] = u0;
        c.sprite[1] = v0;
        c.sprite[2] = u1;
        c.sprite[3] = v1;
        c.blockOrigin[0] = this.blockPos.getX() & 15;
        c.blockOrigin[1] = this.blockPos.getY() & 15;
        c.blockOrigin[2] = this.blockPos.getZ() & 15;
        Vec3i n = quad.getFacingDirection().getNormal();
        c.normal[0] = n.getX();
        c.normal[1] = n.getY();
        c.normal[2] = n.getZ();
        c.emission = this.blockState.getLightEmission();
        final net.minecraft.world.level.block.Block block = this.blockState.getBlock();
        c.blockId = dev.elemore.iroh.shaderpack.runtime.PackStartup.blockId(block,
                () -> net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString());
        c.renderType = target.ordinal();
    }

    public void bufferQuad(TerrainBuilder terrainBuilder, Vector3f pos, ModelQuadView quad, QuadLightData quadLightData) {
        QuadFacing quadFacing = quad.getQuadFacing();

        // Iroh (P1-6b FRAPI): test the layer the quad is written to, not the block's layer. A BlendMode.TRANSLUCENT
        // material on a solid/cutout block (Polaris' chisel paint coat) went to a per-facing bucket of the translucent
        // builder, which only sorts and indexes the UNDEFINED bucket (TerrainBuilder.setupQuadSortingPoints): the
        // quads were left unsorted or not drawn at all.
        TerrainRenderType target = this.quadRenderType != null ? this.quadRenderType : this.renderType;
        if (target == TerrainRenderType.TRANSLUCENT || !this.backFaceCulling) {
            quadFacing = QuadFacing.UNDEFINED;
        }

        TerrainBufferBuilder bufferBuilder = terrainBuilder.getBufferBuilder(quadFacing.ordinal());

        Vec3i normal = quad.getFacingDirection().getNormal();
        int packedNormal = I32_SNorm.packNormal(normal.getX(), normal.getY(), normal.getZ());

        float[] brightnessArr = quadLightData.br;
        int[] lights = quadLightData.lm;

        // Rotate triangles if needed to fix AO anisotropy
        int idx = QuadUtils.getIterationStartIdx(brightnessArr, lights);

        bufferBuilder.ensureCapacity();
        if (PACK_WIDE)
            packQuadContext(quad, idx, target);

        for (byte i = 0; i < 4; ++i) {
            final float x = pos.x() + quad.getX(idx);
            final float y = pos.y() + quad.getY(idx);
            final float z = pos.z() + quad.getZ(idx);

            final int quadColor = quad.getColor(idx);

            int color = ColorUtil.ARGB.toRGBA(quadColor);

            final int light = lights[idx];
            final float u = quad.getU(idx);
            final float v = quad.getV(idx);

            bufferBuilder.vertex(x, y, z, color, u, v, light, packedNormal);

            idx = (idx + 1) & 0b11;
        }

    }

}

