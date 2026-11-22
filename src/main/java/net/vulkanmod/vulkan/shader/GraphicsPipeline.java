package net.vulkanmod.vulkan.shader;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.vulkanmod.interfaces.VertexFormatMixed;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackGet;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class GraphicsPipeline extends Pipeline {
    private final Object2LongMap<PipelineState> graphicsPipelines = new Object2LongOpenHashMap<>();

    private final VertexFormat vertexFormat;

    /** Iroh P3-10: the vertex format this pipeline was built for (program substitution picks the pack layout from it) */
    public VertexFormat getVertexFormat() {
        return vertexFormat;
    }
    private final VertexInputDescription vertexInputDescription;

    private long vertShaderModule = 0;
    private long fragShaderModule = 0;

    GraphicsPipeline(Builder builder) {
        super(builder.shaderPath);
        this.buffers = builder.UBOs;
        this.manualUBO = builder.manualUBO;
        this.imageDescriptors = builder.imageDescriptors;
        this.pushConstants = builder.pushConstants;
        this.vertexFormat = builder.vertexFormat;

        this.vertexInputDescription = new VertexInputDescription(this.vertexFormat, inputLocations(builder.vertShaderSPIRV));

        createDescriptorSetLayout();
        createPipelineLayout();
        createShaderModules(builder.vertShaderSPIRV, builder.fragShaderSPIRV);

        if (builder.renderPass != null)
            graphicsPipelines.computeIfAbsent(PipelineState.DEFAULT,
                    this::createGraphicsPipeline);

        createDescriptorSets(Renderer.getFramesNum());

        PIPELINES.add(this);
    }

    /**
     * int-2b: the depth bias / line width bits (CmdState) the pipeline created for {@code state} declares dynamic.
     * Viewport and scissor are dynamic in every pipeline. Used at creation and at every bind (Renderer).
     */
    public static int dynamicStates(PipelineState state) {
        int ars = state.assemblyRasterState;
        return PipelineState.AssemblyRasterState.decodeTopology(ars) == VK_PRIMITIVE_TOPOLOGY_LINE_LIST
                || PipelineState.AssemblyRasterState.decodePolygonMode(ars) == VK_POLYGON_MODE_LINE
                ? dev.elemore.iroh.render.CmdState.ALL : dev.elemore.iroh.render.CmdState.DEPTH_BIAS;
    }

    public long getHandle(PipelineState state) {
        return graphicsPipelines.computeIfAbsent(state, this::createGraphicsPipeline);
    }

    private long createGraphicsPipeline(PipelineState state) {
        try (MemoryStack stack = stackPush()) {
            ByteBuffer entryPoint = stack.UTF8("main");

            VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);

            VkPipelineShaderStageCreateInfo vertShaderStageInfo = shaderStages.get(0);

            vertShaderStageInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            vertShaderStageInfo.stage(VK_SHADER_STAGE_VERTEX_BIT);
            vertShaderStageInfo.module(vertShaderModule);
            vertShaderStageInfo.pName(entryPoint);

            VkPipelineShaderStageCreateInfo fragShaderStageInfo = shaderStages.get(1);

            fragShaderStageInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            fragShaderStageInfo.stage(VK_SHADER_STAGE_FRAGMENT_BIT);
            fragShaderStageInfo.module(fragShaderModule);
            fragShaderStageInfo.pName(entryPoint);

            // ===> VERTEX STAGE <===

            VkPipelineVertexInputStateCreateInfo vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack);
            vertexInputInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);
            vertexInputInfo.pVertexBindingDescriptions(vertexInputDescription.bindingDescriptions);
            vertexInputInfo.pVertexAttributeDescriptions(vertexInputDescription.attributeDescriptions);

            // ===> ASSEMBLY STAGE <===

            final int topology = PipelineState.AssemblyRasterState.decodeTopology(state.assemblyRasterState);

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack);
            inputAssembly.sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO);
            inputAssembly.topology(topology);
            inputAssembly.primitiveRestartEnable(false);

            // ===> VIEWPORT & SCISSOR

            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack);
            viewportState.sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO);

            viewportState.viewportCount(1);
            viewportState.scissorCount(1);

            // ===> RASTERIZATION STAGE <===

            final int polygonMode = PipelineState.AssemblyRasterState.decodePolygonMode(state.assemblyRasterState);
            final int cullMode = PipelineState.AssemblyRasterState.decodeCullMode(state.assemblyRasterState);

            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack);
            rasterizer.sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO);
            rasterizer.depthClampEnable(false);
            rasterizer.rasterizerDiscardEnable(false);
            rasterizer.polygonMode(polygonMode);
            rasterizer.lineWidth(1.0f);
            rasterizer.cullMode(cullMode);
            rasterizer.frontFace(dev.elemore.iroh.glshim.Orientation.FRONT_FACE); // conventions: Orientation
            rasterizer.depthBiasEnable(true);

            // ===> MULTISAMPLING <===

            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack);
            multisampling.sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO);
            multisampling.sampleShadingEnable(false);
            multisampling.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            // ===> DEPTH TEST <===

            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack);
            depthStencil.sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO);
            depthStencil.depthTestEnable(PipelineState.DepthState.depthTest(state.depthState_i));
            depthStencil.depthWriteEnable(PipelineState.DepthState.depthMask(state.depthState_i));
            depthStencil.depthCompareOp(PipelineState.DepthState.decodeDepthFun(state.depthState_i));
            depthStencil.depthBoundsTestEnable(false);
            depthStencil.minDepthBounds(0.0f); // Optional
            depthStencil.maxDepthBounds(1.0f); // Optional
            depthStencil.stencilTestEnable(false);

            // ===> COLOR BLENDING <===

            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            colorBlendAttachment.colorWriteMask(state.colorMask_i);

            if (PipelineState.BlendState.enable(state.blendState_i)) {
                colorBlendAttachment.blendEnable(true);
                colorBlendAttachment.srcColorBlendFactor(PipelineState.BlendState.getSrcRgbFactor(state.blendState_i));
                colorBlendAttachment.dstColorBlendFactor(PipelineState.BlendState.getDstRgbFactor(state.blendState_i));
                colorBlendAttachment.colorBlendOp(PipelineState.BlendState.blendOp(state.blendState_i));
                colorBlendAttachment.srcAlphaBlendFactor(PipelineState.BlendState.getSrcAlphaFactor(state.blendState_i));
                colorBlendAttachment.dstAlphaBlendFactor(PipelineState.BlendState.getDstAlphaFactor(state.blendState_i));
                colorBlendAttachment.alphaBlendOp(PipelineState.BlendState.blendOp(state.blendState_i));
            }
            else {
                colorBlendAttachment.blendEnable(false);
            }

            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack);
            colorBlending.sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO);
            colorBlending.logicOpEnable(PipelineState.LogicOpState.enable(state.logicOp_i));
            colorBlending.logicOp(PipelineState.LogicOpState.decodeFun(state.logicOp_i));
            colorBlending.pAttachments(colorBlendAttachment);
            colorBlending.blendConstants(stack.floats(0.0f, 0.0f, 0.0f, 0.0f));

            // ===> DYNAMIC STATES <===

            VkPipelineDynamicStateCreateInfo dynamicStates = VkPipelineDynamicStateCreateInfo.calloc(stack);
            dynamicStates.sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO);

            if ((dynamicStates(state) & dev.elemore.iroh.render.CmdState.LINE_WIDTH) != 0) { // int-2b: one source with the bind
                dynamicStates.pDynamicStates(
                        stack.ints(VK_DYNAMIC_STATE_DEPTH_BIAS, VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR,
                                   VK_DYNAMIC_STATE_LINE_WIDTH));
            }
            else {
                dynamicStates.pDynamicStates(
                        stack.ints(VK_DYNAMIC_STATE_DEPTH_BIAS, VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));
            }

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            pipelineInfo.sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO);
            pipelineInfo.pStages(shaderStages);
            pipelineInfo.pVertexInputState(vertexInputInfo);
            pipelineInfo.pInputAssemblyState(inputAssembly);
            pipelineInfo.pViewportState(viewportState);
            pipelineInfo.pRasterizationState(rasterizer);
            pipelineInfo.pMultisampleState(multisampling);
            pipelineInfo.pDepthStencilState(depthStencil);
            pipelineInfo.pColorBlendState(colorBlending);
            pipelineInfo.pDynamicState(dynamicStates);
            pipelineInfo.layout(pipelineLayout);
            pipelineInfo.basePipelineHandle(VK_NULL_HANDLE);
            pipelineInfo.basePipelineIndex(-1);

            if (!Vulkan.DYNAMIC_RENDERING) {
                pipelineInfo.renderPass(state.renderPass.getId());
                pipelineInfo.subpass(0);
            }
            else {
                //dyn-rendering
                VkPipelineRenderingCreateInfoKHR renderingInfo = VkPipelineRenderingCreateInfoKHR.calloc(stack);
                renderingInfo.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR);
                renderingInfo.pColorAttachmentFormats(stack.ints(state.renderPass.getFramebuffer().getFormat()));
                renderingInfo.depthAttachmentFormat(state.renderPass.getFramebuffer().getDepthFormat());
                pipelineInfo.pNext(renderingInfo);
            }

            LongBuffer pGraphicsPipeline = stack.mallocLong(1);

            Vulkan.checkResult(vkCreateGraphicsPipelines(DeviceManager.vkDevice, PIPELINE_CACHE, pipelineInfo, null, pGraphicsPipeline),
                               "Failed to create graphics pipeline " + this.name);

            return pGraphicsPipeline.get(0);
        }
    }

    /** Bit mask of the vertex input locations (below 64) the SPIR-V declares; 0 when it cannot be read. */
    static long inputLocations(SPIRVUtils.SPIRV vert) {
        try {
            ByteBuffer code = vert.bytecode().duplicate().order(java.nio.ByteOrder.nativeOrder());
            java.util.Map<Integer, Integer> locationOf = new java.util.HashMap<>();
            java.util.List<Integer> inputs = new java.util.ArrayList<>();
            int words = code.remaining() / 4;
            int pos = 5; // after the header
            while (pos < words) {
                int w = code.getInt(code.position() + pos * 4);
                int count = w >>> 16;
                int op = w & 0xFFFF;
                if (count == 0)
                    return 0L;
                if (op == 71 && count >= 4 && code.getInt(code.position() + (pos + 2) * 4) == 30) { // OpDecorate Location
                    locationOf.put(code.getInt(code.position() + (pos + 1) * 4), code.getInt(code.position() + (pos + 3) * 4));
                } else if (op == 59 && count >= 4 && code.getInt(code.position() + (pos + 3) * 4) == 1) { // OpVariable Input
                    inputs.add(code.getInt(code.position() + (pos + 2) * 4));
                }
                pos += count;
            }
            long mask = 0L;
            for (int id : inputs) {
                Integer loc = locationOf.get(id);
                if (loc != null && loc < 64)
                    mask |= 1L << loc;
            }
            return mask;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private void createShaderModules(SPIRVUtils.SPIRV vertSpirv, SPIRVUtils.SPIRV fragSpirv) {
        this.vertShaderModule = createShaderModule(vertSpirv.bytecode());
        this.fragShaderModule = createShaderModule(fragSpirv.bytecode());
    }

    public void cleanUp() {
        if (this.destroyed) return; // rc15: idempotent (shutdown destroyAll + a pending scheduled cleanUp)
        this.destroyed = true;
        vkDestroyShaderModule(DeviceManager.vkDevice, vertShaderModule, null);
        vkDestroyShaderModule(DeviceManager.vkDevice, fragShaderModule, null);

        vertexInputDescription.cleanUp();

        destroyDescriptorSets();

        graphicsPipelines.forEach((state, pipeline) -> {
            vkDestroyPipeline(DeviceManager.vkDevice, pipeline, null);
        });
        graphicsPipelines.clear();

        vkDestroyDescriptorSetLayout(DeviceManager.vkDevice, descriptorSetLayout, null);
        vkDestroyPipelineLayout(DeviceManager.vkDevice, pipelineLayout, null);

        PIPELINES.remove(this);
        Renderer.getInstance().removeUsedPipeline(this);
    }

    static class VertexInputDescription {
        final VkVertexInputAttributeDescription.Buffer attributeDescriptions;
        final VkVertexInputBindingDescription.Buffer bindingDescriptions;

        VertexInputDescription(VertexFormat vertexFormat, long consumedLocations) {
            this.bindingDescriptions = getBindingDescription(vertexFormat);
            this.attributeDescriptions = dropUnconsumed(getAttributeDescriptions(vertexFormat), consumedLocations);
        }

        /**
         * Validation warns for every attribute the vertex shader does not read. The binding stride (the vertex layout)
         * stays as the format defines it; only the attribute descriptions the shader never consumes are left out (P1-8b).
         */
        private static VkVertexInputAttributeDescription.Buffer dropUnconsumed(VkVertexInputAttributeDescription.Buffer all, long consumed) {
            if (consumed == 0L)
                return all;

            int kept = 0;
            for (int i = 0; i < all.remaining(); i++) {
                if (isConsumed(all.get(i).location(), consumed))
                    kept++;
            }

            if (kept == all.remaining() || kept == 0)
                return all;

            VkVertexInputAttributeDescription.Buffer out = VkVertexInputAttributeDescription.calloc(kept);
            int n = 0;
            for (int i = 0; i < all.remaining(); i++) {
                VkVertexInputAttributeDescription d = all.get(i);
                if (isConsumed(d.location(), consumed)) {
                    VkVertexInputAttributeDescription o = out.get(n++);
                    o.binding(d.binding());
                    o.location(d.location());
                    o.format(d.format());
                    o.offset(d.offset());
                }
            }
            MemoryUtil.memFree(all);
            return out.rewind();
        }

        private static boolean isConsumed(int location, long consumed) {
            return location >= 64 || (consumed & (1L << location)) != 0;
        }

        void cleanUp() {
            MemoryUtil.memFree(this.bindingDescriptions);
            MemoryUtil.memFree(this.attributeDescriptions);
        }
    }

    private static VkVertexInputBindingDescription.Buffer getBindingDescription(VertexFormat vertexFormat) {
        VkVertexInputBindingDescription.Buffer bindingDescription = VkVertexInputBindingDescription.calloc(1);

        bindingDescription.binding(0);
        bindingDescription.stride(vertexFormat.getVertexSize());
        bindingDescription.inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

        return bindingDescription;
    }

    private static VkVertexInputAttributeDescription.Buffer getAttributeDescriptions(VertexFormat vertexFormat) {
        List<VertexFormatElement> elements = vertexFormat.getElements();

        int size = elements.size();

        VkVertexInputAttributeDescription.Buffer attributeDescriptions = VkVertexInputAttributeDescription.calloc(size);

        int offset = 0;

        for (int i = 0; i < size; ++i) {
            VkVertexInputAttributeDescription posDescription = attributeDescriptions.get(i);
            posDescription.binding(0);
            posDescription.location(i);

            VertexFormatElement formatElement = elements.get(i);
            VertexFormatElement.Usage usage = formatElement.usage();
            VertexFormatElement.Type type = formatElement.type();
            int elementCount = formatElement.count();

            switch (usage) {
                case POSITION -> {
                    switch (type) {
                        case FLOAT -> {
                            posDescription.format(VK_FORMAT_R32G32B32_SFLOAT);
                            posDescription.offset(offset);

                            offset += 12;
                        }
                        case SHORT -> {
                            posDescription.format(VK_FORMAT_R16G16B16A16_SINT);
                            posDescription.offset(offset);

                            offset += 8;
                        }
                        case BYTE -> {
                            posDescription.format(VK_FORMAT_R8G8B8A8_SINT);
                            posDescription.offset(offset);

                            offset += 4;
                        }
                    }

                }

                case COLOR -> {
                    switch (type) {
                        case UBYTE -> {
                            posDescription.format(VK_FORMAT_R8G8B8A8_UNORM);
                            posDescription.offset(offset);

                            offset += 4;
                        }
                        case UINT -> {
                            posDescription.format(VK_FORMAT_R32_UINT);
                            posDescription.offset(offset);

                            offset += 4;
                        }
                    }
                }

                case UV -> {
                    switch (type) {
                        case FLOAT -> {
                            posDescription.format(VK_FORMAT_R32G32_SFLOAT);
                            posDescription.offset(offset);

                            offset += 8;
                        }
                        case SHORT -> {
                            posDescription.format(VK_FORMAT_R16G16_SINT);
                            posDescription.offset(offset);

                            offset += 4;
                        }
                        case USHORT -> {
                            posDescription.format(VK_FORMAT_R16G16_UINT);
                            posDescription.offset(offset);

                            offset += 4;
                        }
                        case UINT -> {
                            posDescription.format(VK_FORMAT_R32_UINT);
                            posDescription.offset(offset);

                            offset += 4;
                        }
                    }
                }

                case NORMAL -> {
                    posDescription.format(VK_FORMAT_R8G8B8A8_SNORM);
                    posDescription.offset(offset);

                    offset += 4;
                }

                case GENERIC -> {
                    if (type == VertexFormatElement.Type.SHORT && elementCount == 1) {
                        posDescription.format(VK_FORMAT_R16_SINT);
                        posDescription.offset(offset);

                        offset += 2;
                    }
                    else if (type == VertexFormatElement.Type.INT && elementCount == 1) {
                        posDescription.format(VK_FORMAT_R32_SINT);
                        posDescription.offset(offset);

                        offset += 4;
                    }
                    else if (type == VertexFormatElement.Type.FLOAT && elementCount >= 1 && elementCount <= 4) {
                        // Iroh (P1-2 slice 2): raw-GL program draws (dev.elemore.iroh.glshim.GlPrograms) feed floats
                        posDescription.format(switch (elementCount) {
                            case 1 -> VK_FORMAT_R32_SFLOAT;
                            case 2 -> VK_FORMAT_R32G32_SFLOAT;
                            case 3 -> VK_FORMAT_R32G32B32_SFLOAT;
                            default -> VK_FORMAT_R32G32B32A32_SFLOAT;
                        });
                        posDescription.offset(offset);
                        offset += 4 * elementCount;
                    }
                    else {
                        throw new RuntimeException(String.format("Unknown format: %s", usage));
                    }
                }

                default -> throw new RuntimeException(String.format("Unknown format: %s", usage));
            }

            posDescription.offset(((VertexFormatMixed) (vertexFormat)).getOffset(i));
        }

        return attributeDescriptions.rewind();
    }
}
