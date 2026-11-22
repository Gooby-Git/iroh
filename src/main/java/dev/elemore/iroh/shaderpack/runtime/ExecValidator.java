package dev.elemore.iroh.shaderpack.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.elemore.iroh.shaderpack.graph.PackData;
import dev.elemore.iroh.shaderpack.graph.PassGraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks what the executor did on a {@link RecordingBackend} against the frame plan: every replayed fullscreen pass is
 * drawn once and in plan order; its attachments are the planned target halves; every planned colour-target read is
 * bound to the planned half; requested mip chains are generated before the pass; clear=true targets the inputs do not
 * supply are cleared; the device saw no synchronisation violation.
 */
public final class ExecValidator {

    private ExecValidator() {}

    public static List<String> check(PackData data, FrameExecutor ex, RecordingBackend rec, PackFrameInputs in) {
        List<String> errs = new ArrayList<>(rec.violations);
        errs.addAll(ex.problems);
        PassGraph.Frame f = ex.frame();
        int start = FrameExecutor.STAGES.indexOf(in.startStage());
        List<PassGraph.Pass> expected = new ArrayList<>();
        for (PassGraph.Pass p : f.passes)
            if (FrameExecutor.STAGES.indexOf(p.stage) >= start && p.kind.equals("fullscreen"))
                expected.add(p);
        if (expected.size() != rec.draws.size())
            errs.add("drew " + rec.draws.size() + " passes, the plan has " + expected.size() + " fullscreen passes from " + in.startStage());
        for (int i = 0; i < Math.min(expected.size(), rec.draws.size()); i++) {
            PassGraph.Pass p = expected.get(i);
            GpuBackend.Draw d = rec.draws.get(i);
            if (!p.id.equals(d.pass())) {
                errs.add("draw " + i + " is " + d.pass() + ", plan says " + p.id);
                continue;
            }
            for (int k = 0; k < p.writes.size(); k++) {
                Map<String, Object> w = p.writes.get(k);
                Integer want = key(ex, (String) w.get("target"), (String) w.get("half"));
                if (k >= d.colour().length || want == null || d.colour()[k] != want)
                    errs.add(p.id + ": attachment " + k + " is not " + w.get("target") + "/" + w.get("half"));
            }
            // reads -> bindings by sampler name
            PackData.Unit u = data.units.get(p.unit);
            Map<String, Integer> bindingOf = new HashMap<>();
            JsonObject r = u.reflection();
            for (String kk : new String[]{"samplers", "images"})
                if (r.has(kk))
                    for (JsonElement e : r.getAsJsonArray(kk))
                        bindingOf.put(e.getAsJsonObject().get("name").getAsString(), e.getAsJsonObject().get("binding").getAsInt());
            for (Map<String, Object> rd : p.reads) {
                if (!rd.containsKey("half"))
                    continue;
                Integer want = key(ex, (String) rd.get("resource"), (String) rd.get("half"));
                Integer b = bindingOf.get((String) rd.get("name"));
                GpuBackend.Bind got = b == null ? null : d.set0().get(b);
                if (want == null || got == null || got.image() != want)
                    errs.add(p.id + ": " + rd.get("name") + " not bound to " + rd.get("resource") + "/" + rd.get("half"));
            }
            for (String mip : p.mipmapsBefore) {
                String[] tp = mip.replace("(", "").replace(")", "").split(" ");
                Integer want = key(ex, tp[0], tp[1]);
                boolean found = false;
                for (int[] mc : rec.mipCalls)
                    if (mc[0] == (want == null ? -1 : want) && mc[1] == i)
                        found = true;
                if (!found)
                    errs.add(p.id + ": mip chain of " + mip + " not generated right before the pass");
            }
        }
        for (PassGraph.Target t : f.targets.values()) {
            if (!t.clear || in.suppliedImages().contains(t.name) || (t.writers.isEmpty() && t.readers.isEmpty()))
                continue;
            Integer h = key(ex, t.name, "main");
            if (h != null && !rec.cleared.contains(h))
                errs.add(t.name + " (clear=true) was not cleared");
        }
        return errs;
    }

    private static Integer key(FrameExecutor ex, String target, String half) {
        if (target.equals("output"))
            return ex.images.get("output");
        Integer h = ex.images.get(target + "#" + half);
        return h != null ? h : ex.images.get(target);
    }
}
