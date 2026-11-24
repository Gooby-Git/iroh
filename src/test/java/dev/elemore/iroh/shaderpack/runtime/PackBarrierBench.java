package dev.elemore.iroh.shaderpack.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B-3B: barrier cost of the pack frame on the Navidson world0 plan (lavapipe, CPU only, no validation layer). Counts the
 * barrier calls the executor makes at the {@link GpuBackend} interface per frame (one call = one vkCmdPipelineBarrier;
 * the backend's own layout transitions around imported images are not counted, they are the same before and after) and
 * the image barriers inside them, and reads the per-pass GPU timestamps HeadlessVkBackend takes in submit-and-wait
 * mode (lavapipe: RELATIVE costs only; a CPU rasteriser executes barriers almost for free, so the real-GPU effect of
 * tight masks needs a guarded in-game A/B). Cases: synthetic (supplied images, deferred start), post (imported scene
 * colour/depth copied in, flat clears, output presented), full (SyntheticFullScene geometry + shadow + post).
 * {@code ./gradlew packBarrierBench [-Pframes=N] [-Pw=W -Ph=H]}; one line per case "# barrierbench <case> ...".
 */
public final class PackBarrierBench {

    public static void main(String[] args) throws Exception {
        int frames = Integer.getInteger("bb.frames", 12), w = Integer.getInteger("bb.w", 320), h = Integer.getInteger("bb.h", 180);
        PackThreadRepro.load();
        var data = PackThreadRepro.data;
        var plan = PackThreadRepro.plan;
        String folder = PackThreadRepro.FOLDER;
        for (String kase : List.of("synthetic", "post", "full")) {
            HeadlessVkBackend gpu = new HeadlessVkBackend(false, 1L << 30, false, 1);
            int[] calls = new int[frames], images = new int[frames];
            int[] frameNo = {-1};
            GpuBackend counted = (GpuBackend) java.lang.reflect.Proxy.newProxyInstance(GpuBackend.class.getClassLoader(), new Class<?>[]{GpuBackend.class},
                    (proxy, m, a) -> {
                        if (m.getName().equals("barrier") && frameNo[0] >= 0) {
                            calls[frameNo[0]]++;
                            images[frameNo[0]] += a.length == 1 && a[0] != null ? barrierImages(a[0]) : 1;
                        }
                        try {
                            return m.invoke(gpu, a);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            FrameExecutor ex = new FrameExecutor(data, plan, folder, counted, FrameExecutor.cap(1L << 30));
            ex.tracing = false;
            PackFrameInputs in;
            SyntheticScene syn = new SyntheticScene(data, plan.frames.get(folder), plan.resources.get(folder), w, h);
            in = syn;
            if (kase.equals("full")) {
                in = new SyntheticFullScene(data, plan.frames.get(folder), plan.resources.get(folder), w, h);
            } else if (kase.equals("post")) {
                String c0 = "R8G8B8A8_UNORM";
                for (var im : plan.resources.get(folder).images)
                    if (im.name.equals("colortex0"))
                        c0 = im.vkFormat;
                int sc = PackFlightRepro.sceneImage(gpu, "scene colour", c0, w, h);
                int sd = PackFlightRepro.sceneImage(gpu, "scene depth", "D32_SFLOAT", w, h);
                ex.presentTo = PackFlightRepro.sceneImage(gpu, "scene colour (present)", "R8G8B8A8_UNORM", w, h);
                Map<String, Integer> copies = new LinkedHashMap<>();
                copies.put("colortex0", sc);
                copies.put("depthtex0", sd);
                copies.put("depthtex1", sd);
                copies.put("depthtex2", sd);
                Map<String, float[]> clears = new LinkedHashMap<>();
                clears.put("colortex6", new float[]{0, 0, 1, 0});
                clears.put("colortex4", new float[]{0, 1, 0, 0});
                in = new PackFrameInputs() {
                    public int width() { return syn.width(); }
                    public int height() { return syn.height(); }
                    public Map<String, double[]> uniforms() { return syn.uniforms(); }
                    public Set<String> suppliedImages() { return Set.of(); }
                    public float[] image(String n) { return null; }
                    public int[] extent(String n) { return null; }
                    public String startStage() { return "deferred"; }
                    public double frameSeconds() { return syn.frameSeconds(); }
                    public Map<String, Integer> copySources() { return copies; }
                    public Map<String, float[]> clearValues() { return clears; }
                };
            }
            long cpu = 0;
            List<String> errs = new ArrayList<>();
            try {
                for (int f = 0; f < frames; f++) {
                    if (f == 2) // the first two frames create images, pipelines and caches: not steady state
                        gpu.passTimes.clear();
                    frameNo[0] = f;
                    long t0 = System.nanoTime();
                    ex.frame(in);
                    if (f >= 2)
                        cpu += System.nanoTime() - t0;
                }
                frameNo[0] = -1;
            } catch (Throwable t) {
                errs.add(t.toString());
            }
            double gpuMs = 0;
            for (double[] v : gpu.passTimes.values())
                gpuMs += v[0] / Math.max(1, v[1]);
            int steady = frames - 2;
            System.out.println("# barrierbench " + kase + " " + w + "x" + h + " barrier calls/frame f1 " + calls[0] + " f2 " + calls[1]
                    + " steady " + calls[frames - 1] + " (image barriers " + images[frames - 1] + "), summed pass GPU ms/frame "
                    + String.format("%.3f", gpuMs) + " (lavapipe), record+execute ms/frame " + String.format("%.2f", cpu / 1e6 / steady)
                    + (errs.isEmpty() ? "" : " ERROR " + errs));
            if (Boolean.getBoolean("bb.describe"))
                describe(ex);
            ex.close();
            gpu.close();
        }
        System.exit(0);
    }

    /** the compiled schedule (B-3B builds; reflection so the bench also runs on the pre-B-3B executor) */
    static void describe(FrameExecutor ex) {
        try {
            Object sp = ex.getClass().getMethod("sync").invoke(ex);
            System.out.println("  " + sp.getClass().getField("lastCompile").get(sp));
            for (Object l : (java.util.List<?>) sp.getClass().getMethod("describe").invoke(sp))
                System.out.println("    " + l);
        } catch (ReflectiveOperationException e) {
            System.out.println("  (no compiled schedule in this build)");
        }
    }

    /** image barriers in one barrier call: the old global call counts 1; a {@code GpuBackend.Barrier} its image list (reflection: works on both) */
    static int barrierImages(Object b) {
        try {
            Object imgs = b.getClass().getMethod("images").invoke(b);
            return imgs instanceof int[] a ? Math.max(1, a.length) : 1;
        } catch (ReflectiveOperationException e) {
            return 1;
        }
    }
}
