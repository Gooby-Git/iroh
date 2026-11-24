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
                && net.vulkanmod.Initializer.CONFIG.indirectDraw;
    }

    private boolean fullRanThisFrame;

    void worldEnd() {
        if (worldFrame) { // P3-11: the world pass's last segment
            worldEndFrame();
            return;
        }
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

    private SceneTarget st;
    private String folder;
    private long cpu0;

    /** @return true when a frame was recorded */
    private boolean frame(boolean full) {
        String f0 = setup(full, false);
        if (f0 == null)
            return false;
        return rest(full, f0);
    }

    /** shared setup of every pack frame (imports, folder, copies/clears, inputs.update); null = no frame */
    private String setup(boolean full, boolean world) {
        if (failed)
            return null;
        if (!ensureLoaded())
            return null;
        Renderer r = Renderer.getInstance();
        st = r.getMainPass().getSceneTarget();
        if (st == null) {
            fail("no scene target (main pass without one)");
            return null;
        }
        if (gpu == null) {
            gpu = new IrohVkBackend();
            IrohVkBackend g = gpu;
            dev.elemore.iroh.apiimpl.FrameProfiler.EXTRA = () -> g.times.last() + " | " + stats.last;
            lc = new PackLifecycle(gpu, (name, bytes) -> true); // MemoryGuard is asked per image inside IrohVkBackend
            lc.enable(loaded.data(), loaded.plan());
            lc.tracing = DebugSwitches.on(PackSwitches.LOG);
            lc.expectGeometry = PackStartup.FULL; // P3-11: terrain pipelines even when a frame starts before its batches
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
            return null;
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
            if (DebugSwitches.on(PackSwitches.FULL_RASTER_SKY) && !world) // world pass: the sky is drawn later in the frame
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
        cpu0 = System.nanoTime();
        inputs.update(FrameHooks.context(), st.getWidth(), st.getHeight(), copies, clears, sunPath);
        this.folder = folder;
        return folder;
    }

    /** P3-6 FULL terrain collection (+ shadow reuse planning) into inputs.geometry */
    private void collectTerrain() {
        {
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
    }

    private void fillSkip(boolean full, String folder) {
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
    }

    private boolean rest(boolean full, String folder) {
        if (full)
            collectTerrain();
        fillSkip(full, folder);
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

    // ------------------------------------------------------------------------------------------------ P3-11 world pass

    private WorldPass world;
    private WorldPassGame worldGame;
    private boolean worldFrame;
    private String worldFailed;
    private boolean worldSubst;
    private static final float[] TRANSPARENT = {0, 0, 0, 0};
    private long worldLogNs;

    /** the world pass is wanted this frame: full mode ready, iroh.pack.world.split on, not failed, not Fabulous */
    private boolean worldWanted() {
        return worldFailed == null && DebugSwitches.on(PackSwitches.WORLD_SPLIT) && fullReady()
                && !Minecraft.useShaderTransparency(); // UNVERIFIED: check in G3b (Fabulous' own targets are not composed with the world pass)
    }

    /** WorldRenderEvents.START: segment 0 (clears + inputs) and the hooks go live; false = not a world-pass frame */
    boolean worldBegin() {
        worldFrame = false;
        if (world != null && world.failure() != null) // INT-3: failed outside worldEndFrame (e.g. frame ended inside it)
            failWorld(world.failure());
        if (!worldWanted())
            return false;
        // INT-3 interaction matrix rule 1: never together with render scale's world phase (pack wins; defensive);
        // INT-4 rule 8: never while ray tracing runs (only reached when the pack path is active)
        String refuse = dev.elemore.iroh.render.MainPassArbiter.worldPassRefusal(
                net.vulkanmod.vulkan.Renderer.getInstance().getMainPass() instanceof net.vulkanmod.vulkan.pass.DefaultMainPass d && d.inWorldPhase(),
                dev.elemore.iroh.rt.game.RtSwitch.ON);
        if (refuse != null) {
            failWorld(refuse);
            return false;
        }
        String f = setup(true, true);
        if (f == null)
            return false;
        inputs.geometry.clear();
        inputs.geometryVersion++;
        inputs.depthTo = DebugSwitches.on(PackSwitches.FULL_DEPTH_BACK) ? hDepth : -1;
        fillSkip(true, f);
        // PACK-FULL (T-2 I-9: barriers + clears + buffer->image copies inside Iroh's render pass at every resize): lc.begin
        // REBUILDS the executor on a size / folder change, and prepare() creates every pack image (creation barrier +
        // zeroing clear) and uploads its textures into the frame command buffer. It ran here with Iroh's main pass bound.
        // Now the rebuild, the world pass (re)creation and segment 0 share ONE endPassRunResume (no extra pass break).
        int present = DebugSwitches.on(PackSwitches.PRESENT) ? hColour : -1;
        boolean subst = DebugSwitches.on(PackSwitches.WORLD_SUBSTITUTE);
        int[] res = {0}; // 0 ok, 1 lifecycle refused, 2 world pass failed (reason in why[0]), 3 segment failed
        String[] why = {null};
        VulkanImage.endPassRunResume(() -> {
            FrameExecutor ex = lc.begin(inputs, f, present, skip);
            if (ex == null) {
                res[0] = 1;
                return;
            }
            if (world == null || world.executor() != ex || subst != worldSubst) {
                try {
                    dev.elemore.iroh.diag.FeatureFailures.inject("worldPass"); // INT-3 test seam
                    if (worldGame == null)
                        worldGame = new WorldPassGame(gpu);
                    if (world != null) // PACK-FULL: the old world pass's imported draw textures are forgotten, not kept per rebuild
                        world.forgetTextures();
                    world = new WorldPass(ex, gpu, worldGame, worldGame, SubstitutionHook.rasterMods(), subst);
                } catch (RuntimeException e) {
                    res[0] = 2;
                    why[0] = e.getClass().getSimpleName() + ": " + e.getMessage();
                    return;
                }
                worldSubst = subst;
            }
            ex.rasterOver = hColour;
            WorldPass w0 = world;
            if (!lc.segment(() -> {
                prepImported();
                w0.begin(inputs);
            }))
                res[0] = 3;
        });
        if (res[0] == 1) {
            if (lc.state() == PackLifecycle.State.FAILED)
                fail(lc.failure);
            return false;
        }
        if (res[0] == 2) {
            failWorld(why[0]);
            return false;
        }
        WorldPass w = world;
        if (res[0] == 3) {
            failWorld(lc.failure != null ? lc.failure : "begin failed");
            return false;
        }
        if (w.substitutedBinds == 0 && w.executor().substitutedPipelines() == 0 && subst) { // first frame of this executor
            long t0 = System.nanoTime();
            int n = w.warm();
            log("world pass: " + n + " pipeline permutations warmed in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        }
        worldFrame = true;
        SubstitutionHook.active = w;
        Substitution.setBit(Substitution.WORLD, true);
        return true;
    }

    /** AFTER_SETUP: the sky is drawn; a raster sky (not substituted) goes into colortex0, then the scene colour is cleared transparent */
    void worldAfterSky() {
        if (!worldFrame)
            return;
        WorldPass w = world;
        boolean skySubst = w.perClass.containsKey("sky_basic") && w.perClass.get("sky_basic")[0] > 0;
        VulkanImage.endPassRunResume(() -> lc.segment(() -> {
            prepImported();
            w.afterSky();
            if (!skySubst && DebugSwitches.on(PackSwitches.FULL_RASTER_SKY))
                w.executor().blitIn(hColour, "colortex0");
            w.executor().clearExternal(hColour, TRANSPARENT); // raster draws of the world pass land over transparent black
        }));
    }

    /** BEFORE_ENTITIES: terrain lists, then shadow .. gbuffers-solid (captures held), depthtex0 back to the scene depth */
    void worldOpaque() {
        if (!worldFrame)
            return;
        collectTerrain(); // also decides shadow-map reuse for this frame (executor.reuseShadow)
        WorldPass w = world;
        boolean back = DebugSwitches.on(PackSwitches.FULL_DEPTH_BACK);
        VulkanImage.endPassRunResume(() -> lc.segment(() -> {
            prepImported();
            w.opaque(inputs);
            if (back)
                w.executor().blitOut("depthtex0", hDepth); // raster remainder draws occlude against terrain
        }));
        checkLc();
    }

    /** the first translucent terrain layer: held captures, deferred, gbuffers-translucent; later draws use translucent programs */
    void worldTranslucent() {
        if (!worldFrame || world.phase() != WorldPass.Phase.OPAQUE)
            return;
        WorldPass w = world;
        VulkanImage.endPassRunResume(() -> lc.segment(() -> {
            prepImported();
            w.translucent(inputs);
        }));
        checkLc();
    }

    /** END: composite, final, raster-over, present; hooks off */
    void worldEndFrame() {
        if (!worldFrame)
            return;
        WorldPass w = world;
        VulkanImage.endPassRunResume(() -> lc.segment(() -> {
            prepImported();
            w.end(inputs);
        }));
        worldFrame = false;
        SubstitutionHook.active = null;
        Substitution.setBit(Substitution.WORLD, false);
        DrawIds.weather = false;
        DrawIds.reset();
        stats.cpuNs += System.nanoTime() - cpu0;
        stats.frames++;
        stats.maybeLog(true);
        long now = System.nanoTime();
        if (now - worldLogNs > 2_000_000_000L) {
            worldLogNs = now;
            DebugSwitches.publish(PackSwitches.WORLD_REPORT, w.report());
        }
        checkLc();
        if (w.failure() != null)
            failWorld(w.failure());
    }

    boolean worldFrameActive() {
        return worldFrame;
    }

    private void checkLc() {
        if (lc != null && lc.state() == PackLifecycle.State.FAILED) {
            worldFrame = false;
            SubstitutionHook.active = null;
            Substitution.setBit(Substitution.WORLD, false);
            fail(lc.failure);
        }
    }

    /** degraded mode: the world pass is off for the session, full mode continues terrain-only (P3-6 order); ONE log line */
    private void failWorld(String why) {
        if (worldFailed != null)
            return;
        worldFailed = why;
        if (world != null)
            world.abort();
        worldFrame = false;
        SubstitutionHook.active = null;
        Substitution.setBit(Substitution.WORLD, false);
        // INT-3: ONE line in the feature-failure format (grep "Iroh: .* failed (" finds it; listed in failures{})
        dev.elemore.iroh.diag.FeatureFailures.record("worldPass", new IllegalStateException(why),
                "world pass OFF for this session, terrain-only full mode continues");
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
     * PACK-FULL: the block atlas (imported, sampled by the pack's terrain and substituted programs) in a sampling
     * layout before a segment records descriptors naming it: an atlas upload earlier in the frame leaves it in
     * TRANSFER_DST (compare-and-skip when it is already SHADER_READ_ONLY). Segments run inside endPassRunResume with the
     * world pass's attachments closed: outside every render pass.
     */
    private void prepImported() {
        if (gpu != null && hAtlas >= 0)
            gpu.prepareSample(hAtlas);
    }

    private void fail(String why) {
        failed = true;
        worldFrame = false;
        SubstitutionHook.active = null;
        Substitution.setBit(Substitution.WORLD, false);
        PackRuntime.deactivate();
        log("pack path OFF for this session: " + why);
        if (lc != null)
            lc.disable();
    }

    void unload() {
        if (world != null) {
            world.abort();
            world.forgetTextures();
        }
        world = null;
        worldFrame = false;
        SubstitutionHook.active = null;
        Substitution.setBit(Substitution.WORLD, false);
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
        // same VulkanImage) and WorldPassGame imported draw textures into the OLD backend
        hAtlas = -1;
        importedAtlas = null;
        worldGame = null;
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
