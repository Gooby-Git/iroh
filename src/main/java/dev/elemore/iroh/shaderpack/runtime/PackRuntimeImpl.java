package dev.elemore.iroh.shaderpack.runtime;

import dev.elemore.iroh.apiimpl.FrameHooks;
import dev.elemore.iroh.debug.DebugSwitches;
import dev.elemore.iroh.shaderpack.FrontEnd;
import dev.elemore.iroh.shaderpack.NavidsonOnly;
import dev.elemore.iroh.shaderpack.PackSource;
import dev.elemore.iroh.shaderpack.StandardMacros;
import dev.elemore.iroh.shaderpack.graph.PackData;
import dev.elemore.iroh.shaderpack.graph.PackPlan;
import net.minecraft.client.Minecraft;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.SceneTarget;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Game-side work of the pack path (P3-4, POST mode): loads Navidson off the render thread (front end + plan into
 * {@code <gameDir>/.iroh/pack/}), and at WORLD_END runs the plan's deferred/composite/final chain over the raster
 * picture on {@link IrohVkBackend}, inside {@link VulkanImage#endPassRunResume}. Any failure (memory refusal, a
 * Vulkan error, an exception) switches the path off for the session with ONE log line; the raster path continues.
 * Render thread only, except loading. UNVERIFIED until the game runs it.
 */
final class PackRuntimeImpl {

    private record Loaded(PackData data, PackPlan plan) {}

    private volatile CompletableFuture<Loaded> loading;
    private volatile Loaded loaded;
    private volatile boolean failed;
    private IrohVkBackend gpu;
    private PackLifecycle lc;
    private final GameInputs inputs = new GameInputs();
    private VulkanImage importedColour, importedDepth;
    private int hColour = -1, hDepth = -1;
    private volatile Path captureDir;
    private volatile boolean capturing;
    private String lastFolder;

    static void log(String s) {
        Initializer.LOGGER.info("Iroh pack: " + s);
    }

    String enable() {
        failed = false;
        if (loaded != null || loading != null)
            return "ok";
        Path pack = packPath();
        try {
            PackSource src = PackSource.open(pack);
            String refusal = NavidsonOnly.refusal(src);
            if (refusal != null) {
                failed = true;
                log(refusal);
                return "refused: " + refusal;
            }
            Path dump = Minecraft.getInstance().gameDirectory.toPath().resolve(".iroh/pack").resolve(pack.getFileName().toString());
            Path opt = pack.resolveSibling(pack.getFileName() + ".txt");
            loading = CompletableFuture.supplyAsync(() -> {
                try {
                    String saved = Files.isRegularFile(opt) ? Files.readString(opt, StandardCharsets.UTF_8) : null;
                    long t0 = System.nanoTime();
                    FrontEnd.PackResult r = FrontEnd.run(src, new FrontEnd.Config(StandardMacros.Env.defaults(), saved, true, dump));
                    if (!r.packErrors.isEmpty())
                        throw new IllegalStateException("front end refused the pack: " + r.packErrors);
                    PackData d = PackData.load(dump);
                    PackPlan p = PackPlan.build(d);
                    log("loaded " + d.name + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms, folders " + p.frames.keySet()
                            + ", " + p.errors() + " plan errors");
                    return new Loaded(d, p);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, r -> {
                Thread t = new Thread(r, "Iroh pack loader");
                t.setDaemon(true);
                t.start();
            });
            log("loading " + pack + " (options " + (Files.isRegularFile(opt) ? opt.getFileName() : "defaults") + ")");
            return "ok";
        } catch (Exception e) {
            failed = true;
            log("cannot open " + pack + ": " + e);
            return "error: " + e;
        }
    }

    static Path packPath() {
        String p = DebugSwitches.getOr(PackSwitches.PATH, "");
        if (!p.isBlank())
            return Path.of(p);
        return Minecraft.getInstance().gameDirectory.toPath().resolve("shaderpacks/Navidson");
    }

    boolean usable() {
        return !failed;
    }

    void worldEnd() {
        if (failed)
            return;
        if (loaded == null) {
            CompletableFuture<Loaded> f = loading;
            if (f == null || !f.isDone())
                return;
            try {
                loaded = f.join();
            } catch (RuntimeException e) {
                fail("loading failed: " + (e.getCause() != null ? e.getCause() : e));
                return;
            }
        }
        Renderer r = Renderer.getInstance();
        SceneTarget st = r.getMainPass().getSceneTarget();
        if (st == null) {
            fail("no scene target (main pass without one)");
            return;
        }
        if (gpu == null) {
            gpu = new IrohVkBackend();
            lc = new PackLifecycle(gpu, (name, bytes) -> true); // MemoryGuard is asked per image inside IrohVkBackend
            lc.enable(loaded.data(), loaded.plan());
        }
        VulkanImage colour = st.getColorAttachment(), depth = st.getDepthAttachment();
        if (colour != importedColour) {
            if (hColour >= 0)
                gpu.freeImage(hColour);
            hColour = gpu.importImage(colour, "scene colour");
            importedColour = colour;
        }
        if (depth != importedDepth) {
            if (hDepth >= 0)
                gpu.freeImage(hDepth);
            hDepth = gpu.importImage(depth, "scene depth");
            importedDepth = depth;
        }
        Minecraft mc = Minecraft.getInstance();
        String folder = DebugSwitches.getOr(PackSwitches.FOLDER, "auto");
        if (folder.equals("auto"))
            folder = lc.folderFor(mc.level != null ? mc.level.dimension().location().toString() : "minecraft:overworld");
        if (!loaded.plan().frames.containsKey(folder)) {
            fail("no program folder " + folder);
            return;
        }
        if (!folder.equals(lastFolder)) {
            log("folder " + folder);
            lastFolder = folder;
        }
        Map<String, Integer> copies = new LinkedHashMap<>();
        if (DebugSwitches.on(PackSwitches.POST_SCENE))
            copies.put("colortex0", hColour);
        if (DebugSwitches.on(PackSwitches.POST_DEPTH))
            for (String dt : new String[]{"depthtex0", "depthtex1", "depthtex2"})
                copies.put(dt, hDepth);
        Map<String, float[]> clears = new LinkedHashMap<>();
        clears.put("colortex6", new float[]{0, 0, 1, 0});  // flat material: smooth 0, mask 0, full sky light
        clears.put("colortex4", new float[]{0, 1, 0, 0});  // flat normal up
        clears.put("shadowtex0", new float[]{1, 1, 1, 1}); // no shadow pass in post mode: everything lit
        clears.put("shadowtex1", new float[]{1, 1, 1, 1});
        if (!DebugSwitches.on(PackSwitches.POST_DEPTH))
            for (String dt : new String[]{"depthtex0", "depthtex1", "depthtex2"})
                clears.put(dt, new float[]{1, 1, 1, 1});
        double sunPath = 0;
        try {
            sunPath = Double.parseDouble(loaded.plan().frames.get(folder).directives.getOrDefault("sunPathRotation", "0").replaceAll("[fF]$", ""));
        } catch (NumberFormatException ignored) {
        }
        inputs.setCustom(loaded.data().pack.getAsJsonArray("customUniforms"));
        inputs.update(FrameHooks.context(), st.getWidth(), st.getHeight(), copies, clears, sunPath);
        Set<String> skip = new HashSet<>();
        for (String s : DebugSwitches.getOr(PackSwitches.SKIP, "").split(","))
            if (!s.isBlank())
                skip.add(s.trim());
        int present = DebugSwitches.on(PackSwitches.PRESENT) ? hColour : -1;
        String f = folder;
        Path cap = captureDir;
        int rebuildsBefore = lc.rebuilds;
        boolean[] ok = {false};
        VulkanImage.endPassRunResume(() -> {
            if (cap != null && lc.executor() != null)
                lc.executor().afterInputs = () -> startCapture(cap, f);
            ok[0] = lc.frame(inputs, f, present, skip);
            if (lc.executor() != null)
                lc.executor().afterInputs = null;
        });
        if (cap != null && lc.rebuilds == rebuildsBefore)
            captureDir = null;
        if (lc.rebuilds != rebuildsBefore && DebugSwitches.on(PackSwitches.LOG))
            log("built " + f + " at " + st.getWidth() + "x" + st.getHeight() + " (" + lc.executor().images.size() + " images)");
        if (lc.state() == PackLifecycle.State.FAILED)
            fail(lc.failure);
    }

    private void fail(String why) {
        failed = true;
        PackRuntime.deactivate();
        log("pack path OFF for this session: " + why);
        if (lc != null)
            lc.disable();
    }

    void unload() {
        if (lc != null)
            lc.unload();
        if (gpu != null) {
            if (hColour >= 0)
                gpu.freeImage(hColour);
            if (hDepth >= 0)
                gpu.freeImage(hDepth);
        }
        hColour = hDepth = -1;
        importedColour = importedDepth = null;
    }

    void disable() {
        unload();
        if (lc != null)
            lc.disable();
        if (gpu != null)
            gpu.close();
        gpu = null;
        lc = null;
        lastFolder = null;
        log("disabled: pack images, pipelines and buffers freed (deferred)");
    }

    // ------------------------------------------------------------------------------------------------ capture

    String requestCapture(Path dir) {
        if (capturing || captureDir != null)
            return "refused: a capture is in progress";
        captureDir = dir;
        return "ok";
    }

    /** reads back the images the chain starts from (after copies and clears) and writes one .irohframe, off-thread */
    private void startCapture(Path dir, String folder) {
        FrameExecutor ex = lc.executor();
        capturing = true;
        gpu.barrier(0, "TRANSFER_WRITE", "TRANSFER_READ"); // the copies and clears just recorded finish before the readback
        captureDir = null;
        Map<String, Integer> want = new LinkedHashMap<>();
        for (String n : new String[]{"colortex0", "colortex3", "colortex4", "colortex6", "depthtex0", "depthtex1", "depthtex2", "shadowtex0", "shadowtex1"}) {
            Integer h = ex.images.get(n + "#main");
            if (h == null)
                h = ex.images.get(n);
            if (h != null)
                want.put(n, h);
        }
        long bytes = 0;
        for (Integer h : want.values()) {
            var d = gpu.desc(h);
            bytes += 16L * d.width() * d.height() * d.depth();
        }
        if (bytes > FrameCapture.MAX_BYTES) {
            log("capture refused: " + (bytes >> 20) + " MiB exceeds " + (FrameCapture.MAX_BYTES >> 20) + " MiB");
            capturing = false;
            return;
        }
        Map<String, float[]> images = new java.util.concurrent.ConcurrentHashMap<>();
        Map<String, int[]> extents = new LinkedHashMap<>();
        Map<String, double[]> uniforms = inputs.uniformSnapshot();
        AtomicInteger left = new AtomicInteger(want.size());
        int w = inputs.width(), h = inputs.height();
        String packName = loaded.data().name;
        for (Map.Entry<String, Integer> e : want.entrySet()) {
            var d = gpu.desc(e.getValue());
            extents.put(e.getKey(), new int[]{d.width(), d.height(), d.depth()});
            gpu.readbackAsync(e.getValue(), px -> {
                images.put(e.getKey(), px);
                if (left.decrementAndGet() == 0) {
                    Thread t = new Thread(() -> {
                        try {
                            new FrameCapture(packName, folder, "game", "IrohDebug.capturePackFrame, post mode (raster colour/depth, flat material)",
                                    w, h, "deferred", inputs.frameSeconds(), uniforms, images, extents).write(dir);
                            log("capture written: " + dir);
                        } catch (Exception ex2) {
                            log("capture failed: " + ex2);
                        } finally {
                            capturing = false;
                        }
                    }, "Iroh pack capture");
                    t.setDaemon(true);
                    t.start();
                }
            });
        }
        if (want.isEmpty())
            capturing = false;
    }
}
