package net.vulkanmod.mixin.compatibility;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.BlendMode;
import com.mojang.blaze3d.shaders.EffectProgram;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.vulkanmod.gl.VkGlProgram;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import dev.elemore.iroh.shader.ShaderConversion;
import dev.elemore.iroh.shader.ShaderFallbacks;
import dev.elemore.iroh.shader.ShaderSources;
import dev.elemore.iroh.shader.UniformSlots;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.apache.commons.io.IOUtils;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

@Mixin(EffectInstance.class)
public class EffectInstanceM {

    @Shadow @Final private Map<String, com.mojang.blaze3d.shaders.Uniform> uniformMap;
    @Shadow @Final private List<com.mojang.blaze3d.shaders.Uniform> uniforms;

    @Shadow private boolean dirty;
    @Shadow private static EffectInstance lastAppliedEffect;
    @Shadow @Final private BlendMode blend;
    @Shadow private static int lastProgramId;
    @Shadow @Final private int programId;
    @Shadow @Final private List<Integer> samplerLocations;
    @Shadow @Final private List<String> samplerNames;
    @Shadow @Final private Map<String, IntSupplier> samplerMap;

    @Shadow @Final private String name;

    @Unique private static GraphicsPipeline lastPipeline;
    @Unique private GraphicsPipeline pipeline;

    @Inject(method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/EffectInstance;updateLocations()V",
                    shift = At.Shift.AFTER),
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void inj(ResourceProvider resourceProvider, String string, CallbackInfo ci, ResourceLocation resourceLocation, Resource resource, Reader reader, JsonObject jsonObject, String string2, String string3) {
        createShaders(resourceProvider, string2, string3);
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/EffectInstance;getOrCreate(Lnet/minecraft/server/packs/resources/ResourceProvider;Lcom/mojang/blaze3d/shaders/Program$Type;Ljava/lang/String;)Lcom/mojang/blaze3d/shaders/EffectProgram;"))
    private EffectProgram redirectShader(ResourceProvider resourceProvider, Program.Type type, String string) {
        // P1-2a: getVertexProgram()/getFragmentProgram() must not return null; id -1 = no GL object
        return new EffectProgram(type, -1, string);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    public void close() {
        for (com.mojang.blaze3d.shaders.Uniform uniform : this.uniforms) {
            uniform.close();
        }

        if (this.pipeline != null)
            this.pipeline.scheduleCleanUp();
    }

    private void createShaders(ResourceProvider resourceManager, String vertexShader, String fragShader) {
        String id = "program/" + this.name;
        ShaderConversion.Result result;
        try {
            ShaderConversion.Input input = new ShaderConversion.Input(ShaderConversion.Kind.PROGRAM, this.name,
                    vertexShader, fragShader, new ArrayList<>(this.samplerNames),
                    new LinkedHashSet<>(this.uniformMap.keySet()), DefaultVertexFormat.POSITION.getElementAttributeNames());
            result = ShaderConversion.convert(input, sourcesOf(resourceManager), true);
        } catch (Throwable t) {
            result = null;
            ShaderFallbacks.record(id, "?", "convert", String.valueOf(t));
        }
        if (result != null && !result.ok) {
            ShaderFallbacks.record(id, result.pack, result.stage, result.shortMessage());
            result = null;
        } else if (result != null && !result.output.zeroUniforms().isEmpty()) {
            Initializer.LOGGER.info("Iroh: shader {} ({}) converted; zero-filled uniforms {}", id, result.pack,
                    result.output.zeroUniforms());
        }

        try {
            build(result != null ? result : ShaderConversion.fallback(id));
        } catch (Throwable t) {
            ShaderFallbacks.record(id, result != null ? result.pack : "?", "pipeline", String.valueOf(t));
            build(ShaderConversion.fallback(id));
        }

        VkGlProgram program = VkGlProgram.getProgram(this.programId);
        program.bindPipeline(this.pipeline);
    }

    @Unique
    private void build(ShaderConversion.Result result) {
        UBO ubo = ShaderConversion.buildUbo(result.output);
        UniformSlots.connect(ubo, this.uniformMap);

        List<ImageDescriptor> images = new ArrayList<>();
        for (var s : result.output.samplers())
            images.add(new ImageDescriptor(s.binding(), "sampler2D", s.name(), s.imageIdx()));

        Pipeline.Builder builder = new Pipeline.Builder(DefaultVertexFormat.POSITION, this.name);
        List<UBO> ubos = new ArrayList<>();
        ubos.add(ubo);
        builder.setUniforms(ubos, images);
        builder.setSPIRVs(result.vertex, result.fragment);
        this.pipeline = builder.createGraphicsPipeline();
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

    private String[] decompose(String string, char c) {
        String[] strings = new String[]{"minecraft", string};
        int i = string.indexOf(c);
        if (i >= 0) {
            strings[1] = string.substring(i + 1);
            if (i >= 1) {
                strings[0] = string.substring(0, i);
            }
        }

        return strings;
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    public void apply() {
        this.dirty = false;
        this.blend.apply();

        ProgramManager.glUseProgram(this.programId);

        Renderer renderer = Renderer.getInstance();

        if (this.pipeline != lastPipeline) {
            renderer.bindGraphicsPipeline(pipeline);
            lastPipeline = this.pipeline;
        }

        for(int i = 0; i < this.samplerLocations.size(); ++i) {
            String string = this.samplerNames.get(i);
            IntSupplier intSupplier = this.samplerMap.get(string);
            if (intSupplier != null) {
                RenderSystem.activeTexture(GL30.GL_TEXTURE0 + i);
                int j = intSupplier.getAsInt();
                if (j != -1) {
                    RenderSystem.bindTexture(j);
                    com.mojang.blaze3d.shaders.Uniform.uploadInteger(this.samplerLocations.get(i), i);
                }
            }
        }

        for (com.mojang.blaze3d.shaders.Uniform uniform : this.uniforms) {
            uniform.upload();
        }

        renderer.uploadAndBindUBOs(pipeline);

    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    public void clear() {
        RenderSystem.assertOnRenderThread();
        ProgramManager.glUseProgram(0);
        lastProgramId = -1;
        lastAppliedEffect = null;
        lastPipeline = null;

        for(int i = 0; i < this.samplerLocations.size(); ++i) {
            if (this.samplerMap.get(this.samplerNames.get(i)) != null) {
                GlStateManager._activeTexture(GL30.GL_TEXTURE0 + i);
                GlStateManager._bindTexture(0);
            }
        }

    }
}
