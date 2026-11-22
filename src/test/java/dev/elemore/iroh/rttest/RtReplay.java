package dev.elemore.iroh.rttest;

import dev.elemore.iroh.gpu.VkGpuDevice.VkBuf;
import dev.elemore.iroh.rt.RtFrameData;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * P5-0 offline RT prototype on lavapipe ({@code ./gradlew rtReplay}). Steps, each checked against {@link CpuRef}:
 * (a) BLAS/TLAS + primary-visibility debug view vs the raster G-buffer depth; (b) sun shadows hard then soft;
 * (c) block light from the emissive quad list; (d) one-bounce GI without and with the radiance cache (bias measured);
 * (e) reflections (water mirror + transmission, glossy GGX); (f) alpha-tested leaves/panes in shadow and GI rays;
 * (g) composition with sky and fog; (h) temporal accumulation under a scripted 30-frame camera move (disocclusion,
 * variance vs frame count). Images + metrics in build/rt; goldens in src/test/resources/rtgolden. Never a GPU.
 */
public final class RtReplay {
    static int pass, fail;
    static final List<String> report = new ArrayList<>();
    static PrintStream metrics;
    static final Path OUT = Path.of("build/rt"), GOLD = Path.of("src/test/resources/rtgolden");

    /** Lighting and atmosphere of the test (one shared definition for GPU and CPU). */
    static final class Light {
        float[] sunDir, sunE = {3.2f, 3.0f, 2.7f}, zenith = {0.18f, 0.30f, 0.62f}, horizon = {0.55f, 0.62f, 0.75f};
        float sunRadius = 0.035f;   // 2 degrees: a visible penumbra at 160x90 (the real sun is 0.27 deg)
        boolean fog = true;
        float fogDensity = 0.012f, fogFalloff = 0.08f, fogBase = 6f;
        float[] waterExtinction;
        float polishedRoughness = 0;

        Light(float[] ext) {
            double el = Math.toRadians(38), az = Math.toRadians(215);
            sunDir = new float[]{(float) (Math.cos(el) * Math.sin(az)), (float) Math.sin(el), (float) (Math.cos(el) * Math.cos(az))};
            waterExtinction = ext;
        }
    }

    static void check(String what, boolean ok) {
        String line = (ok ? "ok   " : "FAIL ") + what;
        System.out.println(line);
        report.add(line);
        if (ok) pass++; else fail++;
    }

    static void metric(String s) {
        System.out.println("  " + s);
        metrics.println(s);
    }

    static String f(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    public static void main(String[] args) throws IOException {
        String step = System.getProperty("rt.step", "all");
        boolean quick = System.getProperty("rt.quick") != null;
        String res = System.getProperty("rt.res", "160x90");
        int W = Integer.parseInt(res.split("x")[0]), H = Integer.parseInt(res.split("x")[1]);
        int frames = Integer.parseInt(System.getProperty("rt.frames", quick ? "64" : "256"));
        Files.createDirectories(OUT);
        metrics = new PrintStream(Files.newOutputStream(OUT.resolve("metrics.txt")), true);
        long t0 = System.nanoTime();

        RtScene scene = new RtScene();
        RtGpu probe = null;
        Light light = new Light(new RtFrameData().waterExtinction);
        RtCamera cam = new RtCamera(new Vector3f(-2.0f, 16.5f, 27.0f), new Vector3f(16.0f, 7.0f, 14.0f), 70, W, H);
        int quads = 0;
        for (RtScene.SectionMesh m : scene.sections) quads += m.totalQuads();
        metric("scene: " + scene.sections.size() + " sections, " + quads + " quads, " + scene.lights.size() + " emissive quads, " + W + "x" + H + ", frames " + frames);

        try (RtGpu gpu = new RtGpu(scene, W, H)) {
            metric("device: " + gpu.dev.deviceName + "  (lavapipe: CPU Vulkan; timings below are RELATIVE, never GPU performance)");
            metric("AS vertex formats: R16G16B16A16_SNORM " + gpu.accel.vertexFormatSupported(org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SNORM)
                    + ", R32G32B32_SFLOAT " + gpu.accel.vertexFormatSupported(org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32_SFLOAT)
                    + ", R16G16B16A16_SINT " + gpu.accel.vertexFormatSupported(org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SINT));
            metric(String.format(Locale.ROOT, "accel: %d instances, BLAS built %d B -> compacted %d B (%.1f%%), max scratch %d B, TLAS %d B, quads %d -> %.1f B/quad compacted",
                    gpu.instanceCount, gpu.blasBytesBuilt, gpu.blasBytesCompacted, 100.0 * gpu.blasBytesCompacted / gpu.blasBytesBuilt, gpu.blasScratchMax,
                    gpu.tlasBytes, gpu.totalQuads, gpu.blasBytesCompacted / (double) gpu.totalQuads));
            CpuRef ref = new CpuRef(scene, light, cam);
            CpuRef.Surf[] prim = ref.primary();
            RtSteps s = new RtSteps(gpu, ref, prim, cam, light, frames, quick);
            if (step.equals("all") || step.contains("a")) s.stepA();
            if (step.equals("all") || step.contains("b")) s.stepB();
            if (step.equals("all") || step.contains("c")) s.stepCUnit();
            if (step.equals("all") || step.matches(".*[cdeg].*")) s.staticRun(step);
            if (step.equals("all") || step.contains("d")) s.stepDCache();
            if (step.equals("all") || step.contains("f")) s.stepF();
            if (step.equals("all") || step.contains("h")) s.stepH();
            s.costTable();
        }
        metric(String.format(Locale.ROOT, "wall time %.1f s", (System.nanoTime() - t0) / 1e9));
        System.out.println("# rtreplay " + (fail == 0 ? "PASS" : "FAIL") + " " + pass + "/" + (pass + fail));
        metrics.println("# rtreplay " + (fail == 0 ? "PASS" : "FAIL") + " " + pass + "/" + (pass + fail));
        for (String r : report) metrics.println(r);
        metrics.close();
        System.exit(fail == 0 ? 0 : 1);
    }
}
