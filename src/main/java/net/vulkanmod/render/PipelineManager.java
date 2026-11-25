package net.vulkanmod.render;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
import net.vulkanmod.render.shader.ShaderLoadUtil;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;

import java.util.function.Function;

public abstract class PipelineManager {
    public static VertexFormat terrainVertexFormat;

    public static void setTerrainVertexFormat(VertexFormat format) {
        terrainVertexFormat = format;
    }

    static GraphicsPipeline
            terrainShader, terrainShaderEarlyZ,
            fastBlitPipeline, cloudsPipeline;
    /** P2-7a (iroh.terrain.mdi): world-arena terrain (firstInstance = section offset from a frame origin, terrain_world.vsh); null when the vertex format is not the compressed one */
    static GraphicsPipeline terrainWorldShader, terrainWorldShaderEarlyZ;

    private static Function<TerrainRenderType, GraphicsPipeline> shaderGetter;

    public static void init() {
        // Iroh P3-6: FULL pack mode meshes 36-byte sections from the start (restart-only switch); default unchanged
        setTerrainVertexFormat(dev.elemore.iroh.shaderpack.runtime.PackStartup.FULL
                ? CustomVertexFormat.PACK_WIDE_TERRAIN : CustomVertexFormat.COMPRESSED_TERRAIN);
        createBasicPipelines();
        setDefaultShader();
        ThreadBuilderPack.defaultTerrainBuilderConstructor();
    }

    public static void setDefaultShader() {
        setShaderGetter(
                renderType -> renderType == TerrainRenderType.TRANSLUCENT ? terrainShaderEarlyZ : terrainShader);
        defaultGetter = true;
    }

    private static void createBasicPipelines() {
        terrainShaderEarlyZ = createPipeline("terrain_early_z" /* lower-case: ResourceLocation rejects "Z" (boot ERROR "Invalid path in mod resource-pack iroh") */, terrainVertexFormat);
        terrainShader = createPipeline("terrain", terrainVertexFormat);
        fastBlitPipeline = createPipeline("blit", CustomVertexFormat.NONE);
        cloudsPipeline = createPipeline("clouds", DefaultVertexFormat.POSITION_COLOR);
        if (terrainVertexFormat == CustomVertexFormat.COMPRESSED_TERRAIN) { // terrain_world.vsh reads the compressed vertex only
            terrainWorldShaderEarlyZ = createPipeline("terrain_world_early_z", terrainVertexFormat);
            terrainWorldShader = createPipeline("terrain_world", terrainVertexFormat);
        }
    }

    private static GraphicsPipeline createPipeline(String configName, VertexFormat vertexFormat) {
        Pipeline.Builder pipelineBuilder = new Pipeline.Builder(vertexFormat, configName);

        JsonObject config = ShaderLoadUtil.getJsonConfig("basic", configName);
        pipelineBuilder.parseBindings(config);

        ShaderLoadUtil.loadShaders(pipelineBuilder, config, configName, "basic");

        return pipelineBuilder.createGraphicsPipeline();
    }

    public static GraphicsPipeline getTerrainShader(TerrainRenderType renderType) {
        return shaderGetter.apply(renderType);
    }

    public static void setShaderGetter(Function<TerrainRenderType, GraphicsPipeline> consumer) {
        shaderGetter = consumer;
        defaultGetter = false; // another terrain program (a mod or pack path): world-arena drawing stays off (P2-7a)
    }

    /** P2-7a: the world-arena terrain pipeline of a layer (translucent: early-Z variant, as {@link #setDefaultShader}); null if unavailable */
    public static GraphicsPipeline getTerrainWorldShader(TerrainRenderType renderType) {
        return renderType == TerrainRenderType.TRANSLUCENT ? terrainWorldShaderEarlyZ : terrainWorldShader;
    }

    /** P2-7a: world-arena terrain can draw (compressed vertex format, pipelines built, default terrain shaders) */
    public static boolean terrainWorldAvailable() {
        return terrainWorldShader != null && terrainWorldShaderEarlyZ != null && defaultGetter;
    }

    private static boolean defaultGetter;

    public static GraphicsPipeline getTerrainDirectShader(RenderType renderType) {
        return terrainShader;
    }

    public static GraphicsPipeline getTerrainIndirectShader(RenderType renderType) {
        return terrainShaderEarlyZ;
    }

    public static GraphicsPipeline getFastBlitPipeline() {
        return fastBlitPipeline;
    }

    public static GraphicsPipeline getCloudsPipeline() {
        return cloudsPipeline;
    }

    public static void destroyPipelines() {
        terrainShaderEarlyZ.cleanUp();
        terrainShader.cleanUp();
        fastBlitPipeline.cleanUp();
        cloudsPipeline.cleanUp();
        if (terrainWorldShader != null) terrainWorldShader.cleanUp();
        if (terrainWorldShaderEarlyZ != null) terrainWorldShaderEarlyZ.cleanUp();
        terrainWorldShader = terrainWorldShaderEarlyZ = null;
    }
}
