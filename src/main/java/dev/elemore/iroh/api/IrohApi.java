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
     * 1 = P1-6b (2026-11-20). 2 = P1-10: {@link #registerShadeHook}. 3 = P1-13a: {@link IrohFrame}, {@link IrohSky},
     * {@link IrohDraw}, {@link IrohShaders}, {@link IrohTextures}, {@link #limits()}, {@link #heldLightSource}.
     * 4 = card V-M1: {@link IrohBuffers}, {@link IrohCompute}, {@link IrohTargets}, {@link IrohDraw#multiDrawIndirectCount},
     * {@link FrameStage#BEFORE_MAIN_PASS}, {@link #fogState()}, {@link #isSectionBuilt}, {@link #addSectionListener},
     * {@link IrohTextures#blockAtlas()} / {@link IrohTextures#lightmap()}.
     */
    public static final int API_VERSION = 4;

    /** Minor version within {@link #API_VERSION} (additions only). 3.1 = P1-13a follow-up: FrameStage.OVERLAY, FrameContext.guiGraphics(). */
    public static final int API_MINOR = 0;

    private static final Set<Capability> CAPABILITIES = Collections.unmodifiableSet(
            EnumSet.of(Capability.SECTION_VISIBILITY, Capability.FACE_CULL_VETO, Capability.LEAF_CULLING,
                    Capability.FRAME_HOOKS, Capability.SKY_HOOKS, Capability.IMMEDIATE_DRAW, Capability.OWNER_SHADERS,
                    Capability.DYNAMIC_TEXTURES));

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

    /** The minor api version of the running Iroh (api 3.1+; absent before, so look it up reflectively). Any thread. */
    public static int apiMinor() {
        return API_MINOR;
    }

    /** Iroh's mod version string, for logs. Any thread. */
    public static String version() {
        return FabricLoader.getInstance().getModContainer("iroh")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }

    /** What this build actually does (unmodifiable). Any thread, no allocation. */
    public static Set<Capability> capabilities() {
        if (!dev.elemore.iroh.gpu.game.GpuLifecycle.available()) return CAPABILITIES;
        EnumSet<Capability> s = EnumSet.copyOf(CAPABILITIES); // api 4: once Iroh's device is up
        s.add(Capability.GPU_BUFFERS);
        s.add(Capability.COMPUTE);
        s.add(Capability.RENDER_TARGETS);
        if (net.vulkanmod.vulkan.device.IndirectDrawCaps.api4IndirectAllowed()) // P2-3 review: firstInstance + multiDrawIndirect enabled
            s.add(Capability.MULTI_DRAW_INDIRECT_COUNT);
        return Collections.unmodifiableSet(s);
    }

    // ------------------------------------------------------------------------------------------------- api 4

    /**
     * The fog the terrain was drawn with this frame (after vanilla, mod fog and Iroh's distance-fog option): give LODs
     * and far geometry exactly this fog. Render thread; the previous frame's values before terrain is drawn.
     */
    public static FogState fogState() {
        return dev.elemore.iroh.apiimpl.Sections.fog();
    }

    /** Whether Iroh's chunk renderer has a built mesh for this 16^3 section (section coordinates). Render thread. */
    public static boolean isSectionBuilt(int sectionX, int sectionY, int sectionZ) {
        return dev.elemore.iroh.apiimpl.Sections.isBuilt(sectionX, sectionY, sectionZ);
    }

    /** Built / reset events per section (the hand-off from real chunks to LODs). close() removes it. */
    public static IrohFrame.Registration addSectionListener(SectionListener listener) {
        return dev.elemore.iroh.apiimpl.Sections.add(listener);
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

    /**
     * Adds a shade hook (see {@link ShadeHook}): lets a mod move a quad's vertices for the light calculation only, in the
     * terrain mesher (Polaris lights its sloped quads at the block face this way). Takes effect for sections meshed
     * from now on, triggers no rebuild; same instance twice is a no-op. Any thread. Since API 2.
     */
    public static void registerShadeHook(ShadeHook hook) {
        dev.elemore.iroh.apiimpl.ShadeHooks.register(hook);
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

    // ------------------------------------------------------------------------------------------------- api 3

    /**
     * The device's limits (api 3). Render thread after the window exists; null before the Vulkan device is up. Cheap
     * (fields of a cached record).
     */
    public static Limits limits() {
        return dev.elemore.iroh.apiimpl.DeviceLimits.get();
    }

    /**
     * Held-light read path (api 3): the stack whose light {@code hand} gives off after every registered
     * {@link HeldLightProvider} ran. What the PACK / RT held-light code will call; queryable now so owner mods can test
     * their providers. Render thread. Cost: one call per provider.
     */
    public static net.minecraft.world.item.ItemStack heldLightSource(net.minecraft.world.entity.player.Player player,
                                                                       net.minecraft.world.InteractionHand hand) {
        return HeldLights.lightSource(player, hand, player.getItemInHand(hand));
    }

    /** Number of registered held-light providers. Any thread. */
    public static int heldLightProviderCount() {
        return HeldLights.count();
    }
}
