package dev.elemore.iroh.packtest;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline model of the frame command buffer's graphics bind point and the two dynamic states Iroh's GraphicsPipelines
 * may or may not declare (depth bias, line width), checked at every draw against the Vulkan rules (int-2b, td-0cc003):
 * <ul>
 *   <li>VUID-vkCmdDraw*-None-08608: "there must not have been any calls to dynamic state setting commands for any state
 *       specified statically in the VkPipeline object bound ... since that pipeline was bound";</li>
 *   <li>VUID-vkCmdDraw*-None-07833 (line width) / -07834 (depth bias): a state the bound pipeline declares dynamic must
 *       have been set and not invalidated since; binding a pipeline that makes the state STATIC invalidates it
 *       (spec "Pipeline Dynamic State").</li>
 * </ul>
 * Render pass begin/end does not touch either state or the bound pipeline. Viewport/scissor are dynamic in every Iroh
 * pipeline and not modelled.
 */
final class VkDynModel {
    static final int DB = 1, LW = 2;

    long bound;          // 0 = nothing bound in this command buffer
    int boundDynamic;    // states the bound pipeline declares dynamic
    int setSinceBind;    // states set since the last bind
    int valid;           // states with a defined value in the command buffer
    int binds, sets, lineSets, biasSets;
    float bias = Float.NaN, line = Float.NaN; // last values recorded
    final List<String> violations = new ArrayList<>();

    void newCommandBuffer() {
        bound = 0;
        boundDynamic = 0;
        setSinceBind = 0;
        valid = 0;
    }

    void bind(long pipeline, int dynamic) {
        binds++;
        bound = pipeline;
        boundDynamic = dynamic;
        setSinceBind = 0;
        valid &= dynamic; // a state the new pipeline makes static is invalidated for later pipelines
    }

    void setBias(float v) {
        biasSets++;
        bias = v;
        set(DB);
    }

    void setLine(float v) {
        lineSets++;
        line = v;
        set(LW);
    }

    void set(int state) {
        sets++;
        valid |= state;
        if (bound != 0)
            setSinceBind |= state;
    }

    /** a draw that expects {@code expected} to be the bound pipeline */
    void draw(String what, long expected) {
        if (bound == 0) {
            violations.add(what + ": draw with no pipeline bound");
            return;
        }
        if (expected != 0 && bound != expected)
            violations.add(what + ": draw runs with pipeline " + bound + ", the caller bound " + expected);
        int bad = setSinceBind & ~boundDynamic;
        if (bad != 0)
            violations.add(what + ": VUID-08608 " + names(bad) + " set since binding pipeline " + bound + " which makes it static");
        int missing = boundDynamic & ~valid;
        if (missing != 0)
            violations.add(what + ": VUID-07833/07834 " + names(missing) + " dynamic in pipeline " + bound + " but not set / invalidated");
    }

    static String names(int m) {
        return ((m & DB) != 0 ? "DEPTH_BIAS " : "") + ((m & LW) != 0 ? "LINE_WIDTH" : "");
    }
}
