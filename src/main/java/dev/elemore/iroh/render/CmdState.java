package dev.elemore.iroh.render;

/**
 * Iroh's view of the frame command buffer's graphics bind point: the bound pipeline and the two dynamic states whose
 * declaration differs between Iroh's pipelines (depth bias: every GraphicsPipeline; line width: line-list / polygon-mode
 * LINE pipelines only). Free of Vulkan so the rules are tested offline (passResumeTest with VkDynModel). int-2b,
 * td-0cc003: int-2 set line width eagerly after a pass resume while a GUI pipeline was bound between its bind and its
 * draw (VUID-vkCmdDrawIndexed-None-08608 on the title screen).
 *
 * <p>Vulkan rules (spec "Pipeline Dynamic State"; VUID-vkCmdDraw*-None-08608, -07833 line width, -07834 depth bias):
 * <ol>
 *   <li>no dynamic-state command for a state the BOUND pipeline makes static may be recorded between its bind and a
 *       draw with it;</li>
 *   <li>a draw with a pipeline that declares a state dynamic needs the state set and not invalidated since; binding a
 *       pipeline that makes the state static invalidates it.</li>
 * </ol>
 * So a wanted value is emitted at once only while nothing is bound or the bound pipeline declares the state dynamic;
 * otherwise the state is marked stale and emitted right after the next bind of a pipeline that declares it. A bind of a
 * pipeline that makes a state static marks that state stale. Anything that binds a graphics pipeline into the frame
 * command buffer outside Iroh's renderer calls {@link #foreign()} (or runs through {@link PassResume}, which also binds
 * Iroh's pipeline again: the recording may sit between an Iroh bind and its draw, e.g. VTextureSelector flushing
 * in-frame uploads after the bind).
 *
 * <p>Render thread only. Everything off, no pass break: exactly rc14's commands, except that a line pipeline bound after
 * a non-line pipeline gets its line width set again (rule 2; rc14 left it invalidated).
 */
public final class CmdState {
    public static final int DEPTH_BIAS = 1, LINE_WIDTH = 2, ALL = DEPTH_BIAS | LINE_WIDTH;

    /** records into the frame command buffer (only called while it records) */
    public interface Backend {
        void bindPipeline(long handle);

        void depthBias(float constant, float slope);

        void lineWidth(float width);
    }

    private final Backend vk;
    private long handle;        // Iroh pipeline bound in the command buffer; 0 = none or unknown (next bind is recorded)
    private Object owner;       // the Pipeline object of {@code handle}
    private int dynamic = ALL;  // dynamic states of what is bound: ALL = nothing bound, 0 = a foreign pipeline may be bound
    private int stale;          // states whose wanted value is not validly in the command buffer
    private float biasConstant, biasSlope, lineWidth = 1.0F;

    public CmdState(Backend vk) {
        this.vk = vk;
    }

    public long boundHandle() {
        return handle;
    }

    public Object boundOwner() {
        return owner;
    }

    public int boundDynamic() {
        return dynamic;
    }

    public int stale() {
        return stale;
    }

    /** a new command buffer began: nothing bound; frame-start values depth bias 0, line width 1 (as rc14) */
    public void newCommandBuffer() {
        handle = 0;
        owner = null;
        dynamic = ALL;
        stale = 0;
        setDepthBias(0.0F, 0.0F);
        setLineWidth(1.0F);
    }

    /** start of a frame (descriptor pools reset): forget the bound pipeline, the command buffer is new */
    public void forgetBinding() {
        handle = 0;
        owner = null;
        dynamic = ALL;
    }

    /** records the bind of {@code h} (the caller checked h != boundHandle()); {@code dyn} = states it declares */
    public void bind(long h, int dyn, Object pipeline) {
        vk.bindPipeline(h);
        handle = h;
        owner = pipeline;
        bound(dyn);
    }

    private void bound(int dyn) {
        stale |= ALL & ~dyn; // static in this pipeline: invalidated for the next one that declares it
        dynamic = dyn;
        int e = stale & dyn;
        if (e != 0)
            emit(e);
    }

    private void emit(int e) {
        if ((e & DEPTH_BIAS) != 0)
            vk.depthBias(biasConstant, biasSlope);
        if ((e & LINE_WIDTH) != 0)
            vk.lineWidth(lineWidth);
        stale &= ~e;
    }

    public void setDepthBias(float constant, float slope) {
        biasConstant = constant;
        biasSlope = slope;
        if ((dynamic & DEPTH_BIAS) != 0)
            emit(DEPTH_BIAS);
        else
            stale |= DEPTH_BIAS;
    }

    public void setLineWidth(float width) {
        lineWidth = width;
        if ((dynamic & LINE_WIDTH) != 0)
            emit(LINE_WIDTH);
        else
            stale |= LINE_WIDTH;
    }

    /** something else bound (or may have bound) a graphics pipeline: every state is undefined, the next bind records */
    public void foreign() {
        handle = 0;
        owner = null;
        dynamic = 0;
        stale = ALL;
    }

    /**
     * After a foreign recording (PassResume): bind Iroh's pipeline {@code h} again when one was bound before it (a draw
     * may follow without a bind of its own) and set the states it declares; with none, the next bind sets them.
     */
    public void restore(long h, int dyn, Object pipeline) {
        foreign();
        if (h != 0)
            bind(h, dyn, pipeline);
    }
}
