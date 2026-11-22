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
            Path golden = Paths.get("src/test/replay").resolve(PackHarness.safe(name) + "-" + folder + "-" + w + "x" + h + "-synthetic.golden");
            String mode = opt.get("golden");
            if (mode != null && !opt.containsKey("capture")) {
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
