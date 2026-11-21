package net.vulkanmod.vulkan.shader;

import com.mojang.blaze3d.platform.GlStateManager;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.RenderPass;

import java.util.Objects;

import static org.lwjgl.vulkan.VK10.*;

public class PipelineState {
    /** Iroh (P1-2): first, because DEFAULT below already encodes state (static init order). */
    private static final java.util.Set<String> LOGGED_UNKNOWN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final int DEFAULT_DEPTH_OP = 515;
//    private static final int DEFAULT_DEPTH_OP = 518;

    public static PipelineState.BlendInfo blendInfo = PipelineState.defaultBlendInfo();

    public static final PipelineState DEFAULT = new PipelineState(getAssemblyRasterState(), getBlendState(), getDepthState(), getLogicOpState(), VRenderSystem.getColorMask(), null);

    public static PipelineState currentState = DEFAULT;

    public static PipelineState getCurrentPipelineState(RenderPass renderPass) {
        int assemblyRasterState = getAssemblyRasterState();
        int blendState = getBlendState();
        int currentColorMask = VRenderSystem.getColorMask();
        int depthState = getDepthState();
        int logicOp = getLogicOpState();

        if (currentState.checkEquals(assemblyRasterState, blendState, depthState, logicOp, currentColorMask, renderPass))
            return currentState;
        else
            return currentState = new PipelineState(assemblyRasterState, blendState, depthState, logicOp, currentColorMask, renderPass);
    }

    public static int getBlendState() {
        return BlendState.getState(blendInfo);
    }

    public static int getAssemblyRasterState() {
        // Iroh (RQ-11, RQ-16): every target is drawn in GL row order (positive viewport height), which mirrors the
        // winding Vulkan sees; GlState.vkCullMode maps glCullFace / glFrontFace accordingly.
        int state = AssemblyRasterState.encode(VRenderSystem.cull, VRenderSystem.topology, VRenderSystem.polygonMode);
        if (VRenderSystem.cull)
            state = AssemblyRasterState.withCullMode(state, dev.elemore.iroh.glshim.GlState.vkCullMode());
        return state;
    }

    public static int getDepthState() {
        int depthState = 0;

        depthState |= VRenderSystem.depthTest ? DepthState.DEPTH_TEST_BIT : 0;
        depthState |= VRenderSystem.depthMask ? DepthState.DEPTH_MASK_BIT : 0;

        depthState |= DepthState.encodeDepthFun(VRenderSystem.depthFun);

        return depthState;
    }

    public static int getLogicOpState() {
        int logicOpState = 0;

        logicOpState |= VRenderSystem.logicOp ? LogicOpState.ENABLE_BIT : 0;

        logicOpState |= LogicOpState.encodeLogicOpFun(VRenderSystem.logicOpFun);

        return logicOpState;
    }

    final RenderPass renderPass;

    int assemblyRasterState;
    int blendState_i;
    int depthState_i;
    int colorMask_i;
    int logicOp_i;

    public PipelineState(int assemblyRasterState, int blendState, int depthState, int logicOp, int colorMask,
                         RenderPass renderPass) {
        this.renderPass = renderPass;

        this.assemblyRasterState = assemblyRasterState;
        this.blendState_i = blendState;
        this.depthState_i = depthState;
        this.colorMask_i = colorMask;
        this.logicOp_i = logicOp;
    }

    private boolean checkEquals(int assemblyRasterState, int blendState, int depthState, int logicOp, int colorMask,
                                RenderPass renderPass) {
        return (blendState == this.blendState_i) && (depthState == this.depthState_i)
               && renderPass == this.renderPass && logicOp == this.logicOp_i
               && (assemblyRasterState == this.assemblyRasterState)
               && colorMask == this.colorMask_i;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        PipelineState that = (PipelineState) o;
        return (blendState_i == that.blendState_i) && (depthState_i == that.depthState_i)
               && this.renderPass == that.renderPass && logicOp_i == that.logicOp_i
               && this.assemblyRasterState == that.assemblyRasterState
               && this.colorMask_i == that.colorMask_i;
    }

    @Override
    public int hashCode() {
        return Objects.hash(blendState_i, depthState_i, logicOp_i, assemblyRasterState, colorMask_i, renderPass);
    }

    public static BlendInfo defaultBlendInfo() {
        return new BlendInfo(true, VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
                             VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO, VK_BLEND_OP_ADD);
    }

    public static class BlendInfo {
        public boolean enabled;
        public int srcRgbFactor;
        public int dstRgbFactor;
        public int srcAlphaFactor;
        public int dstAlphaFactor;
        public int blendOp;

        public BlendInfo(boolean enabled, int srcRgbFactor, int dstRgbFactor, int srcAlphaFactor, int dstAlphaFactor,
                         int blendOp) {
            this.enabled = enabled;
            this.srcRgbFactor = srcRgbFactor;
            this.dstRgbFactor = dstRgbFactor;
            this.srcAlphaFactor = srcAlphaFactor;
            this.dstAlphaFactor = dstAlphaFactor;
            this.blendOp = blendOp;
        }

        public void setBlendFunction(GlStateManager.SourceFactor sourceFactor, GlStateManager.DestFactor destFactor) {
            this.srcRgbFactor = glToVulkanBlendFactor(sourceFactor.value);
            this.srcAlphaFactor = glToVulkanBlendFactor(sourceFactor.value);
            this.dstRgbFactor = glToVulkanBlendFactor(destFactor.value);
            this.dstAlphaFactor = glToVulkanBlendFactor(destFactor.value);
        }

        public void setBlendFuncSeparate(GlStateManager.SourceFactor srcRgb, GlStateManager.DestFactor dstRgb,
                                         GlStateManager.SourceFactor srcAlpha, GlStateManager.DestFactor dstAlpha) {
            this.srcRgbFactor = glToVulkanBlendFactor(srcRgb.value);
            this.srcAlphaFactor = glToVulkanBlendFactor(srcAlpha.value);
            this.dstRgbFactor = glToVulkanBlendFactor(dstRgb.value);
            this.dstAlphaFactor = glToVulkanBlendFactor(dstAlpha.value);
        }

        /* gl to Vulkan conversion */
        public void setBlendFunction(int sourceFactor, int destFactor) {
            this.srcRgbFactor = glToVulkanBlendFactor(sourceFactor);
            this.srcAlphaFactor = glToVulkanBlendFactor(sourceFactor);
            this.dstRgbFactor = glToVulkanBlendFactor(destFactor);
            this.dstAlphaFactor = glToVulkanBlendFactor(destFactor);
        }

        /* gl to Vulkan conversion */
        public void setBlendFuncSeparate(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
            this.srcRgbFactor = glToVulkanBlendFactor(srcRgb);
            this.srcAlphaFactor = glToVulkanBlendFactor(srcAlpha);
            this.dstRgbFactor = glToVulkanBlendFactor(dstRgb);
            this.dstAlphaFactor = glToVulkanBlendFactor(dstAlpha);
        }

        public void setBlendOp(int i) {
            this.blendOp = glToVulkanBlendOp(i);
        }


        public int createBlendState() {
            return BlendState.getState(this);
        }

        private static int glToVulkanBlendOp(int value) {
            return switch (value) {
                case 0x8006 -> VK_BLEND_OP_ADD;
                case 0x8007 -> VK_BLEND_OP_MIN;
                case 0x8008 -> VK_BLEND_OP_MAX;
                case 0x800A -> VK_BLEND_OP_SUBTRACT;
                case 0x800B -> VK_BLEND_OP_REVERSE_SUBTRACT;
                // Iroh (P1-2): total; an unknown value logs once and falls back to GL's default (FUNC_ADD)
                default -> unknown("blend equation", value, VK_BLEND_OP_ADD);
            };
        }

        private static int glToVulkanBlendFactor(int value) {
            return switch (value) {
                case 1 -> VK_BLEND_FACTOR_ONE;
                case 0 -> VK_BLEND_FACTOR_ZERO;
                case 771 -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
                case 770 -> VK_BLEND_FACTOR_SRC_ALPHA;
                case 775 -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
                case 769 -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
                case 774 -> VK_BLEND_FACTOR_DST_COLOR;
                case 768 -> VK_BLEND_FACTOR_SRC_COLOR;
                // Iroh (P1-2): the rest of GlStateManager.SourceFactor / DestFactor
                case 772 -> VK_BLEND_FACTOR_DST_ALPHA;
                case 773 -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
                case 776 -> VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
                case 32769 -> VK_BLEND_FACTOR_CONSTANT_COLOR;
                case 32770 -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
                case 32771 -> VK_BLEND_FACTOR_CONSTANT_ALPHA;
                case 32772 -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA;
                default -> unknown("blend factor", value, VK_BLEND_FACTOR_ONE);
            };
        }
    }

    /** Iroh (P1-2): a GL value Iroh cannot map logs once and falls back instead of throwing mid-frame. */
    static int unknown(String what, int glValue, int fallback) {
        if (LOGGED_UNKNOWN.add(what + glValue))
            net.vulkanmod.Initializer.LOGGER.warn("Iroh: unknown GL {} {} (0x{}); using the default", what, glValue, Integer.toHexString(glValue));
        return fallback;
    }

    public static class BlendState {
        public static final int SRC_RGB_OFFSET = 0;
        public static final int DST_RGB_OFFSET = 5;
        public static final int SRC_A_OFFSET = 10;
        public static final int DST_A_OFFSET = 15;
        public static final int FUN_OFFSET = 20;

        /** Iroh (rc2): factors 5 bits (VkBlendFactor 0..18), op 4 bits (VkBlendOp 0..4) at 20..23, enable at 24. */
        public static final int ENABLE_BIT = 1 << 24;

        public static final int OP_MASK = 0xF;
        public static final int FACTOR_MASK = 0x1F;

        public static int getState(BlendInfo blendInfo) {
            int s = 0;
            s |= blendInfo.enabled ? ENABLE_BIT : 0;
            s |= encode(blendInfo.srcRgbFactor, SRC_RGB_OFFSET, FACTOR_MASK);
            s |= encode(blendInfo.dstRgbFactor, DST_RGB_OFFSET, FACTOR_MASK);
            s |= encode(blendInfo.srcAlphaFactor, SRC_A_OFFSET, FACTOR_MASK);
            s |= encode(blendInfo.dstAlphaFactor, DST_A_OFFSET, FACTOR_MASK);
            s |= encode(blendInfo.blendOp, FUN_OFFSET, OP_MASK);

            return s;
        }

        public static boolean enable(int i) {
            return (i & ENABLE_BIT) != 0;
        }

        public static int encode(int i, int offset, int mask) {
            return (i & mask) << offset;
        }

        public static int decode(int i, int offset, int bits) {
            return (i >>> offset) & bits;
        }

        public static int getSrcRgbFactor(int s) {
            return decode(s, SRC_RGB_OFFSET, FACTOR_MASK);
        }

        public static int getDstRgbFactor(int s) {
            return decode(s, DST_RGB_OFFSET, FACTOR_MASK);
        }

        public static int getSrcAlphaFactor(int s) {
            return decode(s, SRC_A_OFFSET, FACTOR_MASK);
        }

        public static int getDstAlphaFactor(int s) {
            return decode(s, DST_A_OFFSET, FACTOR_MASK);
        }

        public static int blendOp(int state) {
            return decode(state, FUN_OFFSET, OP_MASK);
        }

    }

    public abstract static class LogicOpState {
        public static final int ENABLE_BIT = 1;

        public static final int FUN_OFFSET = 1;
        public static final int FUN_BITS = 5;

        public static boolean enable(int i) {
            return (i & ENABLE_BIT) != 0;
        }

        public static int encodeLogicOpFun(int glFun) {
            int fun = glToVulkan(glFun);

            return fun << FUN_OFFSET;
        }

        public static final int FUN_MASK = (1 << FUN_BITS) - 1;

        public static int decodeFun(int state) {
            return (state >>> FUN_OFFSET) & FUN_MASK;
        }

        public static int glToVulkan(int f) {
            // Iroh (P1-2): GL_CLEAR (0x1500) .. GL_SET (0x150F) are in the same order as VkLogicOp 0..15.
            // The base mapped only OR_REVERSE and turned every other op into AND.
            if (f >= 0x1500 && f <= 0x150F)
                return f - 0x1500;
            if (f == 0) // VRenderSystem.logicOpFun before any glLogicOp (logic op disabled then)
                return VK_LOGIC_OP_COPY;
            return unknown("logic op", f, VK_LOGIC_OP_COPY);
        }

    }

    /**
     * Iroh (rc2, td-42f410): bits 0-2 polygon mode, 3-6 topology, 7-8 cull mode. Every mask is derived from its width,
     * and encode() masks each input, so one field can never leak into the next. The base declared TOPOLOGY_MASK with
     * 5 bits for a 4-bit field: the low bit of the cull mode (VK_CULL_MODE_FRONT_BIT, FRONT_AND_BACK) became topology
     * bit 4 (TRIANGLE_LIST 3 -> 19), an invalid topology on every culled pipeline once RQ-16 made FRONT the normal
     * cull value. Round trip over every value: ./gradlew pipelineStateTest.
     */
    public abstract static class AssemblyRasterState {
        public static final int POLYGON_MODE_OFFSET = 0;
        public static final int POLYGON_MODE_BITS = 3;
        public static final int POLYGON_MODE_MASK = (1 << POLYGON_MODE_BITS) - 1;

        public static final int TOPOLOGY_OFFSET = POLYGON_MODE_OFFSET + POLYGON_MODE_BITS;
        public static final int TOPOLOGY_BITS = 4;
        public static final int TOPOLOGY_MASK = (1 << TOPOLOGY_BITS) - 1;

        public static final int CULL_MODE_OFFSET = TOPOLOGY_OFFSET + TOPOLOGY_BITS;
        public static final int CULL_MODE_BITS = 2;
        public static final int CULL_MODE_MASK = (1 << CULL_MODE_BITS) - 1;

        public static int encode(boolean cull, int topology, int polygonMode) {
            return encode(cull ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE, topology, polygonMode);
        }

        public static int encode(int cullMode, int topology, int polygonMode) {
            return ((polygonMode & POLYGON_MODE_MASK) << POLYGON_MODE_OFFSET)
                   | ((topology & TOPOLOGY_MASK) << TOPOLOGY_OFFSET)
                   | ((cullMode & CULL_MODE_MASK) << CULL_MODE_OFFSET);
        }

        public static int withCullMode(int state, int cullMode) {
            return (state & ~(CULL_MODE_MASK << CULL_MODE_OFFSET)) | ((cullMode & CULL_MODE_MASK) << CULL_MODE_OFFSET);
        }

        public static int decodeTopology(int state) {
            return (state >>> TOPOLOGY_OFFSET) & TOPOLOGY_MASK;
        }

        public static int decodePolygonMode(int state) {
            return (state >>> POLYGON_MODE_OFFSET) & POLYGON_MODE_MASK;
        }

        public static int decodeCullMode(int state) {
            return (state >>> CULL_MODE_OFFSET) & CULL_MODE_MASK;
        }
    }

    public static abstract class ColorMask {

        public static int getColorMask(boolean r, boolean g, boolean b, boolean a) {
            return (r ? VK_COLOR_COMPONENT_R_BIT : 0)
                   | (g ? VK_COLOR_COMPONENT_G_BIT : 0)
                   | (b ? VK_COLOR_COMPONENT_B_BIT : 0)
                   | (a ? VK_COLOR_COMPONENT_A_BIT : 0);
        }

    }

    public static abstract class DepthState {
        public static final int DEPTH_TEST_BIT = 1;
        public static final int DEPTH_MASK_BIT = 2;

        public static final int DEPTH_FUN_OFFSET = 2;
        public static final int DEPTH_FUN_BITS = 4;

        public static boolean depthTest(int i) {
            return (i & DEPTH_TEST_BIT) != 0;
        }

        public static boolean depthMask(int i) {
            return (i & DEPTH_MASK_BIT) != 0;
        }

        public static int encodeDepthFun(int glFun) {
            int fun = glToVulkan(glFun);

            return fun << DEPTH_FUN_OFFSET;
        }

        public static final int DEPTH_FUN_MASK = (1 << DEPTH_FUN_BITS) - 1;

        public static int decodeDepthFun(int state) {
            return (state >>> DEPTH_FUN_OFFSET) & DEPTH_FUN_MASK;
        }

        private static int glToVulkan(int value) {
            return switch (value) {
                case 512 -> VK_COMPARE_OP_NEVER;
                case 513 -> VK_COMPARE_OP_LESS;
                case 514 -> VK_COMPARE_OP_EQUAL;
                case 515 -> VK_COMPARE_OP_LESS_OR_EQUAL;
                case 516 -> VK_COMPARE_OP_GREATER;
                case 517 -> VK_COMPARE_OP_NOT_EQUAL;
                case 518 -> VK_COMPARE_OP_GREATER_OR_EQUAL;
                case 519 -> VK_COMPARE_OP_ALWAYS;
                // Iroh (P1-2): was "unknown blend factor.." thrown mid-frame (ImmediatelyFast HUD batching crash,
                // P1-2b blocker D); now logs once and uses Minecraft's default depth func (GL_LEQUAL).
                default -> unknown("depth func", value, VK_COMPARE_OP_LESS_OR_EQUAL);
            };
        }

    }
}
