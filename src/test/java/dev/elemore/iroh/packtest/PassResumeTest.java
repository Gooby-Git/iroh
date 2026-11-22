package dev.elemore.iroh.packtest;

import dev.elemore.iroh.render.PassResume;
import dev.elemore.iroh.shaderpack.runtime.RecordingBackend;

import java.util.ArrayList;
import java.util.List;

/**
 * G1 validation fix: a pack frame recorded inside endPassRunResume binds its own pipelines; afterwards Iroh's bound
 * pipeline cache must be dropped and every dynamic state set again, also when no pass was bound and when the recording
 * throws. The recording here is a real FrameExecutor-style recording on the RecordingBackend (createPipeline + draw).
 * {@code ./gradlew passResumeTest}; last line {@code # passresume PASS n/n}.
 */
public final class PassResumeTest {
    static int pass, total;

    static void check(String what, boolean ok) {
        total++;
        if (ok) pass++;
        else System.out.println("FAIL " + what);
    }

    static final class Fake implements PassResume.Hooks {
        final List<String> log = new ArrayList<>();
        boolean bound;
        long cachedPipeline = 42; // Iroh's bound-pipeline cache before the pack frame
        boolean biasValid = true, lineValid = true, viewportValid = true;

        public boolean passBound() { return bound; }
        public void endPass() { log.add("end"); bound = false; }
        public void resumePass() { log.add("resume"); bound = true; viewportValid = false; }
        public void invalidateBoundState() { log.add("invalidate"); cachedPipeline = 0; }
        public void reapplyDynamicState() { log.add("reapply"); biasValid = lineValid = viewportValid = true; }
    }

    public static void main(String[] args) {
        for (boolean startBound : new boolean[]{true, false}) {
            Fake f = new Fake();
            f.bound = startBound;
            RecordingBackend rec = new RecordingBackend();
            PassResume.run(f, () -> {
                f.log.add("record");
                // a foreign pipeline bind invalidates every dynamic state it does not declare
                rec.calls.add("bind pack pipeline");
                f.biasValid = f.lineValid = false;
            });
            String tag = startBound ? "bound: " : "unbound: ";
            check(tag + "order " + f.log, f.log.equals(startBound ? List.of("end", "record", "resume", "invalidate", "reapply")
                    : List.of("record", "invalidate", "reapply")));
            check(tag + "pipeline cache dropped", f.cachedPipeline == 0);
            check(tag + "depth bias set again", f.biasValid);
            check(tag + "line width set again", f.lineValid);
            check(tag + "viewport/scissor valid", f.viewportValid);
            check(tag + "pass state restored", f.bound == startBound);
        }
        Fake f = new Fake();
        f.bound = true;
        try {
            PassResume.run(f, () -> { f.biasValid = false; throw new IllegalStateException("boom"); });
        } catch (IllegalStateException expected) {
        }
        check("throwing recording still resumes + invalidates " + f.log, f.log.equals(List.of("end", "resume", "invalidate", "reapply")) && f.cachedPipeline == 0 && f.biasValid);
        System.out.println("# passresume " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }
}
