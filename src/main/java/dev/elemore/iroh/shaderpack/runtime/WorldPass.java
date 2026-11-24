package dev.elemore.iroh.shaderpack.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P3-11 (S) the in-game world pass, free of game and Vulkan classes so the whole frame order is tested on the recording
 * backend (worldPassTest). While the pack is in full mode with the world pass on, vanilla's world rendering IS the
 * gbuffers stage: a draw whose class the pack serves is drawn by the pack program of that class (compiled for the
 * draw's vanilla vertex layout and render state) into that program's DRAWBUFFERS targets + depthtex0, instead of the
 * translated vanilla core shader into the scene target. Everything else stays raster.
 * <p>Frame order (one executor frame recorded in segments, each outside Iroh's render pass):
 * <ol>
 *   <li>{@link #begin} at WorldRenderEvents.START: clears and inputs (no passes); phase OPAQUE. Sky draws follow and
 *       are substituted into colortex0 (gbuffers_skybasic / skytextured).</li>
 *   <li>{@link #afterSky} at AFTER_SETUP: nothing for the executor (the game side copies a raster sky into colortex0 if
 *       the sky was not substituted, then clears the scene colour to transparent for the raster remainder).</li>
 *   <li>{@link #opaque} at BEFORE_ENTITIES: shadow, shadowcomp, prepare, gbuffers-solid (terrain from Iroh's section
 *       lists); depth captures are HELD. Entities, block entities, crumbling, outline follow, substituted.</li>
 *   <li>{@link #translucent} at the first translucent terrain layer: held captures (depthtex1/2 now include entities),
 *       deferred, gbuffers-translucent (water from the section lists); phase TRANSLUCENT: later draws use the
 *       translucent programs (alt halves) or stay raster when their class has none.</li>
 *   <li>{@link #end} at END: composite, final, raster-over composite (raster draws of the world pass over the pack
 *       output), present into the scene colour; phase OFF. Hand, GUI and HUD then draw raster on top.</li>
 * </ol>
 * Pass switches: the open attachment set changes only when the program's DRAWBUFFERS set changes; every switch back to
 * Iroh's pass resumes it and drops Iroh's bound-pipeline cache and dynamic state (as PassResume does).
 * Degraded mode: a permutation or pass that cannot be built fails the world pass for the session with ONE log line
 * ({@link #failure}); the caller drops to terrain-only full mode.
 */
public final class WorldPass {

    /** Iroh's side of a pass switch (game: Renderer / MainPass; tests: a fake) */
    public interface Host {
        boolean irohPassBound();

        void endIrohPass();

        void resumeIrohPass();

        /** forget the bound pipeline handle (the next Iroh bind must not be skipped) */
        void invalidateBoundState();

        /** viewport, scissor, depth bias, line width */
        void reapplyDynamicState();
    }

    /** what a substituted draw takes from the renderer at draw time (game: VRenderSystem, VTextureSelector, DrawIds) */
    public interface DrawSource {
        void modelView(double[] out16);

        void projection(double[] out16);

        /** the renderer's image bound to sampler 0, null = none */
        Object texture();

        /** a value that changes when {@code tex} is recreated in place (its view handle) */
        long textureKey(Object tex);

        /** backend handle for a renderer image (imported once per key) */
        int importTexture(Object tex);

        /** per-draw ids into reused arrays: entityId, blockEntityId, currentRenderedItemId, entityColor */
        void ids(Map<String, double[]> out);
    }

    public enum Phase { OFF, OPAQUE, TRANSLUCENT }

    /** VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST: the pack pipelines are triangle lists (vanilla QUADS / LINES-as-quads are indexed triangles) */
    public static final int TRIANGLE_LIST = 3;

    private final FrameExecutor ex;
    private final GpuBackend gpu;
    private final Host host;
    private final DrawSource src;
    private final Set<String> rasterMods;
    private final boolean substitute;
    private PackFrameInputs in;
    private Phase phase = Phase.OFF;
    private FrameExecutor.SubstPass open;
    private boolean irohEnded;
    // pending substituted bind (valid until the next bind decision; survives a foreign close: reopened at the draw)
    private FrameExecutor.SubstPass pendSp;
    private int pendPipe = -1;
    private String pendClass;
    private String failure;
    // stats (per frame, published by the caller)
    public int substitutedDraws, substitutedBinds, rasterBinds, passBegins;
    public final Map<String, int[]> perClass = new HashMap<>();
    private final List<int[]> counters = new ArrayList<>();

    private final Map<Object, Decision> decisions = new IdentityHashMap<>();
    private final Map<Object, long[]> textures = new IdentityHashMap<>(); // renderer image -> {handle, key}
    private GpuBackend.Bind[] texBinds = new GpuBackend.Bind[64]; // by backend handle (no boxing per draw)
    private final double[] mv = new double[16], pj = new double[16];
    private final Map<String, double[]> perDraw = new HashMap<>();

    public WorldPass(FrameExecutor ex, GpuBackend gpu, Host host, DrawSource src, Set<String> rasterMods, boolean substitute) {
        this.ex = ex;
        this.gpu = gpu;
        this.host = host;
        this.src = src;
        this.rasterMods = rasterMods;
        this.substitute = substitute;
        perDraw.put("entityId", new double[]{-1});
        perDraw.put("blockEntityId", new double[]{-1});
        perDraw.put("currentRenderedItemId", new double[]{-1});
        perDraw.put("entityColor", new double[4]);
    }

    public FrameExecutor executor() {
        return ex;
    }

    public Phase phase() {
        return phase;
    }

    public boolean attachmentsOpen() {
        return open != null;
    }

    /** null while healthy; the reason once the world pass failed (the caller drops to terrain-only full mode) */
    public String failure() {
        return failure;
    }

    // ------------------------------------------------------------------------------------------------ frame segments

    public void begin(PackFrameInputs in) {
        leave();
        this.in = in;
        substitutedDraws = substitutedBinds = rasterBinds = passBegins = 0;
        for (int i = 0; i < counters.size(); i++) // indexed: no iterator (zero allocation per frame)
            counters.get(i)[0] = 0;
        ex.expectGeometry = true;
        ex.frameBegin(in);
        phase = Phase.OPAQUE;
    }

    /** AFTER_SETUP: the sky is drawn; closes the sky's attachments (the game side then handles a raster sky) */
    public void afterSky() {
        leave();
    }

    public void opaque(PackFrameInputs in) {
        leave();
        this.in = in;
        ex.runStages(in, "begin", "deferred", true);
    }

    public void translucent(PackFrameInputs in) {
        if (phase != Phase.OPAQUE)
            return;
        leave();
        this.in = in;
        ex.runHeldCaptures(in);
        ex.runStages(in, "deferred", "composite", false);
        phase = Phase.TRANSLUCENT;
        pendSp = null;
    }

    public void end(PackFrameInputs in) {
        if (phase == Phase.OFF)
            return;
        leave();
        this.in = in;
        if (phase == Phase.OPAQUE) { // no translucent layer this frame (nothing translucent in view): deferred now
            ex.runHeldCaptures(in);
            ex.runStages(in, "deferred", "composite", false);
        }
        ex.runStages(in, "composite", null, false);
        ex.frameEnd(in);
        phase = Phase.OFF;
        pendSp = null;
    }

    /** a frame abandoned midway (exception, world unload): closes the attachments, nothing else is recorded */
    public void abort() {
        leave();
        phase = Phase.OFF;
        pendSp = null;
    }

    // ------------------------------------------------------------------------------------------------ decision path

    /** one vanilla pipeline object's mapping (computed once per pipeline) */
    static final class Decision {
        String cls, layout;
        String opaquePass, translucentPass;
        String opaqueClass, translucentClass;
        final LongIntMap opaquePipes = new LongIntMap(), translucentPipes = new LongIntMap();
        FrameExecutor.SubstPass opaqueSp, translucentSp;
    }

    /**
     * Renderer.bindGraphicsPipeline's decision for one bind. {@code key} identifies the vanilla pipeline (cache key),
     * {@code shader}/{@code elements} its core shader name and vertex format element names, the rest Iroh's current
     * pipeline state. Returns true when the pack pipeline was bound in its place (the caller skips the vanilla bind).
     */
    public boolean onBind(Object key, String shader, List<String> elements, int topology, boolean depthTest, boolean depthWrite,
                          boolean cull, boolean blend, int srcRgb, int dstRgb, int srcA, int dstA, boolean inWorld) {
        if (!substitute || failure != null || phase == Phase.OFF || !inWorld || topology != TRIANGLE_LIST) {
            raster();
            return false;
        }
        Decision d = decisions.get(key);
        if (d == null) {
            d = decide(shader, elements, inWorld);
            decisions.put(key, d);
        }
        boolean tr = phase == Phase.TRANSLUCENT;
        String pass = tr ? d.translucentPass : d.opaquePass;
        if (pass == null) {
            raster();
            return false;
        }
        long state = (depthTest ? 1 : 0) | (depthWrite ? 2 : 0) | (cull ? 4 : 0)
                | (blend ? 8L | ((long) srcRgb << 8) | ((long) dstRgb << 13) | ((long) srcA << 18) | ((long) dstA << 23) : 0);
        LongIntMap m = tr ? d.translucentPipes : d.opaquePipes;
        int pipe = m.get(state);
        if (pipe == LongIntMap.MISSING) {
            pipe = ex.substitutePipeline(pass, d.layout, state, depthTest, depthWrite, cull,
                    blend ? List.of(factor(srcRgb), factor(dstRgb), factor(srcA), factor(dstA)) : null);
            m.put(state, pipe);
        }
        FrameExecutor.SubstPass sp = tr ? d.translucentSp : d.opaqueSp;
        if (sp == null && pipe >= 0) {
            // PACK-FULL (T-2 I-9: clears / barriers / copies inside Iroh's render pass after every resize): building a
            // SubstPass resolves its bindings and may CREATE images (dummies, geometry textures: creation barrier +
            // zeroing clear + upload). The first bind of a pass after an executor (re)build came here with Iroh's main
            // pass (or another attachment set) open; close everything first (enter() below would anyway).
            if (!oldBindOrderForTest && !ex.substPassBuilt(pass)) // another decision may already have built (and opened) it
                suspend();
            sp = ex.substPass(pass, in);
            if (tr)
                d.translucentSp = sp;
            else
                d.opaqueSp = sp;
        }
        if (pipe < 0 || sp == null) {
            fail("cannot build " + pass + " for " + shader + " " + d.layout + (pipe < 0 ? " (pipeline)" : " (attachments)"));
            raster();
            return false;
        }
        pendSp = sp;
        pendPipe = pipe;
        pendClass = tr ? d.translucentClass : d.opaqueClass;
        enter(sp);
        substitutedBinds++;
        return true;
    }

    /** Renderer.uploadAndBindUBOs, right before the draw: per-draw uniforms + the pack descriptor sets; true = handled */
    public boolean beforeDraw() {
        if (pendSp == null || failure != null)
            return false;
        Object tex = pendSp.texBindings.length > 0 ? src.texture() : null;
        int texHandle = tex != null ? textureHandle(tex) : -1;
        // PACK-FULL (T-2 I-9: descriptors naming TRANSFER_DST, VUID 04150 / 09600 / submit layout): a renderer texture
        // uploaded this frame is still in its transfer layout. Move it to SHADER_READ_ONLY as the renderer would, and
        // never inside a render pass: close our attachments for it (rare: once per freshly uploaded texture).
        if (texHandle >= 0 && !noSamplePrepForTest && !gpu.sampleReady(texHandle)) {
            suspend();
            gpu.prepareSample(texHandle);
        }
        if (open != pendSp)
            enter(pendSp); // a foreign recording closed the attachments between the bind and this draw
        src.modelView(mv);
        src.projection(pj);
        src.ids(perDraw);
        if (texHandle >= 0) {
            int h = texHandle;
            if (h >= texBinds.length)
                texBinds = java.util.Arrays.copyOf(texBinds, Math.max(h + 1, texBinds.length * 2));
            GpuBackend.Bind b = texBinds[h];
            if (b == null)
                texBinds[h] = b = GpuBackend.Bind.image(h, "NEAREST");
            for (int binding : pendSp.texBindings)
                pendSp.set0.put(binding, b);
        }
        byte[] ub = ex.substUniforms(pendSp, in, pendClass, mv, pj, perDraw);
        gpu.bindExternalDraw(pendSp.passId, pendPipe, pendSp.set0, pendSp.set1, ub);
        substitutedDraws++;
        int[] c = perClass.get(pendClass);
        if (c == null) {
            perClass.put(pendClass, c = new int[1]);
            counters.add(c);
        }
        c[0]++;
        return true;
    }

    /** the backend handle of a renderer texture (imported once per key; re-imported when recreated in place) */
    private int textureHandle(Object tex) {
        long k = src.textureKey(tex);
        long[] t = textures.get(tex);
        if (t == null || t[1] != k) {
            if (t != null) { // recreated in place: the old view is gone
                gpu.freeImage((int) t[0]);
                if (t[0] < texBinds.length)
                    texBinds[(int) t[0]] = null;
            }
            t = new long[]{src.importTexture(tex), k};
            textures.put(tex, t);
        }
        return (int) t[0];
    }

    /** PACK-FULL negative controls (packFullVkRepro -Pmode=old): the pre-fix bind order / no sample preparation */
    static boolean oldBindOrderForTest, noSamplePrepForTest;

    /**
     * PACK-FULL: outside EVERY render pass from here on: our attachments closed (without resuming Iroh's pass) and
     * Iroh's pass ended. The next {@link #enter} opens attachments directly; {@link #leave} resumes Iroh's pass.
     */
    private void suspend() {
        if (open != null) {
            gpu.endAttachments();
            ex.externalEnded();
            open = null;
        }
        if (!irohEnded && host.irohPassBound()) {
            host.endIrohPass();
            irohEnded = true;
        }
    }

    /** a recording that is not ours is about to begin (Iroh pass begin/end, endPassRunResume): close our attachments */
    public void closeForForeign() {
        leave();
    }

    /** forget imported renderer textures (world unload / reload); handles are only forgotten by the backend */
    public void forgetTextures() {
        for (long[] t : textures.values())
            gpu.freeImage((int) t[0]); // imported: only forgotten by the backend
        textures.clear();
        java.util.Arrays.fill(texBinds, null);
    }

    private void raster() {
        rasterBinds++;
        pendSp = null;
        leave();
    }

    private void enter(FrameExecutor.SubstPass sp) {
        if (open == sp)
            return;
        if (open != null) {
            gpu.endAttachments();
            ex.externalEnded();
            open = null;
        } else if (!irohEnded && host.irohPassBound()) {
            host.endIrohPass();
            irohEnded = true;
        }
        // B-3B: the attachment set is a site of the executor's compiled schedule (a region between its segments): one
        // barrier for its attachment writes and bound pack images, and the executor's later passes see its writes
        // (was: one full barrier per attachment before and after every set)
        ex.external(sp);
        gpu.beginAttachments(sp.passId, sp.colour, sp.depth, sp.viewport);
        open = sp;
        passBegins++;
    }

    private void leave() {
        if (open == null && !irohEnded) // PACK-FULL: suspend() may have ended Iroh's pass with no attachments open
            return;
        if (open != null) {
            gpu.endAttachments();
            ex.externalEnded();
            open = null;
        }
        if (irohEnded) {
            host.resumeIrohPass();
            irohEnded = false;
        }
        host.invalidateBoundState();
        host.reapplyDynamicState();
    }

    private void fail(String why) {
        if (failure == null)
            failure = why;
    }

    /** an exception in a renderer hook: fail, close our attachments, forget the pending bind (the caller draws raster) */
    public void failNow(String why) {
        fail(why);
        pendSp = null;
        try {
            leave();
        } catch (RuntimeException ignored) {
            open = null;
        }
    }

    private Decision decide(String shader, List<String> elements, boolean inWorld) {
        Decision d = new Decision();
        if (Substitution.rasterOnly(shader, inWorld, rasterMods))
            return d;
        d.cls = Substitution.classFor(shader, elements);
        d.layout = elements == null ? null : Substitution.layout(elements);
        if (d.cls == null || d.layout == null)
            return d;
        d.opaqueClass = opaqueClass(d.cls);
        d.translucentClass = translucentClass(d.cls);
        d.opaquePass = d.opaqueClass == null ? null : ex.passFor(d.opaqueClass, "gbuffers-solid");
        d.translucentPass = d.translucentClass == null ? null : ex.passFor(d.translucentClass, "gbuffers-translucent");
        if (d.opaquePass == null && d.opaqueClass != null && FALLBACK.containsKey(d.opaqueClass)) {
            d.opaqueClass = FALLBACK.get(d.opaqueClass);
            d.opaquePass = ex.passFor(d.opaqueClass, "gbuffers-solid");
        }
        if (d.translucentPass == null && d.translucentClass != null && FALLBACK.containsKey(d.translucentClass)) {
            d.translucentClass = FALLBACK.get(d.translucentClass);
            d.translucentPass = ex.passFor(d.translucentClass, "gbuffers-translucent");
        }
        return d;
    }

    /**
     * Class drawn BEFORE deferred (opaque programs, main halves). UNVERIFIED: check in G3b. A translucent vanilla type
     * flushed before translucent terrain (vanilla endBatch of translucent sheets) is drawn with its opaque sibling (Iris'
     * fallback chain); classes with only a translucent program stay raster before deferred.
     */
    static String opaqueClass(String cls) {
        return switch (cls) {
            case "entities_translucent" -> "entities";
            case "block_entities_translucent" -> "block_entities";
            case "particles_translucent" -> "particles_opaque";
            case "weather", "world_border", "hand_solid", "hand_translucent" -> null;
            default -> cls;
        };
    }

    /**
     * Class drawn AFTER deferred (translucent programs, the halves after the deferred flip). UNVERIFIED: check in G3b.
     * An opaque program's output after deferred would be lost (deferred already consumed it), so classes without a
     * translucent sibling stay raster here (drawn over the pack output by the raster-over composite).
     */
    static String translucentClass(String cls) {
        return switch (cls) {
            case "entities", "entities_translucent" -> "entities_translucent";
            case "block_entities", "block_entities_translucent" -> "block_entities_translucent";
            case "particles_opaque", "particles_translucent" -> "particles_translucent";
            case "weather" -> "weather";
            case "world_border" -> "world_border";
            case "clouds" -> "clouds";
            default -> null;
        };
    }

    /** Iris-style fallback when the pack has no program for a class (UNVERIFIED: check in G3b) */
    static final Map<String, String> FALLBACK = Map.of(
            "clouds", "particles_opaque",
            "spider_eyes", "entities",
            "entities_glowing", "entities",
            "lightning", "entities",
            "beacon_beam", "entities",
            "damaged_block", "block_entities",
            "outline", "particles_opaque");

    // ------------------------------------------------------------------------------------------------ warm-up

    static final List<String> NEW_ENTITY = List.of("Position", "Color", "UV0", "UV1", "UV2", "Normal"),
            PARTICLE = List.of("Position", "UV0", "Color", "UV2"), POSITION = List.of("Position"),
            POSITION_TEX = List.of("Position", "UV0"), BLOCK = List.of("Position", "Color", "UV0", "UV2", "Normal");
    /** translucent blend (SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ONE_MINUS_SRC_ALPHA) as VK factor numbers */
    static final int[] TRANSLUCENT = {6, 7, 1, 7};

    /**
     * The vanilla render types warmed at pack load: {shader, vertex elements, depthTest, depthWrite, cull, blend}.
     * UNVERIFIED: check in G3b (replace with the G3b0 observe report's list: the states vanilla really binds).
     */
    static final Object[][] WARM = {
            {"rendertype_entity_solid", NEW_ENTITY, true, true, true, null},
            {"rendertype_entity_cutout", NEW_ENTITY, true, true, true, null},
            {"rendertype_entity_cutout_no_cull", NEW_ENTITY, true, true, false, null},
            {"rendertype_entity_cutout_no_cull", NEW_ENTITY, true, true, true, null},
            {"rendertype_entity_translucent", NEW_ENTITY, true, true, false, TRANSLUCENT},
            {"rendertype_entity_translucent_cull", NEW_ENTITY, true, true, true, TRANSLUCENT},
            {"rendertype_item_entity_translucent_cull", NEW_ENTITY, true, true, true, TRANSLUCENT},
            {"rendertype_entity_shadow", NEW_ENTITY, true, false, true, TRANSLUCENT},
            {"rendertype_armor_cutout_no_cull", NEW_ENTITY, true, true, false, null},
            {"particle", PARTICLE, true, true, false, TRANSLUCENT},
            {"particle", PARTICLE, true, true, false, null},
            {"position", POSITION, false, false, false, null},
            {"position", POSITION, false, false, false, TRANSLUCENT},
            {"position_tex", POSITION_TEX, false, false, false, new int[]{6, 1, 1, 0}},
            {"rendertype_crumbling", BLOCK, true, false, true, new int[]{4, 2, 1, 0}},
            {"iroh:weather", PARTICLE, true, false, false, TRANSLUCENT}, // rain/snow: depthMask false outside Fabulous, blend, no cull
    };

    /** compiles the WARM permutations for both phases now (pack load / executor rebuild); returns how many exist */
    public int warm() {
        if (!substitute)
            return 0;
        for (Object[] w : WARM) {
            @SuppressWarnings("unchecked") List<String> el = (List<String>) w[1];
            Decision d = decide((String) w[0], el, true);
            boolean dt = (Boolean) w[2], dw = (Boolean) w[3], cull = (Boolean) w[4];
            int[] b = (int[]) w[5];
            long state = (dt ? 1 : 0) | (dw ? 2 : 0) | (cull ? 4 : 0)
                    | (b != null ? 8L | ((long) b[0] << 8) | ((long) b[1] << 13) | ((long) b[2] << 18) | ((long) b[3] << 23) : 0);
            List<String> names = b == null ? null : List.of(factor(b[0]), factor(b[1]), factor(b[2]), factor(b[3]));
            for (String pass : new String[]{d.opaquePass, d.translucentPass})
                if (pass != null)
                    ex.substitutePipeline(pass, d.layout, state, dt, dw, cull, names);
        }
        return ex.substitutedPipelines();
    }

    /** VK blend factor number -> the name PipelineDesc blends use */
    static String factor(int vk) {
        return switch (vk) {
            case 0 -> "ZERO";
            case 1 -> "ONE";
            case 2 -> "SRC_COLOR";
            case 3 -> "ONE_MINUS_SRC_COLOR";
            case 4 -> "DST_COLOR";
            case 5 -> "ONE_MINUS_DST_COLOR";
            case 6 -> "SRC_ALPHA";
            case 7 -> "ONE_MINUS_SRC_ALPHA";
            case 8 -> "DST_ALPHA";
            case 9 -> "ONE_MINUS_DST_ALPHA";
            case 14 -> "SRC_ALPHA_SATURATE";
            default -> "ONE";
        };
    }

    /** one line per class: substituted draws this frame */
    public String report() {
        List<String> l = new ArrayList<>();
        perClass.forEach((k, v) -> l.add(k + " " + v[0]));
        java.util.Collections.sort(l);
        return "binds substituted " + substitutedBinds + " raster " + rasterBinds + ", draws " + substitutedDraws + ", attachment switches "
                + passBegins + ", pipelines " + ex.substitutedPipelines() + (l.isEmpty() ? "" : ", " + String.join(", ", l))
                + (failure != null ? ", FAILED: " + failure : "");
    }

    /** open-addressing long -> int map (no boxing on the per-bind path) */
    static final class LongIntMap {
        static final int MISSING = Integer.MIN_VALUE;
        private long[] keys = new long[8];
        private int[] vals = new int[8];
        private boolean[] used = new boolean[8];
        private int size;

        int get(long k) {
            int mask = keys.length - 1;
            for (int i = (int) (mix(k) & mask); used[i]; i = (i + 1) & mask)
                if (keys[i] == k)
                    return vals[i];
            return MISSING;
        }

        void put(long k, int v) {
            if ((size + 1) * 2 > keys.length)
                grow();
            int mask = keys.length - 1;
            int i = (int) (mix(k) & mask);
            while (used[i] && keys[i] != k)
                i = (i + 1) & mask;
            if (!used[i])
                size++;
            used[i] = true;
            keys[i] = k;
            vals[i] = v;
        }

        private void grow() {
            long[] ok = keys;
            int[] ov = vals;
            boolean[] ou = used;
            keys = new long[ok.length * 2];
            vals = new int[ok.length * 2];
            used = new boolean[ok.length * 2];
            size = 0;
            for (int i = 0; i < ok.length; i++)
                if (ou[i])
                    put(ok[i], ov[i]);
        }

        private static long mix(long k) {
            k ^= k >>> 33;
            k *= 0xff51afd7ed558ccdL;
            return k ^ (k >>> 33);
        }
    }
}
