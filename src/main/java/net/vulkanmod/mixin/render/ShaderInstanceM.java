package net.vulkanmod.mixin.render;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import dev.elemore.iroh.shader.ShaderConversion;
import dev.elemore.iroh.shader.ShaderFallbacks;
import dev.elemore.iroh.shader.ShaderSources;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.vulkanmod.Initializer;
import net.vulkanmod.gl.VkGlProgram;
import net.vulkanmod.interfaces.ShaderMixed;
import net.vulkanmod.render.shader.ShaderLoadUtil;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Mixin(ShaderInstance.class)
public class ShaderInstanceM implements ShaderMixed {

    @Shadow @Final private Map<String, com.mojang.blaze3d.shaders.Uniform> uniformMap;
    @Shadow @Final private String name;

    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform MODEL_VIEW_MATRIX;
    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform PROJECTION_MATRIX;
    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform COLOR_MODULATOR;
    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform LINE_WIDTH;
    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform GLINT_ALPHA;
    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform FOG_START;
    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform FOG_END;
    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform FOG_COLOR;
    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform FOG_SHAPE;
    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform TEXTURE_MATRIX;
    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform GAME_TIME;
    @Shadow @Final @Nullable public com.mojang.blaze3d.shaders.Uniform SCREEN_SIZE;

    @Shadow @Final private Map<String, Object> samplerMap;
    @Shadow @Final private List<Integer> samplerLocations;
    @Shadow @Final private List<String> samplerNames;

    @Shadow @Final private List<com.mojang.blaze3d.shaders.Uniform> uniforms;
    @Shadow @Final private VertexFormat vertexFormat;
    @Shadow @Final private int programId;
    @Shadow private static int lastProgramId;
    @Shadow public void setSampler(String string, Object object) {}
    @Unique private String vertexName;
    @Unique private String fragmentName;
    /** converted from GLSL at load time (mod / pack shader), as opposed to Iroh's built-in Vulkan GLSL */
    @Unique private boolean legacy;

    @Unique private GraphicsPipeline pipeline;
    @Unique boolean doUniformUpdate = false;

    public GraphicsPipeline getPipeline() {
        return pipeline;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void create(ResourceProvider resourceProvider, String name, VertexFormat format, CallbackInfo ci) {
        String configName = name;
        try {
            JsonObject config = ShaderLoadUtil.getJsonConfig("core", configName);

            if (config == null) {
                createLegacyShader(resourceProvider, format);
            } else if (!createPackOverride(resourceProvider, format)) {
                createPipeline(configName, format, config);
            }
        } catch (Throwable t) {
            // P1-2a: never leave a ShaderInstance without a pipeline; vanilla and mods keep using it
            Initializer.LOGGER.error("Iroh: shader {} could not be built", this.name, t);
            String pack = "?";
            try {
                pack = sourcesOf(resourceProvider).packOf(idOf(ShaderConversion.coreProgramPath(this.vertexName, ".vsh")));
            } catch (Throwable ignored) {
            }
            ShaderFallbacks.record(this.name, pack, "build", String.valueOf(t));
            createFromResult(ShaderConversion.fallback(this.name), format);
        }

        VkGlProgram program = VkGlProgram.getProgram(this.programId);
        program.bindPipeline(this.pipeline);
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ShaderInstance;getOrCreate(Lnet/minecraft/server/packs/resources/ResourceProvider;Lcom/mojang/blaze3d/shaders/Program$Type;Ljava/lang/String;)Lcom/mojang/blaze3d/shaders/Program;"))
    private Program loadNames(ResourceProvider resourceProvider, Program.Type type, String name) {
        switch (type) {
            case VERTEX -> this.vertexName = name;
            case FRAGMENT -> this.fragmentName = name;
        }

        // P1-2a: vanilla exposes these through getVertexProgram()/getFragmentProgram(); ImmediatelyFast reads
        // getName() of the vanilla ones at the end of every shader reload. A null here froze the pack on the
        // splash (NPE in the reload, rollback, recovery failure). id -1: close() is a no-op, no GL object.
        return new Program(type, -1, name);
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/shaders/Uniform;glBindAttribLocation(IILjava/lang/CharSequence;)V"))
    private void bindAttr(int program, int index, CharSequence name) {}

    /**
     * @author
     */
    @Overwrite
    public void close() {
        if (this.pipeline != null)
            this.pipeline.cleanUp();
    }

    /**
     * @author
     */
    @Overwrite
    public void apply() {
        if (this.doUniformUpdate) {

            for(int j = 0; j < this.samplerLocations.size(); ++j) {
                String string = this.samplerNames.get(j);
                if (this.samplerMap.get(string) != null) {
                    RenderSystem.activeTexture(33984 + j);
                    Object object = this.samplerMap.get(string);
                    int texId = -1;
                    if (object instanceof RenderTarget) {
                        texId = ((RenderTarget)object).getColorTextureId();
                    } else if (object instanceof AbstractTexture) {
                        texId = ((AbstractTexture)object).getId();
                    } else if (object instanceof Integer) {
                        texId = (Integer)object;
                    }

                    if (texId != -1) {
                        RenderSystem.bindTexture(texId);
                        // converted shaders read unit j directly (vanilla semantics); do not touch
                        // RenderSystem's shader texture slots, other shaders read those
                        if (!this.legacy)
                            RenderSystem.setShaderTexture(j, texId);
                    }
                }
            }
            if (this.legacy)
                RenderSystem.activeTexture(33984);

            for (com.mojang.blaze3d.shaders.Uniform uniform : this.uniforms) {
                uniform.upload();
            }

        }

        if (this.programId != lastProgramId) {
            ProgramManager.glUseProgram(this.programId);
            lastProgramId = this.programId;
        }

        bindPipeline();
    }

    /**
     * @author
     */
    @Overwrite
    public void setDefaultUniforms(VertexFormat.Mode mode, Matrix4f modelView, Matrix4f projection, Window window) {
        if (!this.doUniformUpdate)
            return;

        if (this.legacy) {
            // vanilla: Sampler0..11 follow RenderSystem's shader textures; apply() binds JSON sampler j to unit j
            for (int i = 0; i < 12; ++i)
                this.setSampler("Sampler" + i, RenderSystem.getShaderTexture(i));
        }

        if (this.MODEL_VIEW_MATRIX != null) {
            this.MODEL_VIEW_MATRIX.set(modelView);
        }

        if (this.PROJECTION_MATRIX != null) {
            this.PROJECTION_MATRIX.set(projection);
        }

        if (this.COLOR_MODULATOR != null) {
            this.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
        }

        if (this.GLINT_ALPHA != null) {
            this.GLINT_ALPHA.set(RenderSystem.getShaderGlintAlpha());
        }

        if (this.FOG_START != null) {
            this.FOG_START.set(RenderSystem.getShaderFogStart());
        }

        if (this.FOG_END != null) {
            this.FOG_END.set(RenderSystem.getShaderFogEnd());
        }

        if (this.FOG_COLOR != null) {
            this.FOG_COLOR.set(RenderSystem.getShaderFogColor());
        }

        if (this.FOG_SHAPE != null) {
            this.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
        }

        if (this.TEXTURE_MATRIX != null) {
            this.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
        }

        if (this.GAME_TIME != null) {
            this.GAME_TIME.set(RenderSystem.getShaderGameTime());
        }

        if (this.SCREEN_SIZE != null) {
            this.SCREEN_SIZE.set((float)window.getWidth(), (float)window.getHeight());
        }

        if (this.LINE_WIDTH != null && (mode == VertexFormat.Mode.LINES || mode == VertexFormat.Mode.LINE_STRIP)) {
            this.LINE_WIDTH.set(RenderSystem.getShaderLineWidth());
        }

        RenderSystem.setupShaderLights((ShaderInstance) (Object) this);
    }

    /**
     * @author
     */
    @Overwrite
    public void clear() {}

    @Unique
    private void bindPipeline() {
        if (this.pipeline == null) {
            throw new NullPointerException("Shader %s has no initialized pipeline".formatted(this.name));
        }

        Renderer renderer = Renderer.getInstance();
        renderer.bindGraphicsPipeline(pipeline);
        if (this.legacy)
            bindUnitTextures(pipeline);
        else
            VTextureSelector.bindShaderTextures(pipeline);
        renderer.uploadAndBindUBOs(pipeline);
    }

    public void setupUniformSuppliers(UBO ubo) {
        dev.elemore.iroh.shader.UniformSlots.connect(ubo, this.uniformMap);
    }

    public Supplier<MappedBuffer> getUniformSupplier(String name) {
        com.mojang.blaze3d.shaders.Uniform uniform1 = this.uniformMap.get(name);

        if (uniform1 == null) {
            Initializer.LOGGER.error(String.format("Error: field %s not present in uniform map", name));
            return null;
        }

        Supplier<MappedBuffer> supplier;
        ByteBuffer byteBuffer;

        if (uniform1.getType() <= 3) {
            byteBuffer = MemoryUtil.memByteBuffer(uniform1.getIntBuffer());
        } else if (uniform1.getType() <= 10) {
            byteBuffer = MemoryUtil.memByteBuffer(uniform1.getFloatBuffer());
        } else {
            throw new RuntimeException("out of bounds value for uniform " + uniform1);
        }

        MappedBuffer mappedBuffer = MappedBuffer.createFromBuffer(byteBuffer);
        supplier = () -> mappedBuffer;

        return supplier;
    }

    @Override
    public void setDoUniformsUpdate() {
        this.doUniformUpdate = true;
    }

    @Override
    public void setPipeline(GraphicsPipeline graphicsPipeline) {
        this.pipeline = graphicsPipeline;
    }

    @Unique
    private void createPipeline(String configName, VertexFormat format, JsonObject config) {
        Pipeline.Builder builder = new Pipeline.Builder(format, configName);
        builder.setUniformSupplierGetter(info -> this.getUniformSupplier(info.name));

        builder.parseBindings(config);

        ShaderLoadUtil.loadShaders(builder, config, configName, "core");

        GraphicsPipeline pipeline = builder.createGraphicsPipeline();
        this.pipeline = pipeline;
    }

    /**
     * Iroh (P1-10, RQ-14): a resource pack or mod overrides this vanilla core shader (its json, a stage or an include;
     * see CoreOverrides): build it from the resource manager's sources through the translator, with the vertex format
     * vanilla passed and the samplers / uniforms of the winning json (vanilla already parsed it into this instance).
     * Returns false to keep Iroh's built-in: nothing overridden, or the conversion failed (one log line + ShaderFallbacks).
     * Runs on every shader reload (F3+T, pack list change), so pack order and removal behave as in vanilla.
     */
    @Unique
    private boolean createPackOverride(ResourceProvider resourceProvider, VertexFormat format) {
        ShaderSources sources = sourcesOf(resourceProvider);
        dev.elemore.iroh.shader.CoreOverrides.Hit ov;
        try {
            ov = dev.elemore.iroh.shader.CoreOverrides.find(sources, this.name, this.vertexName, this.fragmentName);
        } catch (Throwable t) {
            return false;
        }
        if (ov == null)
            return false;

        ShaderConversion.Result result = convertFromSources(sources, format);
        if (!result.ok) {
            ShaderFallbacks.record(this.name, ov.pack(), result.stage, result.shortMessage());
            Initializer.LOGGER.warn("Iroh: core shader {} overridden by pack {} ({}) could not be converted ({}: {}); using Iroh's built-in",
                    this.name, ov.pack(), ov.file(), result.stage, result.shortMessage());
            return false;
        }
        try {
            createFromResult(result, format);
        } catch (Throwable t) {
            ShaderFallbacks.record(this.name, ov.pack(), "pipeline", String.valueOf(t));
            Initializer.LOGGER.warn("Iroh: core shader {} overridden by pack {} converted but no pipeline ({}); using Iroh's built-in",
                    this.name, ov.pack(), t.toString());
            this.legacy = false;
            this.doUniformUpdate = false;
            return false;
        }
        Initializer.LOGGER.info("Iroh: core shader {} is overridden by pack {} ({}); using the pack's GLSL", this.name, ov.pack(), ov.file());
        return true;
    }

    @Unique
    private ShaderConversion.Result convertFromSources(ShaderSources sources, VertexFormat format) {
        ShaderConversion.Input input = new ShaderConversion.Input(ShaderConversion.Kind.CORE, this.name,
                this.vertexName, this.fragmentName, new ArrayList<>(this.samplerNames),
                new java.util.LinkedHashSet<>(this.uniformMap.keySet()), format.getElementAttributeNames());
        return ShaderConversion.convert(input, sources, true);
    }

    @Unique
    private void createLegacyShader(ResourceProvider resourceProvider, VertexFormat format) {
        ShaderSources sources = sourcesOf(resourceProvider);
        ShaderConversion.Result result = convertFromSources(sources, format);
        if (!result.ok) {
            ShaderFallbacks.record(this.name, result.pack, result.stage, result.shortMessage());
            result = ShaderConversion.fallback(this.name);
        } else if (!result.output.zeroUniforms().isEmpty() || !result.output.notes().isEmpty()) {
            Initializer.LOGGER.info("Iroh: shader {} ({}) converted; zero-filled uniforms {}; {}", this.name, result.pack,
                    result.output.zeroUniforms(), result.output.notes());
        }

        try {
            createFromResult(result, format);
        } catch (Throwable t) {
            // converted fine but the pipeline could not be made (e.g. format/shader mismatch)
            ShaderFallbacks.record(this.name, result.pack, "pipeline", String.valueOf(t));
            createFromResult(ShaderConversion.fallback(this.name), format);
        }
    }

    @Unique
    private void createFromResult(ShaderConversion.Result result, VertexFormat format) {
        UBO ubo = ShaderConversion.buildUbo(result.output);
        this.setupUniformSuppliers(ubo);

        List<ImageDescriptor> images = new ArrayList<>();
        for (var s : result.output.samplers())
            images.add(new ImageDescriptor(s.binding(), "sampler2D", s.name(), s.imageIdx()));

        Pipeline.Builder builder = new Pipeline.Builder(format, this.name);
        List<UBO> ubos = new ArrayList<>();
        ubos.add(ubo);
        builder.setUniforms(ubos, images);
        builder.setSPIRVs(result.vertex, result.fragment);

        this.pipeline = builder.createGraphicsPipeline();
        this.legacy = true;
        this.doUniformUpdate = true;
    }

    /** Converted shaders sample emulated GL texture unit {@code imageIdx}, filled by apply() as vanilla does. */
    @Unique
    private static void bindUnitTextures(GraphicsPipeline pipeline) {
        for (ImageDescriptor d : pipeline.getImageDescriptors()) {
            if (VTextureSelector.getImage(d.imageIdx) == null) {
                VkGlTexture missing = VkGlTexture.getTexture(MissingTextureAtlasSprite.getTexture().getId());
                VTextureSelector.bindTexture(d.imageIdx, missing.getVulkanImage());
            }
        }
    }

    @Unique
    private static String idOf(String path) {
        return path.indexOf(':') >= 0 ? path : "minecraft:" + path;
    }

    @Unique
    private static ShaderSources sourcesOf(ResourceProvider provider) {
        return new ShaderSources() {
            @Override
            public String read(String id) {
                try {
                    ResourceLocation loc = ResourceLocation.tryParse(id);
                    if (loc == null)
                        return null;
                    var res = provider.getResource(loc);
                    if (res.isEmpty())
                        return null;
                    try (InputStream in = res.get().open()) {
                        return IOUtils.toString(in, StandardCharsets.UTF_8);
                    }
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            public String packOf(String id) {
                try {
                    ResourceLocation loc = ResourceLocation.tryParse(id);
                    return loc == null ? "?" : provider.getResource(loc).map(r -> r.sourcePackId()).orElse("?");
                } catch (Exception e) {
                    return "?";
                }
            }
        };
    }
}
