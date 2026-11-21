package dev.elemore.iroh.api;

import dev.elemore.iroh.apiimpl.FaceCullHooks;
import dev.elemore.iroh.apiimpl.HeldLights;
import dev.elemore.iroh.apiimpl.LeafCulling;
import dev.elemore.iroh.apiimpl.Visibility;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Iroh's public API (P1-6b). What our own mods asked Sodium and Iris for, answered by Iroh. Static methods only.
 * <p>
 * Threading and cost are stated per method. "Any thread" methods are lock-free reads.
 * See {@code knowledge/api.md} in the Iroh skill for which mod uses what.
 */
public final class IrohApi {
    /**
     * Version of this API. Inlined into callers at compile time, so use {@link #apiVersion()} to ask the running Iroh.
     * 1 = P1-6b (2026-11-20).
     */
    public static final int API_VERSION = 1;

    private static final Set<Capability> CAPABILITIES = Collections.unmodifiableSet(
            EnumSet.of(Capability.SECTION_VISIBILITY, Capability.FACE_CULL_VETO, Capability.LEAF_CULLING));

    private IrohApi() {
    }

    // ------------------------------------------------------------------------------------------------- identity

    /** Always true: if this class resolves, Iroh is installed. Any thread. */
    public static boolean isLoaded() {
        return true;
    }

    /** The API version of the running Iroh (compare with the {@link #API_VERSION} you compiled against). Any thread. */
    public static int apiVersion() {
        return API_VERSION;
    }

    /** Iroh's mod version string, for logs. Any thread. */
    public static String version() {
        return FabricLoader.getInstance().getModContainer("iroh")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }

    /** What this build actually does (unmodifiable). Any thread, no allocation. */
    public static Set<Capability> capabilities() {
        return CAPABILITIES;
    }

    /** The render path drawing the world. RASTER until Phase 3 adds PACK and Phase 5 adds RT. Any thread. */
    public static RenderPath renderPath() {
        return RenderPath.RASTER;
    }

    // ------------------------------------------------------------------------------------------------- shader packs

    /** Whether a shader pack is loaded and drawing (Iris {@code isShaderPackInUse}). False until Phase 3. Any thread. */
    public static boolean isShaderPackInUse() {
        return false;
    }

    /** The loaded pack's name (Iris {@code getCurrentPackName}), "" when none. Never null. Any thread. */
    public static String currentPackName() {
        return "";
    }

    /**
     * Switches shader packs on or off and applies it (Iris {@code setShadersEnabledAndApply}).
     * Render thread. Returns whether the request was accepted; until Phase 3 it does nothing and returns false.
     */
    public static boolean setShadersEnabled(boolean enabled) {
        return false;
    }

    /** True while the shadow map is being drawn (Iris {@code isRenderingShadowPass}). False until Phase 3. Any thread. */
    public static boolean isRenderingShadowPass() {
        return false;
    }

    // ------------------------------------------------------------------------------------------------- visibility

    /**
     * Whether the chunk section at these SECTION coordinates (block >> 4) is in this frame's visible set: reached by the
     * occlusion graph and inside the frustum at the last graph update (the camera's own section included). What
     * Sodium's {@code SodiumWorldRenderer.isSectionVisible} answered.
     * <p>
     * Render thread (that is where the graph is updated); from any other thread, or with no world renderer, the answer
     * is a conservative true. Cost: O(1), two array reads, no allocation: fine per block entity per frame.
     */
    public static boolean isSectionVisible(int sectionX, int sectionY, int sectionZ) {
        return Visibility.isSectionVisible(sectionX, sectionY, sectionZ);
    }

    /**
     * Whether any section touched by this world-space box is visible (see {@link #isSectionVisible}). Box in blocks,
     * max inclusive of the block it lies in. Sodium's {@code isBoxVisible}. Boxes outside the build height, or spanning
     * more than 4x4x4 sections, answer true. Render thread, else true. Cost: one {@link #isSectionVisible} per section
     * touched (1 to 8 for a block-sized box), no allocation.
     */
    public static boolean isBoxVisible(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return Visibility.isBoxVisible(minX, minY, minZ, maxX, maxY, maxZ);
    }

    // ------------------------------------------------------------------------------------------------- meshing

    /**
     * Adds a face-cull veto to the chunk mesher (see {@link FaceCullVeto} for the rules). Takes effect for sections
     * meshed from now on; already-built sections keep their mesh until they rebuild, so register during client init.
     * Registering triggers no rebuild. Registering the same instance twice is a no-op. Any thread.
     */
    public static void registerFaceCullVeto(FaceCullVeto veto) {
        FaceCullHooks.register(veto);
    }

    /** Removes a veto added by {@link #registerFaceCullVeto}. Same rebuild rule. Any thread. */
    public static void unregisterFaceCullVeto(FaceCullVeto veto) {
        FaceCullHooks.unregister(veto);
    }

    /** How leaves are being drawn: option leavesQuality (FANCY / FAST, DEFAULT follows the graphics mode). Any thread. */
    public static LeavesQuality leavesQuality() {
        return LeafCulling.leavesFancy() ? LeavesQuality.FANCY : LeavesQuality.FAST;
    }

    /** Whether Iroh's built-in leaf culling option is on. Any thread. */
    public static boolean isLeafCullingEnabled() {
        return LeafCulling.enabled();
    }

    /**
     * Iroh's leaf-culling rule (the Cull Fewer Leaves behaviour, option {@code leafCulling}) for a leaf block whose kind
     * Iroh does not recognise itself (Iroh applies it to every {@code LeavesBlock} already). Hides the face when the
     * next {@code depth} blocks out of it are all leaves (depth 2 on fancy leaves by default, 1 on fast); when only some
     * are, it hides the face for the configured share of positions, chosen by a fixed per-position random, so the
     * pattern is stable across rebuilds. False when the option is off.
     * <p>
     * For use inside a {@link FaceCullVeto}: worker threads, reads {@code level} only. Cost: up to {@code depth} block
     * reads plus {@code isLeaf} calls.
     *
     * @param isLeaf what counts as leaves when looking through the canopy
     */
    public static boolean cullLeafFace(BlockGetter level, BlockPos pos, Direction face, Predicate<BlockState> isLeaf) {
        return LeafCulling.enabled() && LeafCulling.cullFace(level, pos, face, isLeaf, LeafCulling.depthNow());
    }

    /**
     * True when {@code view} is the snapshot Iroh's chunk mesher hands to block models (the role Sodium's
     * {@code LevelSlice} played for models that only deform terrain, never items, entities or LOD bakes). Any thread.
     */
    public static boolean isChunkMeshView(BlockGetter view) {
        return view instanceof net.vulkanmod.render.chunk.build.RenderRegion;
    }

    // ------------------------------------------------------------------------------------------------- held light

    /**
     * Adds a held-light provider (see {@link HeldLightProvider}). Takes effect on the PACK and RT paths only; on RASTER
     * it is stored and ignored, which is the OpenGL pack's behaviour with shaders off. Same instance twice is a no-op.
     * Any thread.
     */
    public static void registerHeldLightProvider(HeldLightProvider provider) {
        HeldLights.register(provider);
    }
}
