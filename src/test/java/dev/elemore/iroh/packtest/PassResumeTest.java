package dev.elemore.iroh.packtest;

import dev.elemore.iroh.render.CmdState;
import dev.elemore.iroh.render.PassResume;
import dev.elemore.iroh.shaderpack.runtime.RecordingBackend;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * G1 + int-2b (td-0cc003): a recording inside endPassRunResume (pack frame, in-frame texture uploads, copies) may bind
 * its own pipeline and may sit between an Iroh bind and its draw. Afterwards Iroh's pipeline must be bound again and
 * exactly the dynamic states it declares set (depth bias for every GraphicsPipeline, line width only for line
 * pipelines), also when no pass was bound and when the recording throws. Every command goes into VkDynModel, which
 * checks each draw against VUID-vkCmdDraw*-None-08608 and -07833/-07834. The production classes (CmdState,
 * PassResume) run here; only Renderer's two-line bind (`if (handle != bound) cmdState.bind(...)`) is mirrored.
 * {@code ./gradlew passResumeTest}; last line {@code # passresume PASS n/n}.
 */
public final class PassResumeTest {
    static int pass, total;

    static void check(String what, boolean ok) {
        total++;
        if (ok) pass++;
        else System.out.println("FAIL " + what);
    }

    static final long GUI = 0x922, GUI2 = 0x925, LINES = 0x930, TERRAIN = 0x940, PACK = 0x999;
    static final int TRI = CmdState.DEPTH_BIAS, LINE = CmdState.ALL; // GraphicsPipeline.dynamicStates

    /** Iroh's renderer on the production CmdState, recording into the model */
    static final class Iroh implements PassResume.Hooks {
        final VkDynModel vk = new VkDynModel();
        final CmdState cmd = new CmdState(new CmdState.Backend() {
            public void bindPipeline(long h) { vk.bind(h, h == LINES ? VkDynModel.DB | VkDynModel.LW : VkDynModel.DB); }
            public void depthBias(float c, float s) { vk.setBias(c); }
            public void lineWidth(float w) { vk.setLine(w); }
        });
        final List<String> log = new ArrayList<>();
        boolean bound = true;
        float wantBias, wantLine = 1f;

        void frameBegin() { vk.newCommandBuffer(); cmd.forgetBinding(); cmd.newCommandBuffer(); wantBias = 0; wantLine = 1; }
        void bind(long p) { if (cmd.boundHandle() != p) cmd.bind(p, p == LINES ? LINE : TRI, null); } // Renderer.bindGraphicsPipeline
        void glBias(float c) { wantBias = c; cmd.setDepthBias(c, 0f); }
        void glLine(float w) { wantLine = w; cmd.setLineWidth(w); }
        void draw(String what, long p) {
            vk.draw(what, p);
            if (vk.bound == p && (vk.boundDynamic & VkDynModel.DB) != 0 && vk.bias != wantBias)
                vk.violations.add(what + ": depth bias " + vk.bias + " != wanted " + wantBias);
            if (vk.bound == p && (vk.boundDynamic & VkDynModel.LW) != 0 && vk.line != wantLine)
                vk.violations.add(what + ": line width " + vk.line + " != wanted " + wantLine);
        }
        void resume(Runnable record) { PassResume.run(this, cmd, record); }
        /** a pack frame: own pipeline with depth bias / line width static, own draw */
        Runnable foreignRecord() { return () -> { log.add("record"); vk.bind(PACK, 0); vk.draw("pack draw", PACK); }; }

        public boolean passBound() { return bound; }
        public void endPass() { log.add("end"); bound = false; }
        public void resumePass() { log.add("resume"); bound = true; }
        public void reapplyViewportAndScissor() { log.add("viewport"); }
    }

    /** int-2's behaviour (c469385: invalidate + eager depth bias AND line width after the resume), on the same model */
    static final class Int2 {
        final VkDynModel vk = new VkDynModel();
        long cache;
        void frameBegin() { vk.newCommandBuffer(); vk.setBias(0); vk.setLine(1); cache = 0; }
        void bind(long p, int dyn) { if (cache == p) return; vk.bind(p, dyn); cache = p; }
        void resume(Runnable record) { record.run(); cache = 0; vk.setBias(0); vk.setLine(1); }
    }

    public static void main(String[] args) {
        // 0. validator self-check: int-2's title-screen sequence is flagged exactly as in the in-game log
        Int2 old = new Int2();
        old.frameBegin();
        old.bind(GUI, VkDynModel.DB);
        old.resume(() -> {});
        old.vk.draw("int-2 title GUI draw", GUI);
        check("model flags int-2's title screen as VUID-08608 LINE_WIDTH " + old.vk.violations,
                old.vk.violations.size() == 1 && old.vk.violations.get(0).contains("08608 LINE_WIDTH"));

        // 1. title screen: in-frame upload break between the GUI bind and its draw (VTextureSelector.bindShaderTextures)
        for (boolean startBound : new boolean[]{true, false}) {
            Iroh t = new Iroh();
            t.bound = startBound;
            t.frameBegin();
            t.bind(GUI);
            t.draw("first GUI draw", GUI);
            t.bind(GUI2);
            int lineSets = t.vk.lineSets;
            t.resume(() -> t.log.add("record")); // copyBufferToImage + barriers, no pipeline
            t.draw("GUI draw after the upload break", GUI2);
            t.bind(GUI);
            t.draw("next GUI draw", GUI);
            String tag = startBound ? "pass bound: " : "no pass: ";
            check(tag + "title screen valid " + t.vk.violations, t.vk.violations.isEmpty());
            check(tag + "no vkCmdSetLineWidth while a GUI pipeline is bound", t.vk.lineSets == lineSets);
            check(tag + "order " + t.log, t.log.equals(startBound ? List.of("end", "record", "resume", "viewport") : List.of("record")));
            check(tag + "pass state restored", t.bound == startBound);
        }

        // 2. G1: a pack frame (depth bias static) between a terrain bind and its draw, polygon offset on
        {
            Iroh t = new Iroh();
            t.frameBegin();
            t.glBias(-2f);
            t.bind(TERRAIN);
            RecordingBackend rec = new RecordingBackend();
            t.resume(() -> { rec.calls.add("bind pack pipeline"); t.foreignRecord().run(); });
            t.draw("terrain draw after the pack frame (no bind of its own)", TERRAIN);
            check("G1 depth bias re-emitted for the dynamic-bias pipeline " + t.vk.violations, t.vk.violations.isEmpty() && t.vk.bias == -2f);
            check("G1 Iroh's pipeline bound again", t.cmd.boundHandle() == TERRAIN && t.vk.bound == TERRAIN);
        }
        // 3. foreign recording with nothing of Iroh's bound: the next bind sets what it declares
        {
            Iroh t = new Iroh();
            t.frameBegin();
            t.glBias(3f);
            t.resume(t.foreignRecord());
            check("nothing bound: no state emitted after the resume", t.cmd.stale() == CmdState.ALL && t.cmd.boundHandle() == 0);
            t.bind(LINES);
            t.draw("line draw after a foreign frame", LINES);
            t.bind(TERRAIN);
            t.draw("terrain draw after a foreign frame", TERRAIN);
            check("next binds set depth bias / line width " + t.vk.violations, t.vk.violations.isEmpty());
        }
        // 4. Vulkan rule 2: a line pipeline after a non-line pipeline (line width invalidated by the bind); rc14 missed it
        {
            Iroh t = new Iroh();
            t.frameBegin();
            t.glLine(2f);
            t.bind(LINES);
            t.draw("lines", LINES);
            t.bind(GUI);
            t.draw("gui", GUI);
            t.bind(LINES);
            t.draw("lines again", LINES);
            check("line width set again after a static-line-width bind " + t.vk.violations, t.vk.violations.isEmpty());
        }
        // 5. glLineWidth by a mod while a GUI pipeline is bound, then the GUI draw (rc14 emitted it at once: 08608)
        {
            Iroh t = new Iroh();
            t.frameBegin();
            t.bind(GUI);
            t.glLine(4f);
            t.draw("gui after glLineWidth", GUI);
            t.bind(LINES);
            t.draw("lines after glLineWidth", LINES);
            check("glLineWidth deferred to the next line pipeline " + t.vk.violations, t.vk.violations.isEmpty() && t.vk.line == 4f);
        }
        // 6. FrameSync.flushFrame / flushCmds: a new command buffer must not keep the old bind
        {
            Iroh t = new Iroh();
            t.frameBegin();
            t.bind(GUI);
            t.draw("before flush", GUI);
            t.frameBegin(); // Renderer.beginRenderPass(stack): forgetBinding + newCommandBuffer
            t.bind(GUI);
            t.draw("after flush", GUI);
            check("new command buffer rebinds " + t.vk.violations, t.vk.violations.isEmpty());
        }
        // 7. a throwing recording still resumes and restores
        {
            Iroh t = new Iroh();
            t.frameBegin();
            t.glBias(1f);
            t.bind(TERRAIN);
            try {
                t.resume(() -> { t.vk.bind(PACK, 0); throw new IllegalStateException("boom"); });
            } catch (IllegalStateException expected) {
            }
            t.draw("terrain after a throwing recording", TERRAIN);
            check("throwing recording: resumed + restored " + t.log + " " + t.vk.violations,
                    t.log.equals(List.of("end", "resume", "viewport")) && t.vk.violations.isEmpty());
        }
        // 8. everything off, no pass break: rc14's commands (2 sets at frame begin, one bind per pipeline change)
        {
            Iroh t = new Iroh();
            t.frameBegin();
            t.bind(GUI);
            t.draw("a", GUI);
            t.bind(GUI);
            t.bind(GUI2);
            t.draw("b", GUI2);
            check("rc14 command count: binds " + t.vk.binds + " sets " + t.vk.sets, t.vk.binds == 2 && t.vk.sets == 2 && t.vk.violations.isEmpty());
        }
        // 9. random sequences: no violation and wanted values at every draw
        Random r = new Random(0x0cc003);
        long[] pipes = {GUI, GUI2, LINES, TERRAIN};
        int randomBad = 0;
        String firstBad = null;
        for (int run = 0; run < 2000; run++) {
            Iroh t = new Iroh();
            t.frameBegin();
            long last = 0;
            for (int i = 0; i < 60; i++) {
                switch (r.nextInt(8)) {
                    case 0, 1 -> { last = pipes[r.nextInt(pipes.length)]; t.bind(last); }
                    case 2 -> t.glBias(r.nextInt(4));
                    case 3 -> t.glLine(1 + r.nextInt(3));
                    case 4 -> t.resume(r.nextBoolean() ? t.foreignRecord() : () -> {});
                    case 5 -> { t.frameBegin(); last = 0; }
                    default -> { if (last != 0) t.draw("random draw " + i, last); }
                }
            }
            if (!t.vk.violations.isEmpty()) {
                randomBad++;
                if (firstBad == null) firstBad = t.vk.violations.get(0);
            }
        }
        check("2000 random command sequences valid (first: " + firstBad + ")", randomBad == 0);

        System.out.println("# passresume " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }
}
