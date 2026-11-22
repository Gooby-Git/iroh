package dev.elemore.iroh.apitest;

import dev.elemore.iroh.api.*;
import dev.elemore.iroh.gpu.GpuCore;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.BiConsumer;

import static dev.elemore.iroh.apitest.ApiV4Checks.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * api 4.1 (card V-M3) on the recording device: sampled textures in compute and draws (targets, static textures, Iroh's
 * own textures as externals with offline stand-ins), the UNDER blend, full-screen draws, the main-target tokens and their
 * settle rule, runOutsideMainPass, copies, and per-owner GPU timestamps.
 */
final class ApiV41Checks {
    private ApiV41Checks() {}

    static final String CS_TEX = "#version 450\nlayout(local_size_x=1) in;\nlayout(std430,binding=0) buffer B{float b[];};\n"
            + "layout(binding=1) uniform sampler2D t;\nvoid main(){ b[0] = texelFetch(t, ivec2(0), 0).r; }\n";
    static final String VS_FULL = "#version 450\nlayout(location=0) out vec2 uv;\nvoid main(){ vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);"
            + " uv = p; gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }\n";
    static final String FS_TEX = "#version 450\nlayout(location=0) in vec2 uv;\nlayout(location=0) out vec4 o;\nlayout(binding=0) uniform sampler2D a;\n"
            + "void main(){ o = texture(a, uv); gl_FragDepth = 0.5; }\n";

    static void run(BiConsumer<String, Boolean> check) {
        Rec rec = new Rec();
        GpuCore core = new GpuCore(rec, 0.40);
        core.resize(64, 32);

        // ---- textures in compute
        ComputePipelineHandle cp = core.computePipeline("vista", "texcs", CS_TEX, 1, 1, 0);
        check.accept("v41 compute pipeline declares 1 texture", rec.log.stream().anyMatch(l -> l.startsWith("compute texcs") && l.endsWith("tex 1")));
        GpuBuffer out = core.createBuffer("vista", "out", 16, GpuCore.STORAGE);
        RenderTargetHandle color = core.createTarget("vista", "color", IrohTargets.Format.RGBA8, 0, 0, 1);
        RenderTargetHandle depth = core.createTarget("vista", "depth", IrohTargets.Format.DEPTH32F, 0, 0, 1);
        IrohCompute.Binding[] wb = {IrohCompute.Binding.write(out)};
        check.accept("v41 dispatch with wrong texture count refused", throwsIae(() -> core.dispatch(cp, wb, new GpuTexture[0], null, 1, 1, 1)));
        // a target rendered then sampled by compute: one barrier before the dispatch
        core.beginRendering(color, depth, true, null, 0f);
        core.endRendering();
        int before = rec.log.size();
        core.dispatch(cp, wb, new GpuTexture[]{color}, null, 1, 1, 1);
        check.accept("v41 rendered target sampled by compute: barrier first",
                rec.log.subList(before, rec.log.size()).stream().anyMatch(l -> l.startsWith("barrier")) && rec.log.get(rec.log.size() - 1).startsWith("dispatch"));
        before = rec.log.size();
        core.dispatch(cp, wb, new GpuTexture[]{color}, null, 1, 1, 1);
        check.accept("v41 sampled again: no second target barrier",
                rec.log.subList(before, rec.log.size()).stream().filter(l -> l.startsWith("barrier")).count() <= 1); // only the WAW on out

        // ---- externals and stand-ins
        GpuTexture atlas = core.externalTexture(GpuCore.BLOCK_ATLAS);
        check.accept("v41 external is shared and owned by iroh", atlas == core.externalTexture(GpuCore.BLOCK_ATLAS) && atlas.owner().equals("iroh"));
        core.dispatch(cp, wb, new GpuTexture[]{atlas}, null, 1, 1, 1);
        check.accept("v41 external passed to the device as its key", rec.log.get(rec.log.size() - 1).endsWith("tex ext:iroh:block_atlas"));
        ByteBuffer px = MemoryUtil.memAlloc(4 * 4 * 4 + 2 * 2 * 4 + 4);
        GpuTexture stand = core.createTexture("test", "atlas stand-in", 4, 4, 3, px, false);
        check.accept("v41 texture accounted (4x4 + 2x2 + 1x1 texels)", core.ownerBytes("test") == (16 + 4 + 1) * 4);
        core.standIn(GpuCore.BLOCK_ATLAS, stand);
        check.accept("v41 stand-in registered", rec.log.get(rec.log.size() - 1).startsWith("standIn iroh:block_atlas tex"));
        check.accept("v41 stand-in must be a real texture", throwsIae(() -> core.standIn(GpuCore.LIGHTMAP, atlas)));
        check.accept("v41 too many mip levels refused", throwsIae(() -> core.createTexture("test", "x", 4, 4, 4, px, false)));
        MemoryUtil.memFree(px);

        // ---- blend modes, full-screen draws
        IndirectPipelineHandle comp = core.indirectPipeline("vista", "composite", VS_FULL, FS_TEX, 0, 1, 0, IrohTargets.Format.RGBA8,
                IrohTargets.Format.DEPTH32F, VK_COMPARE_OP_ALWAYS, true, IrohTargets.Blend.UNDER);
        check.accept("v41 UNDER blend reaches the device as 2", rec.log.stream().anyMatch(l -> l.equals("draw pipeline composite blend 2")));
        RenderTargetHandle other = core.createTarget("vista", "other", IrohTargets.Format.RGBA8, 0, 0, 1);
        check.accept("v41 draw outside a pass refused", throwsIse(() -> core.draw(comp, new IrohCompute.Binding[0], new GpuTexture[]{other}, null, 3, 1)));
        core.beginRendering(color, depth, false, null, 0f);
        check.accept("v41 sampling the pass's own attachment refused", throwsIae(() -> core.draw(comp, new IrohCompute.Binding[0], new GpuTexture[]{color}, null, 3, 1)));
        core.draw(comp, new IrohCompute.Binding[0], new GpuTexture[]{other}, null, 3, 1);
        check.accept("v41 full-screen draw recorded", rec.log.get(rec.log.size() - 1).startsWith("draw 3x1 tex"));
        core.endRendering();

        // ---- main target tokens and the settle rule
        RenderTargetHandle mc = core.mainColor(), md = core.mainDepth();
        check.accept("v41 main tokens report the window size", mc.width() == 64 && md.height() == 32 && md.format() == IrohTargets.Format.DEPTH32F);
        check.accept("v41 main tokens are not samplable", throwsIae(() -> core.dispatch(cp, wb, new GpuTexture[]{mc}, null, 1, 1, 1)));
        check.accept("v41 main is never cleared by a mod", throwsIae(() -> core.beginRendering(mc, md, true, null, 1f)));
        check.accept("v41 main needs both tokens", throwsIae(() -> core.beginRendering(mc, depth, false, null, 1f)));
        // `color` was rendered (written) and not settled: a main pass sampling it must refuse
        core.beginRendering(color, depth, false, null, 0f);
        core.endRendering();
        check.accept("v41 main pass after an unsettled target write refused", throwsIse(() -> core.beginRendering(mc, md, false, null, 1f)));
        // a buffer written and not settled cannot be read by the main pass
        rec.mainBound = true;
        core.runOutsideMainPass(() -> core.dispatch(cp, wb, new GpuTexture[]{other}, null, 1, 1, 1));
        check.accept("v41 runOutsideMainPass suspends, works, settles, resumes",
                rec.log.lastIndexOf("suspend") < rec.log.lastIndexOf("resume") && rec.log.get(rec.log.size() - 2).startsWith("barrier"));
        check.accept("v41 compute outside runOutsideMainPass while main is bound refused", throwsIse(() -> core.dispatch(cp, wb, null == null ? new GpuTexture[]{other} : null, null, 1, 1, 1)));
        core.beginRendering(mc, md, false, null, 1f, out);
        check.accept("v41 main pass joined (no begin of a new pass)", rec.log.get(rec.log.size() - 1).equals("beginMain"));
        core.draw(comp, new IrohCompute.Binding[0], new GpuTexture[]{color}, null, 3, 1);
        core.endRendering();
        check.accept("v41 main pass left", rec.log.get(rec.log.size() - 1).equals("endMain"));
        rec.mainBound = false;
        rec.mainDepth = VK_FORMAT_D24_UNORM_S8_UINT;
        check.accept("v41 main pass refused when the main depth is not D32F", throwsUoe(() -> core.beginRendering(mc, md, false, null, 1f)));
        rec.mainDepth = VK_FORMAT_D32_SFLOAT;

        // ---- copies
        RenderTargetHandle depth2 = core.createTarget("vista", "depth2", IrohTargets.Format.DEPTH32F, 0, 0, 1);
        core.copyTarget(depth, depth2);
        check.accept("v41 copy recorded", rec.log.get(rec.log.size() - 1).startsWith("copy"));
        check.accept("v41 copy of different formats refused", throwsIae(() -> core.copyTarget(color, depth2)));
        core.mainDepthCopy(depth2);
        check.accept("v41 mainDepthCopy goes to the device", rec.log.get(rec.log.size() - 1).startsWith("mainDepth -> "));
        check.accept("v41 mainDepthCopy into a colour target refused", throwsIae(() -> core.mainDepthCopy(color)));

        // ---- timestamps
        core.frameStart();
        core.profileBegin("vista", "mesh");
        core.dispatch(cp, wb, new GpuTexture[]{other}, null, 1, 1, 1);
        core.profileEnd("vista");
        core.profileBegin("vista", "cull");
        core.profileEnd("vista");
        check.accept("v41 timestamps: one reset, four writes", rec.count("tsReset") == 1 && rec.count("ts ") == 4);
        core.frameStart(); // same slot (0): the recording device says query i = i ms
        check.accept("v41 per-owner pass times", Math.abs(core.profileMs("vista", "mesh") - 1.0) < 1e-9
                && Math.abs(core.profileMs("vista", "cull") - 1.0) < 1e-9);
        check.accept("v41 profile line names owner and pass", core.profileLine().contains("vista:mesh=1.000") && core.profileLine().contains("vista:cull=1.000"));
        rec.inPass = true;
        core.frameStart();
        core.profileBegin("vista", "late");
        rec.inPass = false;
        core.profileEnd("vista");
        check.accept("v41 no timestamps the frame the reset could not be recorded", core.profileMs("vista", "late") == 0 && rec.count("ts ") == 4);

        // ---- owner death covers textures and targets
        int n = core.freeOwner("test");
        check.accept("v41 texture freed with its owner", n == 1 && core.ownerBytes("test") == 0);
        core.freeAll();
        check.accept("v41 everything freed", core.liveResources() == 0);
    }

    static boolean throwsUoe(Runnable r) {
        try {
            r.run();
            return false;
        } catch (UnsupportedOperationException e) {
            return true;
        }
    }
}
