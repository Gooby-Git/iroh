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

    /**
     * V-7 (review 698ba7c #2): forget the loaded (or loading) pack so the next {@link #enable} reads {@link #packPath} again.
     * A load still running finishes on its thread and is ignored (ensureLoaded only joins the current future).
     */
    void dropLoaded() {
        loaded = null;
        loading = null;
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

    /** P3-6: full mode can run this frame (startup format, loaded, healthy, terrain sub-switch on) */
    boolean fullReady() {
        return PackStartup.FULL && !failed && ensureLoaded() && DebugSwitches.on(PackSwitches.FULL_TERRAIN)
                && net.vulkanmod.vulkan.device.IndirectDrawCaps.useTerrainIndirect(); // P2-3: FULL writes firstInstance indirect commands
    }

    private boolean fullRanThisFrame;

    void worldEnd() {
        if (fullRanThisFrame) { // the full frame already presented before entities
            fullRanThisFrame = false;
            return;
        }
        frame(false);
    }

    void fullFrame() {
        fullRanThisFrame = frame(true);
    }

    private boolean ensureLoaded() {
        if (loaded != null)
            return true;
        CompletableFuture<Loaded> f = loading;
        if (f == null || !f.isDone())
            return false;
        try {
            loaded = f.join();
            PackStartup.blockIds = PackIdMaps.of(loaded.data()).blockMap();
            if (PackStartup.FULL) {
                // sections meshed before the ids existed carry mc_Entity -1: mesh everything again once
                log("block ids loaded (" + PackStartup.blockIds.size() + "): rebuilding sections");
                Minecraft.getInstance().execute(() -> Minecraft.getInstance().levelRenderer.allChanged());
            }
            return true;
        } catch (RuntimeException e) {
            fail("loading failed: " + (e.getCause() != null ? e.getCause() : e));
            return false;
        }
    }

    private final ShadowPlanner shadowPlanner = new ShadowPlanner();
    private int lastPlannedRebuilds = -1;
    private VulkanImage importedAtlas;
    private int hAtlas = -1;

    /** @return true when a frame was recorded */
    private boolean frame(boolean full) {
        if (failed)
            return false;
        if (!ensureLoaded())
            return false;
        Renderer r = Renderer.getInstance();
        SceneTarget st = r.getMainPass().getSceneTarget();
        if (st == null) {
            fail("no scene target (main pass without one)");
            return false;
        }
        if (gpu == null) {
            gpu = new IrohVkBackend();
            IrohVkBackend g = gpu;
            dev.elemore.iroh.apiimpl.FrameProfiler.EXTRA = () -> g.times.last() + " | " + stats.last;
            lc = new PackLifecycle(gpu, (name, bytes) -> true); // MemoryGuard is asked per image inside IrohVkBackend
            lc.enable(loaded.data(), loaded.plan());
            lc.tracing = DebugSwitches.on(PackSwitches.LOG);
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
            return false;
        }
        if (!folder.equals(lastFolder)) {
            log("folder " + folder);
            lastFolder = folder;
        }
        copies.clear();
        clears.clear();
        inputs.full = full;
        if (full) {
            // FULL: geometry + shadow + chain; the raster sky (already in the scene colour, terrain skipped) is the
            // background in colortex0; depthtex0 goes back into the scene depth for the raster draws after this
            if (DebugSwitches.on(PackSwitches.FULL_RASTER_SKY))
                copies.put("colortex0", hColour);
            inputs.depthTo = DebugSwitches.on(PackSwitches.FULL_DEPTH_BACK) ? hDepth : -1;
            VulkanImage atlas = blockAtlas();
            if (atlas != null && atlas != importedAtlas) {
                if (hAtlas >= 0)
                    gpu.freeImage(hAtlas);
                hAtlas = gpu.importImage(atlas, "block atlas");
                importedAtlas = atlas;
            }
            inputs.geoImages.clear();
            if (hAtlas >= 0)
                inputs.geoImages.put("geo:tex", hAtlas);
        }
        if (!full && DebugSwitches.on(PackSwitches.POST_SCENE))
            copies.put("colortex0", hColour);
        if (!full && DebugSwitches.on(PackSwitches.POST_DEPTH))
            for (String dt : new String[]{"depthtex0", "depthtex1", "depthtex2"})
                copies.put(dt, hDepth);
        if (!full) {
            clears.put("colortex6", FLAT_MATERIAL);  // flat material: smooth 0, mask 0, full sky light
            clears.put("colortex4", FLAT_NORMAL);    // flat normal up
            clears.put("shadowtex0", ONE);           // no shadow pass in post mode: everything lit
            clears.put("shadowtex1", ONE);
        }
        if (!full && !DebugSwitches.on(PackSwitches.POST_DEPTH))
            for (String dt : new String[]{"depthtex0", "depthtex1", "depthtex2"})
                clears.put(dt, ONE);
        double sunPath = 0;
        try {
            sunPath = Double.parseDouble(loaded.plan().frames.get(folder).directives.getOrDefault("sunPathRotation", "0").replaceAll("[fF]$", ""));
        } catch (NumberFormatException ignored) {
        }
        inputs.setCustom(loaded.data().pack.getAsJsonArray("customUniforms"));
        var dir = loaded.plan().frames.get(folder).directives;
        inputs.shadowDistance = SyntheticFullScene.parse(dir.get("shadowDistance"), 96);
        inputs.shadowInterval = SyntheticFullScene.parse(dir.get("shadowIntervalSize"), 2);
        long cpu0 = System.nanoTime();
        inputs.update(FrameHooks.context(), st.getWidth(), st.getHeight(), copies, clears, sunPath);
        if (full) {
            inputs.geometry.clear();
            inputs.geometryVersion++; // the list object is reused: tell the executor its content changed
            var c = FrameHooks.context().cameraPos();
            boolean shadow = DebugSwitches.on(PackSwitches.FULL_SHADOW);
            // P3-7 C: reuse last frame's shadow maps while the shadow camera barely moved (bounded staleness)
            int maxStale = 0;
            try {
                maxStale = Integer.parseInt(DebugSwitches.getOr(PackSwitches.FULL_SHADOW_REUSE, "3").trim());
            } catch (NumberFormatException ignored) {
            }
            boolean reuse = false;
            if (shadow) {
                Map<String, double[]> u = inputs.uniforms();
                double[][] d = shadowPlanner.decide(u.get("shadowModelView"), u.get("shadowProjection"), maxStale, 2e-3,
                        lc.executor() == null || lc.rebuilds != lastPlannedRebuilds);
                lastPlannedRebuilds = lc.rebuilds;
                reuse = d[2][0] == 1;
                if (reuse) {
                    u.put("shadowModelView", d[0]);
                    u.put("shadowModelViewInverse", Mat.invert(d[0]));
                    u.put("shadowProjection", d[1]);
                    u.put("shadowProjectionInverse", Mat.invert(d[1]));
                }
            }
            if (lc.executor() != null)
                lc.executor().reuseShadow = reuse;
            net.vulkanmod.render.chunk.WorldRenderer wr = net.vulkanmod.render.chunk.WorldRenderer.getInstance();
            wr.collectPackTerrain(inputs.geometry, c.x(), c.y(), c.z(), shadow && !reuse ? inputs.uniforms().get("shadowModelView") : null, inputs.shadowDistance);
            stats.mainDraws = wr.packMainDraws;
            stats.shadowSections = shadow && !reuse ? wr.packShadowSections : 0;
            stats.shadowReused = shadowPlanner.reused;
            stats.shadowDraws = shadow ? wr.packShadowDraws : 0;
            stats.batches = inputs.geometry.size();
        }
        skip.clear();
        for (String s : DebugSwitches.getOr(PackSwitches.SKIP, "").split(","))
            if (!s.isBlank())
                skip.add(s.trim());
        if (full && !DebugSwitches.on(PackSwitches.FULL_SHADOW))
            for (var p0 : loaded.plan().frames.get(folder).passes)
                if (p0.id.startsWith("shadow:"))
                    skip.add(p0.id);
        if (full && !DebugSwitches.on(PackSwitches.FULL_TRANSLUCENT))
            skip.add("gbuffers_water:translucent");
        int present = DebugSwitches.on(PackSwitches.PRESENT) ? hColour : -1;
        String f = folder;
        Path cap = captureDir;
        int rebuildsBefore = lc.rebuilds;
        boolean[] ok = {false};
        VulkanImage.endPassRunResume(() -> {
            if (cap != null && lc.executor() != null)
                if (full)
                    lc.executor().onStage = st0 -> {
                        if (st0.equals("deferred") && lc.executor().onStage != null) {
                            lc.executor().onStage = null;
                            startCapture(cap, f, true);
                        }
                    };
                else
                    lc.executor().afterInputs = () -> startCapture(cap, f, false);
            prepImported();
            ok[0] = lc.frame(inputs, f, present, skip);
            if (lc.executor() != null) {
                lc.executor().afterInputs = null;
                lc.executor().onStage = null;
            }
        });
        if (cap != null && lc.rebuilds == rebuildsBefore)
            captureDir = null;
        if (lc.rebuilds != rebuildsBefore && DebugSwitches.on(PackSwitches.LOG))
            log("built " + f + " at " + st.getWidth() + "x" + st.getHeight() + " (" + lc.executor().images.size() + " images)");
        stats.cpuNs += System.nanoTime() - cpu0;
        stats.frames++;
        stats.maybeLog(full);
        if (lc.state() == PackLifecycle.State.FAILED) {
            fail(lc.failure);
            return false;
        }
        return ok[0];
    }

    // reused per frame (P3-6: no per-frame maps in the hook)
    private final Map<String, Integer> copies = new LinkedHashMap<>();
    private final Map<String, float[]> clears = new LinkedHashMap<>();
    private final Set<String> skip = new HashSet<>();
    private static final float[] FLAT_MATERIAL = {0, 0, 1, 0}, FLAT_NORMAL = {0, 1, 0, 0}, ONE = {1, 1, 1, 1};
    final FrameStats stats = new FrameStats();

    /** CPU cost of the pack frame and the terrain/shadow counts, published every 2 s (iroh.pack.cpu) and in -Diroh.profile */
    static final class FrameStats {
        long cpuNs, frames, lastLog = System.nanoTime();
        int mainDraws, shadowSections, shadowDraws, batches, shadowReused;
        String last = "no samples yet";

        void maybeLog(boolean full) {
            long now = System.nanoTime();
            if (now - lastLog < 2_000_000_000L || frames == 0)
                return;
            last = String.format("pack cpu ms/frame %.3f (%s, %d frames)%s", cpuNs / 1e6 / frames, full ? "full" : "post", frames,
                    full ? String.format(" terrain draws %d, shadow sections %d draws %d, batches %d, shadow maps reused %d", mainDraws, shadowSections,
                            shadowDraws, batches, shadowReused) : "");
            DebugSwitches.publish(PackSwitches.CPU, last);
            cpuNs = 0;
            frames = 0;
            lastLog = now;
        }
    }

    static VulkanImage blockAtlas() {
        try {
            var tex = Minecraft.getInstance().getTextureManager().getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
            var gl = net.vulkanmod.gl.VkGlTexture.getTexture(tex.getId());
            return gl == null ? null : gl.getVulkanImage();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * PACK-FULL: the block atlas (imported, sampled by the pack's terrain programs) in a sampling layout before a
     * frame records descriptors naming it: an atlas upload earlier in the frame leaves it in TRANSFER_DST
     * (compare-and-skip when it is already SHADER_READ_ONLY). The frame runs inside endPassRunResume: outside every pass.
     */
    private void prepImported() {
        if (gpu != null && hAtlas >= 0)
            gpu.prepareSample(hAtlas);
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
        dev.elemore.iroh.apiimpl.FrameProfiler.EXTRA = null;
        gpu = null;
        lc = null;
        // PACK-FULL: handles of the closed backend: a reload made a new backend but kept the atlas handle (no re-import:
        // same VulkanImage)
        hAtlas = -1;
        importedAtlas = null;
        lastFolder = null;
        log("disabled: pack images, pipelines and buffers freed (deferred)");
        if (dev.elemore.iroh.diag.ImageTrace.ON) // the frees land when the frame slots come round: report then
            net.vulkanmod.vulkan.memory.MemoryManager.getInstance().addFrameOp(() -> net.vulkanmod.vulkan.memory.MemoryManager.getInstance()
                    .addFrameOp(() -> log("after disable: " + dev.elemore.iroh.diag.ImageTrace.report())));
    }

    // ------------------------------------------------------------------------------------------------ capture

    String requestCapture(Path dir) {
        if (capturing || captureDir != null)
            return "refused: a capture is in progress";
        captureDir = dir;
        return "ok";
    }

    /** reads back the images the chain starts from (after copies and clears) and writes one .irohframe, off-thread */
    private void startCapture(Path dir, String folder, boolean full) {
        FrameExecutor ex = lc.executor();
        capturing = true;
        gpu.barrier(0, "TRANSFER_WRITE", "TRANSFER_READ"); // the copies and clears just recorded finish before the readback
        captureDir = null;
        Map<String, Integer> want = new LinkedHashMap<>();
        if (full) {
            // FULL mode, before "deferred": every target the gbuffers/shadow passes produced, in the half a replay from
            // "deferred" uploads into, so packReplay -Pcapture re-runs the deferred + composite chain on REAL gbuffers
            // every target half under its exact key (a pair's other half holds last frame's history) + image.* volumes
            for (String k : new java.util.TreeSet<>(ex.images.keySet()))
                if (!k.startsWith("dummy:") && !k.startsWith("custom:") && !k.startsWith("geo:") && !k.equals("output"))
                    want.put(k, ex.images.get(k));
        } else
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
                            new FrameCapture(packName, folder, "game", full
                                    ? "IrohDebug.capturePackFrame, FULL mode: real gbuffers + shadow maps before deferred"
                                    : "IrohDebug.capturePackFrame, post mode (raster colour/depth, flat material)",
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
