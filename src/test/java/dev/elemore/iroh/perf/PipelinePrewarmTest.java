package dev.elemore.iroh.perf;

import dev.elemore.iroh.perf.PrewarmList.Entry;
import dev.elemore.iroh.perf.PrewarmList.Header;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * P2-11 (L8), offline (no Vulkan, no game): the in-frame pipeline-creation counter and the pre-warm list.
 * Counter: a normal creation while a frame records counts as in-frame, outside a frame it does not, a pre-warm creation never does; a
 * pre-warm hit is counted separately. List: encode/decode round trip, rejected on a changed Iroh build / driver / pack fingerprint / format
 * version / truncation / corrupt entry, invalid names and the entry cap handled, fingerprint independent of order.
 * {@code ./gradlew pipelinePrewarmTest}; last line "# pipelinePrewarm PASS n/n".
 */
public final class PipelinePrewarmTest {
    private static int pass, fail;

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    static Entry e(String name, int a) {
        return new Entry(name, a, 2, 3, 4, 15, 37, 126, 2);
    }

    public static void main(String[] args) {
        // ---- counter
        PipelineStats.reset();
        boolean[] recording = {false};
        PipelineStats.RECORDING = () -> recording[0];
        PipelineStats.created(PipelineStats.Kind.GRAPHICS, 2_000_000L); // load time, no frame
        check("creation outside a recording frame: created 1, in-frame 0", PipelineStats.created() == 1 && PipelineStats.inFrame() == 0);
        recording[0] = true;
        PipelineStats.created(PipelineStats.Kind.GRAPHICS, 30_000_000L);
        PipelineStats.created(PipelineStats.Kind.COMPUTE, 1_000_000L);
        check("two creations while a frame records: in-frame 2, created 3", PipelineStats.inFrame() == 2 && PipelineStats.created() == 3);
        PipelineStats.createdPrewarm(25_000_000L);
        PipelineStats.createdPrewarm(25_000_000L);
        check("pre-warm creations while a frame records are NOT in-frame (in-frame 2, pre-warmed 2, created 3)",
                PipelineStats.inFrame() == 2 && PipelineStats.prewarmed() == 2 && PipelineStats.created() == 3);
        PipelineStats.prewarmHit();
        check("a bind that finds its pipeline ready is a hit, not a creation", PipelineStats.prewarmHits() == 1 && PipelineStats.created() == 3 && PipelineStats.inFrame() == 2);
        String line = PipelineStats.line();
        System.out.println("     " + line);
        check("memStats line carries the counts", line.contains("created 3") && line.contains("in recording frames 2") && line.contains("pre-warmed 2") && line.contains("hits at bind 1"));
        // a first-flight model: N states first seen in play. Without a list every one is an in-frame creation; with the list they were created ahead
        PipelineStats.reset();
        recording[0] = true;
        int states = 40;
        for (int i = 0; i < states; i++) PipelineStats.created(PipelineStats.Kind.GRAPHICS, 20_000_000L);
        long cold = PipelineStats.inFrame();
        PipelineStats.reset();
        recording[0] = false;
        for (int i = 0; i < states; i++) PipelineStats.createdPrewarm(20_000_000L); // worker, at load
        recording[0] = true;
        for (int i = 0; i < states; i++) PipelineStats.prewarmHit(); // first sight in flight: map hit
        check("40 first-flight states: " + cold + " in-frame creations without a list, " + PipelineStats.inFrame() + " with it", cold == states && PipelineStats.inFrame() == 0 && PipelineStats.prewarmHits() == states);
        PipelineStats.reset();

        // ---- list
        Header h = new Header("1.2.3", PrewarmList.driverId(0x1002, 0x7550, 0x12345, 0x401000, "00ff"), PrewarmList.fingerprint(List.of("a@1", "b@2", "rp:vanilla")));
        Set<Entry> in = new LinkedHashSet<>(List.of(e("rendertype_solid", 1), e("rendertype_solid", 2), e("position_tex", 1), e("terrain", 9)));
        byte[] bytes = PrewarmList.encode(h, in);
        var back = PrewarmList.decode(bytes, h);
        check("round trip: accepted, same entries in order", back.accepted() && back.entries().equals(List.copyOf(in)));
        check("rejected on another Iroh build", !PrewarmList.decode(bytes, new Header("1.2.4", h.driver(), h.pack())).accepted());
        check("rejected on another driver (driver version bump)", !PrewarmList.decode(bytes, new Header(h.build(), PrewarmList.driverId(0x1002, 0x7550, 0x12346, 0x401000, "00ff"), h.pack())).accepted());
        check("rejected on another pipeline-cache UUID", !PrewarmList.decode(bytes, new Header(h.build(), PrewarmList.driverId(0x1002, 0x7550, 0x12345, 0x401000, "00fe"), h.pack())).accepted());
        var mismatch = PrewarmList.decode(bytes, new Header(h.build(), h.driver(), PrewarmList.fingerprint(List.of("a@1", "b@3", "rp:vanilla"))));
        check("rejected when a mod version changed (pack fingerprint): " + mismatch.reason(), !mismatch.accepted() && mismatch.entries().isEmpty());
        check("fingerprint ignores order", PrewarmList.fingerprint(List.of("x", "y", "z")).equals(PrewarmList.fingerprint(List.of("z", "x", "y"))));
        check("fingerprint changes with a resource pack added", !PrewarmList.fingerprint(List.of("x")).equals(PrewarmList.fingerprint(List.of("x", "rp:file/pack.zip"))));
        String text = new String(bytes, StandardCharsets.UTF_8);
        check("truncated file (no end line) rejected", !PrewarmList.decode(text.substring(0, text.length() - 5).getBytes(StandardCharsets.UTF_8), h).accepted());
        check("count larger than the entries rejected", !PrewarmList.decode(text.replace("count=4", "count=5").getBytes(StandardCharsets.UTF_8), h).accepted());
        check("count smaller than the entries rejected", !PrewarmList.decode(text.replace("count=4", "count=3").getBytes(StandardCharsets.UTF_8), h).accepted());
        check("non-numeric field rejected", !PrewarmList.decode(text.replaceFirst("\t2\t3\t4\t15", "\tX\t3\t4\t15").getBytes(StandardCharsets.UTF_8), h).accepted());
        check("another format version rejected", !PrewarmList.decode(text.replace("#iroh-prewarm 1", "#iroh-prewarm 2").getBytes(StandardCharsets.UTF_8), h).accepted());
        check("null / empty rejected without throwing", !PrewarmList.decode(null, h).accepted() && !PrewarmList.decode(new byte[0], h).accepted()
                && !PrewarmList.decode(new byte[]{1, 2, 3, (byte) 0xff}, h).accepted());
        check("duplicate entries in a file are folded", PrewarmList.decode(PrewarmList.encode(h, new LinkedHashSet<>(List.of(e("a", 1)))), h).entries().size() == 1);
        Set<Entry> odd = new LinkedHashSet<>(List.of(e("ok", 1), new Entry("has\ttab", 1, 1, 1, 1, 1, 1, 1, 0), new Entry("has\nnl", 1, 1, 1, 1, 1, 1, 1, 0), new Entry("", 1, 1, 1, 1, 1, 1, 1, 0)));
        var oddBack = PrewarmList.decode(PrewarmList.encode(h, odd), h);
        check("names that could break the format are dropped at encode, the rest survives", oddBack.accepted() && oddBack.entries().size() == 1 && oddBack.entries().get(0).pipeline().equals("ok"));
        Set<Entry> many = new LinkedHashSet<>();
        for (int i = 0; i < PrewarmList.MAX_ENTRIES + 50; i++) many.add(e("p" + i, i));
        var capped = PrewarmList.decode(PrewarmList.encode(h, many), h);
        check("entry cap " + PrewarmList.MAX_ENTRIES + " at encode (still accepted)", capped.accepted() && capped.entries().size() == PrewarmList.MAX_ENTRIES);
        check("over-size input rejected", !PrewarmList.decode(new byte[PrewarmList.MAX_BYTES + 1], h).accepted());
        boolean threw = false;
        try { PrewarmList.encode(new Header("", "d", "p"), in); } catch (IllegalArgumentException ex) { threw = true; }
        check("an empty header field cannot be written", threw);

        // ---- GPL scaffold: capability check + switch, never used while the path is not implemented
        dev.elemore.iroh.temporal.DeviceCaps.probeGpl(Set.of("VK_EXT_graphics_pipeline_library"));
        check("GPL needs its dependency VK_KHR_pipeline_library too", !dev.elemore.iroh.temporal.DeviceCaps.gplOffered);
        dev.elemore.iroh.temporal.DeviceCaps.probeGpl(Set.of("VK_EXT_graphics_pipeline_library", "VK_KHR_pipeline_library"));
        check("GPL offered when both extensions are listed", dev.elemore.iroh.temporal.DeviceCaps.gplOffered);
        PerfSwitches.registerAll();
        dev.elemore.iroh.debug.DebugSwitches.set(PerfSwitches.PIPELINE_GPL, "on");
        check("GPL never used while the path is not implemented, switch or not (" + dev.elemore.iroh.temporal.DeviceCaps.gplReport() + ")", !dev.elemore.iroh.temporal.DeviceCaps.gplUsable());

        System.out.println("# pipelinePrewarm " + (fail == 0 ? "PASS" : "FAIL") + " " + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
