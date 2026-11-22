package dev.elemore.iroh.packtest;

import dev.elemore.iroh.shaderpack.FrontEnd;
import dev.elemore.iroh.shaderpack.Json;
import dev.elemore.iroh.shaderpack.PackSource;
import dev.elemore.iroh.shaderpack.StandardMacros;
import dev.elemore.iroh.shaderpack.graph.PackData;
import dev.elemore.iroh.shaderpack.graph.PackPlan;
import dev.elemore.iroh.shaderpack.runtime.FrameCapture;
import dev.elemore.iroh.shaderpack.runtime.FrameExecutor;
import dev.elemore.iroh.shaderpack.runtime.GpuBackend;
import dev.elemore.iroh.shaderpack.runtime.HeadlessVkBackend;
import dev.elemore.iroh.shaderpack.runtime.PackFrameInputs;
import dev.elemore.iroh.shaderpack.runtime.SyntheticScene;
import dev.elemore.iroh.shaderpack.runtime.SyntheticFullScene;
import dev.elemore.iroh.shaderpack.runtime.TexelCodec;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline frame replay (P3-3): Navidson's deferred + composite + final chain executed by {@link FrameExecutor} on a
 * headless CPU Vulkan device (lavapipe) against a captured or synthetic frame. No game, no GPU (the backend refuses
 * non-CPU devices; the Gradle task restricts the loader to the lavapipe ICD), device memory capped at 1 GiB.
 * <pre>
 * ./gradlew packReplay [-Ppack=dir] [-Pfolder=world0] [-Psize=320x180] [-Pcapture=dir.irohframe] [-Pframes=2]
 *                      [-Pdump] [-Pgolden=check|update]
 * </pre>
 * Output build/packReplay/&lt;pack&gt;/&lt;folder&gt;/: final.png, targets/*.png (final state; per pass with -Pdump),
 * stats.json (NaN / inf / min / max / mean per target), trace.txt, synthetic.irohframe. Last line
 * {@code # replay PASS|FAIL ...}: FAIL on any NaN/inf in a target, an all-black output, an executor problem, or a
 * golden mismatch.
 */
public final class PackReplay {

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new LinkedHashMap<>();
        for (String a : args) {
            int eq = a.indexOf('=');
            if (a.startsWith("--"))
                opt.put(eq > 0 ? a.substring(2, eq) : a.substring(2), eq > 0 ? a.substring(eq + 1) : "true");
        }
        Path pack = Paths.get(opt.getOrDefault("pack", System.getProperty("user.home") + "/.local/share/atlauncher/instances/Vulkan/shaderpacks/Navidson"));
        String folder = opt.getOrDefault("folder", "world0");
        String[] size = opt.getOrDefault("size", "320x180").split("x");
        int w = Integer.parseInt(size[0]), h = Integer.parseInt(size[1]);
        int frames = Integer.parseInt(opt.getOrDefault("frames", "2"));
        String name = pack.getFileName().toString();
        Path out = Paths.get("build/packReplay").resolve(PackHarness.safe(name)).resolve(folder.replace("<", "").replace(">", ""));
        PackHarness.deleteTree(out);
        Files.createDirectories(out.resolve("targets"));

        Path optFile = pack.resolveSibling(name + ".txt");
        String saved = Files.isRegularFile(optFile) ? Files.readString(optFile, StandardCharsets.UTF_8) : null;
        Path dump = Paths.get("build/packReplay/_dump").resolve(PackHarness.safe(name));
        PackHarness.deleteTree(dump);
        FrontEnd.PackResult fr = FrontEnd.run(PackSource.open(pack), new FrontEnd.Config(StandardMacros.Env.defaults(), saved, true, dump));
        if (!fr.packErrors.isEmpty())
            throw new IllegalStateException("front end refused the pack: " + fr.packErrors);
        PackData data = PackData.load(dump);
        PackPlan plan = PackPlan.build(data);

        PackFrameInputs in;
        if (opt.containsKey("capture")) {
            in = FrameCapture.read(Paths.get(opt.get("capture")));
            w = in.width();
            h = in.height();
        } else if (opt.containsKey("full")) {
            // P3-5 FULL mode: real geometry through the shadow pass and gbuffers programs
            SyntheticFullScene fs = new SyntheticFullScene(data, plan.frames.get(folder), plan.resources.get(folder), w, h);
            System.out.println("# full mode: " + fs.geometry().size() + " batches, " + fs.quads() + " quads");
            in = fs;
        } else {
            in = new SyntheticScene(data, plan.frames.get(folder), plan.resources.get(folder), w, h);
            FrameCapture.of(in, name, folder, "synthetic", "SyntheticScene: grass plane, boxes, glowing block, water, sky; undistorted shadow map")
                    .write(out.resolve("synthetic.irohframe"));
        }

        long t0 = System.nanoTime();
        boolean ok = true;
        StringBuilder report = new StringBuilder();
        try (HeadlessVkBackend gpu = new HeadlessVkBackend(Boolean.getBoolean("iroh.replay.allowGpu"), 1L << 30)) {
            System.out.println("# device " + gpu.deviceName + " (" + gpu.name() + ")");
            FrameExecutor ex = new FrameExecutor(data, plan, folder, gpu, FrameExecutor.cap(1L << 30));
            if (opt.containsKey("upscale")) {
                // P3-7 D: the scene renders at w x h (render size), display = w,h / scale; Navidson's cut (CutPoint):
                // after composite5, input colortex3, composite6 (TAA) replaced; bilinear stand-in upscaler
                double sc = Double.parseDouble(opt.get("upscale"));
                ex.upscale = new FrameExecutor.Upscale("composite5", "colortex3", java.util.Set.of("composite6"),
                        (int) Math.round(w / sc), (int) Math.round(h / sc), new dev.elemore.iroh.shaderpack.runtime.PackUpscaler.Bilinear());
            }
            if (opt.containsKey("post")) {
                // POST mode as the game runs it: a raster "scene" colour + depth come in by blit, flat clears, the output is
                // blitted back into the scene colour (the in-game path's copies, exercised on lavapipe)
                PackFrameInputs syn = in;
                int sc = gpu.createImage(new GpuBackend.ImageDesc("scene colour", "R8G8B8A8_UNORM", w, h, 1, 1,
                        java.util.Set.of("COLOR_ATTACHMENT", "SAMPLED", "TRANSFER_SRC", "TRANSFER_DST")));
                int sd = gpu.createImage(new GpuBackend.ImageDesc("scene depth", "D32_SFLOAT", w, h, 1, 1,
                        java.util.Set.of("SAMPLED", "TRANSFER_SRC", "TRANSFER_DST")));
                float[] c0 = syn.image("colortex0");
                float[] srgb = new float[c0.length];
                for (int i = 0; i < c0.length; i++)
                    srgb[i] = (i & 3) == 3 ? 1f : (float) Math.pow(Math.min(1, c0[i]), 1 / 2.2);
                gpu.upload(sc, srgb);
                gpu.upload(sd, syn.image("depthtex0"));
                ex.presentTo = sc;
                Map<String, Integer> copies = new LinkedHashMap<>();
                copies.put("colortex0", sc);
                copies.put("depthtex0", sd);
                copies.put("depthtex1", sd);
                copies.put("depthtex2", sd);
                Map<String, float[]> clears = new LinkedHashMap<>();
                clears.put("colortex6", new float[]{0, 0, 1, 0});
                clears.put("colortex4", new float[]{0, 1, 0, 0});
                clears.put("shadowtex0", new float[]{1, 1, 1, 1});
                clears.put("shadowtex1", new float[]{1, 1, 1, 1});
                in = new PackFrameInputs() {
                    public int width() { return syn.width(); }
                    public int height() { return syn.height(); }
                    public Map<String, double[]> uniforms() { return syn.uniforms(); }
                    public java.util.Set<String> suppliedImages() { return java.util.Set.of(); }
                    public float[] image(String n) { return null; }
                    public int[] extent(String n) { return null; }
                    public String startStage() { return "deferred"; }
                    public double frameSeconds() { return syn.frameSeconds(); }
                    public Map<String, Integer> copySources() { return copies; }
                    public Map<String, float[]> clearValues() { return clears; }
                };
            }
            ex.prepare(in);
            boolean dumpPasses = opt.containsKey("dump");
            if (dumpPasses)
                ex.afterPass = (pass, keys) -> {
                    for (String k : keys)
                        try {
                            Integer img = ex.images.get(k);
                            if (img == null)
                                img = ex.images.get(k.substring(0, k.indexOf('#')));
                            if (img != null)
                                png(gpu, img, out.resolve("targets").resolve(String.format("%02d-%s-%s.png", ex.passIndex, pass, k.replace('#', '_'))));
                        } catch (Exception e) {
                            System.out.println("dump " + k + ": " + e);
                        }
                };
            for (int f = 0; f < frames; f++)
                ex.frame(in);
            if (opt.containsKey("upscale") && ex.images.containsKey("engine:motion")) {
                // P3-7 D: camera-only motion vectors: zero for a static camera; with the camera moved +1 block
                // along x since the previous frame, non-zero and finite everywhere
                float[] mv0 = gpu.readback(ex.images.get("engine:motion"));
                double max0 = 0;
                for (int i = 0; i < mv0.length; i += 4)
                    max0 = Math.max(max0, Math.max(Math.abs(mv0[i]), Math.abs(mv0[i + 1])));
                Map<String, double[]> moved = new LinkedHashMap<>(in.uniforms());
                double[] cam = moved.get("cameraPosition");
                moved.put("previousCameraPosition", new double[]{cam[0] - 1, cam[1], cam[2]});
                PackFrameInputs base = in;
                ex.frame(new PackFrameInputs() {
                    public int width() { return base.width(); }
                    public int height() { return base.height(); }
                    public Map<String, double[]> uniforms() { return moved; }
                    public java.util.Set<String> suppliedImages() { return base.suppliedImages(); }
                    public float[] image(String n) { return base.image(n); }
                    public int[] extent(String n) { return base.extent(n); }
                    public String startStage() { return base.startStage(); }
                    public double frameSeconds() { return base.frameSeconds(); }
                    public List<Geometry> geometry() { return base.geometry(); }
                    public java.util.Set<String> geometryTextures() { return base.geometryTextures(); }
                });
                float[] mv1 = gpu.readback(ex.images.get("engine:motion"));
                double sumX = 0, bad = 0;
                for (int i = 0; i < mv1.length; i += 4) {
                    if (!Float.isFinite(mv1[i]) || !Float.isFinite(mv1[i + 1]))
                        bad++;
                    sumX += mv1[i];
                }
                double meanX = sumX / (mv1.length / 4.0);
                report.append(String.format("# motion static max %.6f, camera +1x mean mv.x %.5f (uv units), non-finite %d%n", max0, meanX, (long) bad));
                if (max0 > 1e-3 || bad > 0 || meanX <= 0)
                    ok = false;
                ex.frame(in); // back to the static frame for the golden
            }
            if (opt.containsKey("full") && opt.containsKey("roundtrip")) {
                // P3-6: the in-game FULL-mode capture path offline: read back every target before "deferred" (the
                // half a replay uploads into), write an .irohframe, replay it from "deferred" on a fresh executor and
                // compare with the full frame's output
                Map<String, float[]> capImgs = new LinkedHashMap<>();
                Map<String, int[]> capExt = new LinkedHashMap<>();
                ex.onStage = st -> {
                    if (!st.equals("deferred"))
                        return;
                    // every target half under its exact key (a pair's other half holds last frame's history) + image.* volumes
                    java.util.Set<String> names = new java.util.TreeSet<>();
                    for (String k : ex.images.keySet())
                        if (!k.startsWith("dummy:") && !k.startsWith("custom:") && !k.startsWith("geo:") && !k.equals("output"))
                            names.add(k);
                    for (String n : names) {
                        Integer img = ex.images.get(n);
                        GpuBackend.ImageDesc d = gpu.desc(img);
                        capImgs.put(n, gpu.readback(img));
                        capExt.put(n, new int[]{d.width(), d.height(), d.depth()});
                    }
                };
                ex.frame(in);
                ex.onStage = null;
                float[] fullOut = gpu.readback(ex.images.get("output"));
                FrameCapture cap = new FrameCapture(name, folder, "synthetic-full", "round trip of the FULL-mode capture path", w, h,
                        "deferred", in.frameSeconds(), new LinkedHashMap<>(in.uniforms()), capImgs, capExt);
                cap.write(out.resolve("full-capture.irohframe"));
                FrameCapture back = FrameCapture.read(out.resolve("full-capture.irohframe"));
                FrameExecutor ex2 = new FrameExecutor(data, plan, folder, gpu, FrameExecutor.cap(1L << 30));
                ex2.frame(back);
                float[] replayOut = gpu.readback(ex2.images.get("output"));
                double diff = 0;
                for (int i = 0; i < fullOut.length; i++)
                    diff += Math.abs(fullOut[i] - replayOut[i]);
                diff /= fullOut.length;
                report.append(String.format("# roundtrip FULL frame vs replay of its capture: mean abs diff %.5f (%d images captured)%n", diff, capImgs.size()));
                if (diff > 0.02)
                    ok = false;
                ex2.close();
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            Map<String, Map<String, Object>> stats = ex.inspect();
            for (Map.Entry<String, Map<String, Object>> e : stats.entrySet()) {
                Map<String, Object> s = e.getValue();
                long nan = (Long) s.get("nan"), inf = (Long) s.get("inf");
                boolean bad = nan > 0 || inf > 0;
                if (bad)
                    ok = false;
                report.append(String.format("%-4s %-22s %-26s nan %-6d inf %-6d mean %s max %s%n", bad ? "BAD" : "ok", e.getKey(),
                        s.get("format") + " " + s.get("extent"), nan, inf, s.get("mean"), s.get("max")));
                if (!e.getKey().startsWith("depth") && !e.getKey().startsWith("shadowtex"))
                    png(gpu, ex.images.get(e.getKey()), out.resolve("targets").resolve(e.getKey().replace('#', '_') + ".png"));
            }
            Map<String, Object> outStats = stats.get("output");
            @SuppressWarnings("unchecked") List<Double> mean = (List<Double>) outStats.get("mean");
            double lum = (mean.get(0) + mean.get(1) + mean.get(2)) / 3;
            if (Boolean.TRUE.equals(outStats.get("allZeroRgb")) || lum < 0.01) {
                ok = false;
                report.append("BAD  output is black (mean ").append(lum).append(")\n");
            }
            png(gpu, ex.images.get("output"), out.resolve("final.png"));
            for (String p : ex.problems) {
                ok = false;
                report.append("PROBLEM ").append(p).append('\n');
            }
            for (String l : gpu.log)
                report.append("note ").append(l).append('\n');
            Files.writeString(out.resolve("trace.txt"), String.join("\n", ex.trace) + "\n", StandardCharsets.UTF_8);
            Map<String, Object> js = new LinkedHashMap<>();
            js.put("device", gpu.deviceName);
            js.put("size", w + "x" + h);
            js.put("frames", frames);
            js.put("ms", ms);
            js.put("deviceMemoryMiB", gpu.allocatedBytes() >> 20);
            js.put("targets", stats);
            Files.writeString(out.resolve("stats.json"), Json.write(js), StandardCharsets.UTF_8);
            // golden: hash of the output's RGBA8 bytes + NaN-free flags
            String hash = sha256(TexelCodec.encode("R8G8B8A8_UNORM", gpu.readback(ex.images.get("output"))));
            // real captures (G1 capture1, 117 MB, kept OUT of the repo under ~/Projects/proctor) get their own golden by dir name
            String kind = opt.containsKey("capture")
                    ? "capture-" + PackHarness.safe(Paths.get(opt.get("capture")).getFileName().toString().replace(".irohframe", ""))
                    : (opt.containsKey("post") ? "synthetic-post" : opt.containsKey("full") ? "synthetic-full" : "synthetic")
                    + (opt.containsKey("upscale") ? "-up" + opt.get("upscale") : "");
            Path golden = Paths.get("src/test/replay").resolve(PackHarness.safe(name) + "-" + folder + "-" + w + "x" + h + "-" + kind + ".golden");
            String mode = opt.get("golden");
            if (mode != null) {
                if (mode.equals("update")) {
                    Files.createDirectories(golden.getParent());
                    Files.writeString(golden, "sha256 " + hash + "\nframes " + frames + "\n", StandardCharsets.UTF_8);
                    report.append("golden written: ").append(golden).append('\n');
                } else if (Files.isRegularFile(golden)) {
                    String want = Files.readAllLines(golden).get(0).replace("sha256 ", "").trim();
                    boolean same = want.equals(hash);
                    report.append(same ? "golden ok " : "BAD  golden mismatch ").append(hash).append('\n');
                    ok &= same;
                } else {
                    report.append("no golden at ").append(golden).append(" (run -Pgolden=update)\n");
                }
            }
            System.out.print(report);
            System.out.println("# output " + out.resolve("final.png") + " sha256 " + hash);
            System.out.println("# replay " + (ok ? "PASS" : "FAIL") + " " + name + " " + folder + " " + w + "x" + h + " frames " + frames
                    + " passes " + ex.drawCount + " in " + ms + " ms, device memory " + (gpu.allocatedBytes() >> 20) + " MiB");
        }
        System.exit(ok ? 0 : 1);
    }

    static String sha256(ByteBuffer b) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(b.duplicate());
        return HexFormat.of().formatHex(md.digest());
    }

    /** PNG of mip 0 (rgb clamped to 0..1, top row = GL's last row); depth/integer shown as their r channel */
    static void png(GpuBackend gpu, int image, Path file) throws Exception {
        GpuBackend.ImageDesc d = gpu.desc(image);
        float[] px = gpu.readback(image);
        if (px == null || d.depth() > 1)
            return;
        boolean single = d.vkFormat().startsWith("R8_") || d.vkFormat().startsWith("R16_") || d.vkFormat().startsWith("R32_") || d.vkFormat().startsWith("D");
        BufferedImage bi = new BufferedImage(d.width(), d.height(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < d.height(); y++)
            for (int x = 0; x < d.width(); x++) {
                int i = 4 * (y * d.width() + x);
                float r = px[i], g = single ? px[i] : px[i + 1], b = single ? px[i] : px[i + 2];
                if (d.vkFormat().contains("SNORM")) {
                    r = r * 0.5f + 0.5f;
                    g = g * 0.5f + 0.5f;
                    b = b * 0.5f + 0.5f;
                }
                int rgb = (c8(r) << 16) | (c8(g) << 8) | c8(b);
                bi.setRGB(x, d.height() - 1 - y, rgb);
            }
        ImageIO.write(bi, "png", file.toFile());
    }

    static int c8(float v) {
        if (Float.isNaN(v))
            return 255;
        return Math.max(0, Math.min(255, Math.round(v * 255)));
    }
}
