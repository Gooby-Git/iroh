package dev.elemore.iroh.shaderpack.graph;

import dev.elemore.iroh.shaderpack.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Upscaler cut-point analysis (docs/OUTLINE.md "P4 design constraint", tier 1 = a pack directive
 * {@code iroh.upscale.after=<program>}). Everything up to the cut runs at render resolution, everything after at
 * output resolution; the upscaler sits between. For each candidate cut (after the last geometry pass and after each
 * deferred/composite pass) the analysis lists what crosses it: colour targets read after the cut whose content was
 * produced before it (each must be upscaled or resampled), depth reads after the cut, and history targets (read and
 * written in one pass, never cleared: a temporal filter of the pack's own, which an upscaler replaces).
 */
public final class CutPoint {

    public record Candidate(String after, int index, Set<String> crossingColour, boolean depthAfter, Set<String> history,
                            String verdict) {}

    public final List<Candidate> candidates = new ArrayList<>();
    public String temporalPass;
    public String recommendation;
    public String spatialOnly;
    public final List<String> packMustDeclare = new ArrayList<>();

    public static CutPoint analyse(PassGraph.Frame f) {
        CutPoint c = new CutPoint();
        List<PassGraph.Pass> ps = f.passes;
        int lastGeometry = -1;
        for (int i = 0; i < ps.size(); i++)
            if (ps.get(i).kind.equals("geometry") && !ps.get(i).stage.equals("shadow"))
                lastGeometry = i;
        // the pack's temporal anti-aliasing pass: the LAST composite pass that samples and writes a never-cleared
        // target (its history). Earlier such passes (deferred reflections keeping last frame's scene) are history
        // readers too, but an upscaler replaces only the TAA
        for (PassGraph.Pass p : ps) {
            if (!p.kind.equals("fullscreen") || !p.stage.equals("composite"))
                continue;
            for (Map<String, Object> w : p.writes) {
                PassGraph.Target t = f.targets.get((String) w.get("target"));
                if (t != null && !t.clear && p.reads.stream().anyMatch(r -> t.name.equals(r.get("resource"))))
                    c.temporalPass = p.id;
            }
        }
        for (int i = Math.max(lastGeometry, 0); i < ps.size(); i++) {
            PassGraph.Pass cut = ps.get(i);
            if (i != lastGeometry && !cut.kind.equals("fullscreen"))
                continue;
            if (cut.id.equals("final"))
                continue;
            Set<String> written = new TreeSet<>();
            for (int k = 0; k <= i; k++)
                for (Map<String, Object> w : ps.get(k).writes)
                    written.add((String) w.get("target"));
            Set<String> crossing = new TreeSet<>();
            boolean depth = false;
            Set<String> history = new TreeSet<>();
            Set<String> producedAfter = new TreeSet<>();
            for (int k = i + 1; k < ps.size(); k++) {
                PassGraph.Pass p = ps.get(k);
                for (Map<String, Object> r : p.reads) {
                    Object res = r.get("resource");
                    if (r.get("kind").equals("depth"))
                        depth = true;
                    if (res instanceof String s && s.startsWith("colortex") && !producedAfter.contains(s)) {
                        if (written.contains(s))
                            crossing.add(s);
                        PassGraph.Target t = f.targets.get(s);
                        if (t != null && !t.clear && !written.contains(s))
                            history.add(s);
                    }
                }
                for (Map<String, Object> w : p.writes)
                    producedAfter.add((String) w.get("target"));
            }
            String verdict = crossing.size() == 1 && !depth ? "clean: one colour target crosses"
                    : crossing.isEmpty() ? "nothing crosses" : crossing.size() + " colour targets cross" + (depth ? ", depth read after" : "");
            c.candidates.add(new Candidate(cut.id, i, crossing, depth, history, verdict));
        }
        // recommendation: the last candidate before the pack's temporal pass (the upscaler replaces it), else the
        // first clean one, else the one with the fewest crossings
        Candidate best = null;
        if (c.temporalPass != null)
            for (Candidate x : c.candidates)
                if (x.index() < indexOf(ps, c.temporalPass))
                    best = x;
        if (best == null)
            for (Candidate x : c.candidates)
                if (x.verdict().startsWith("clean")) {
                    best = x;
                    break;
                }
        if (best == null)
            for (Candidate x : c.candidates)
                if (best == null || x.crossingColour().size() + (x.depthAfter() ? 1 : 0) < best.crossingColour().size() + (best.depthAfter() ? 1 : 0))
                    best = x;
        Candidate clean = null;
        for (Candidate x : c.candidates)
            if (x.verdict().startsWith("clean"))
                clean = x;
        if (clean != null && clean != best)
            c.spatialOnly = "a spatial-only upscaler (FSR 1 class) could cut after " + clean.after() + " (" + clean.verdict() + "), on the tone-mapped image";
        if (best != null) {
            c.recommendation = "iroh.upscale.after=" + best.after() + " (" + best.verdict() + ")";
            c.packMustDeclare.add("iroh.upscale.after=" + best.after());
            if (best.crossingColour().size() >= 1)
                c.packMustDeclare.add("iroh.upscale.input=" + best.crossingColour().iterator().next()
                        + (best.crossingColour().size() > 1 ? "  (and iroh.upscale.resample=" + String.join(",",
                        new ArrayList<>(best.crossingColour()).subList(1, best.crossingColour().size())) + ": the other crossing targets, bilinear)" : ""));
            if (c.temporalPass != null)
                c.packMustDeclare.add("iroh.upscale.replaces=" + c.temporalPass + "  (the pack's TAA is switched off while an upscaler runs: an option the pack maps, e.g. TAA_DEFINE)");
            c.packMustDeclare.add("iroh.upscale.jitter=<uniform the pack offsets its projection with> or let Iroh supply the jitter in gbufferProjection");
            c.packMustDeclare.add("motion vectors: from depth + gbufferPreviousModelView/Projection (Iroh), at_velocity for entities when the pack reads it");
            c.packMustDeclare.add("colour space of the input: HDR linear before tone mapping is what FSR 3 / DLSS want; after tone mapping works with the LDR flag");
        }
        return c;
    }

    private static int indexOf(List<PassGraph.Pass> ps, String id) {
        for (int i = 0; i < ps.size(); i++)
            if (ps.get(i).id.equals(id))
                return i;
        return Integer.MAX_VALUE;
    }

    public Object toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> l = new ArrayList<>();
        for (Candidate x : candidates)
            l.add(Json.obj("after", x.after(), "crossingColour", new ArrayList<>(x.crossingColour()), "depthReadAfter", x.depthAfter(),
                    "historyRead", new ArrayList<>(x.history()), "verdict", x.verdict()));
        m.put("candidates", l);
        m.put("packTemporalPass", temporalPass);
        m.put("recommendation", recommendation);
        m.put("spatialOnly", spatialOnly);
        m.put("packMustDeclare", packMustDeclare);
        return m;
    }

    public String toText() {
        StringBuilder sb = new StringBuilder("-- upscaler cut (render resolution ends after the named pass)\n");
        for (Candidate x : candidates)
            sb.append(String.format("  after %-14s crossing %-40s depth-after %-5s %s%n", x.after(), x.crossingColour(), x.depthAfter(), x.verdict()));
        sb.append("  pack temporal pass: ").append(temporalPass).append('\n');
        sb.append("  recommendation: ").append(recommendation).append('\n');
        if (spatialOnly != null)
            sb.append("  ").append(spatialOnly).append('\n');
        for (String s : packMustDeclare)
            sb.append("    ").append(s).append('\n');
        return sb.toString();
    }
}
