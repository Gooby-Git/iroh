package dev.elemore.iroh.shaderpack.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything P3-2 derives offline from one pack's P3-1 dump: the frame plan per folder ({@link PassGraph}), the
 * Vulkan resource plan with memory totals and the budget ladder ({@link ResourcePlan}), the uniform supply plan
 * ({@link UniformSupply}), geometry needs ({@link GeometryNeeds}) and the upscaler cut analysis ({@link CutPoint}).
 */
public final class PackPlan {

    public final PackData data;
    public final Map<String, PassGraph.Frame> frames;
    public final Map<String, ResourcePlan> resources = new LinkedHashMap<>();
    public final Map<String, CutPoint> cuts = new LinkedHashMap<>();
    public UniformSupply.Report uniforms;
    public GeometryNeeds geometry;

    private PackPlan(PackData data, Map<String, PassGraph.Frame> frames) {
        this.data = data;
        this.frames = frames;
    }

    public static PackPlan build(PackData d) {
        PackPlan p = new PackPlan(d, PassGraph.build(d));
        for (Map.Entry<String, PassGraph.Frame> e : p.frames.entrySet()) {
            p.resources.put(e.getKey(), ResourcePlan.build(d, e.getValue()));
            p.cuts.put(e.getKey(), CutPoint.analyse(e.getValue()));
        }
        p.uniforms = UniformSupply.report(d);
        p.geometry = GeometryNeeds.build(d, p.frames);
        return p;
    }

    public int errors() {
        int n = 0;
        for (PassGraph.Frame f : frames.values())
            n += (int) f.count(PassGraph.Severity.ERROR);
        return n;
    }

    public List<String> errorLines() {
        List<String> l = new ArrayList<>();
        frames.forEach((k, f) -> f.grouped(PassGraph.Severity.ERROR).forEach((msg, ps) -> l.add(k + " " + String.join(", ", ps) + ": " + msg)));
        return l;
    }

    public List<String> summaryLines() {
        List<String> l = new ArrayList<>();
        frames.forEach((k, f) -> {
            ResourcePlan rp = resources.get(k);
            l.add(String.format("%s: passes %d  ERROR %d  WARN %d  INFO %d  images %d  MiB@1080p %.0f  @1440p %.0f  @4K %.0f  fits %s",
                    k, f.passes.size(), f.count(PassGraph.Severity.ERROR), f.count(PassGraph.Severity.WARN),
                    f.count(PassGraph.Severity.INFO), rp.images.size(), rp.mib(1920, 1080), rp.mib(2560, 1440), rp.mib(3840, 2160),
                    rp.budgetVerdict()));
        });
        return l;
    }

    public Object toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", "iroh.graph.v1");
        m.put("pack", data.name);
        Map<String, Object> folders = new LinkedHashMap<>();
        frames.forEach((k, f) -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("frame", PassGraph.toJson(f));
            x.put("resources", resources.get(k).toJson());
            x.put("upscalerCut", cuts.get(k).toJson());
            folders.put(k, x);
        });
        m.put("folders", folders);
        m.put("uniforms", uniforms.toJson());
        m.put("geometry", geometry.toJson());
        return m;
    }

    public String toText() {
        StringBuilder sb = new StringBuilder("# pass graph of " + data.name + " (iroh.graph.v1)\n");
        frames.forEach((k, f) -> {
            sb.append(PassGraph.toText(f));
            sb.append(resources.get(k).toText());
            sb.append(cuts.get(k).toText());
            sb.append('\n');
        });
        sb.append(uniforms.toText());
        sb.append(geometry.toText());
        return sb.toString();
    }
}
