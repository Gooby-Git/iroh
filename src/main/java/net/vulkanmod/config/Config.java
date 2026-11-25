package net.vulkanmod.config;

import dev.elemore.iroh.config.IrohOptions;
import net.vulkanmod.config.video.VideoModeSet;

/**
 * Live values of Iroh's options, read directly by the renderer ({@code Initializer.CONFIG.field}).
 * The registry that owns defaults, ranges, serialisation and the screen is
 * {@link IrohOptions}; the file is {@code config/iroh.json}. Field names of the original VulkanMod options are kept
 * because other code reads them. Constructing a Config touches no GLFW or Minecraft class; {@code videoMode} is
 * filled in by Initializer once the monitor is known.
 */
public class Config {
    // ---- ported VulkanMod options (defaults unchanged) ----
    public VideoModeSet.VideoMode videoMode = null;
    public int windowMode = 0;

    public int advCulling = 2;
    public boolean indirectDraw = true;

    public boolean uniqueOpaqueLayer = true;
    public boolean entityCulling = true;
    public int device = -1;
    /** rc8: Iroh's own device-memory cap in MiB; 0 = automatic (min(60% of the largest VRAM heap, 8 GiB)). */
    public int maxDeviceMemoryMiB = 0;

    public int ambientOcclusion = 1;
    public int frameQueueSize = 2;
    public int builderThreads = 0;

    public boolean backFaceCulling = true;

    // ---- P2-7b: GPU terrain culling techniques (Culling page). Each its own option, all off by default until an in-game
    // A/B shows a gain with 0 missing sections; active only with the world terrain arena on an indirect-draw device.
    /** compute frustum test per command against the frame's MVP (sections the async walk kept from the last frame) */
    public boolean cullGpuFrustum = false;
    /** compute horizon test: hidden below opaque section columns near the camera */
    public boolean cullGpuHorizon = false;
    /** compute screen-size test: sections whose box covers no pixel sample */
    public boolean cullGpuScreenSize = false;
    /** compute facing test: per-facing buckets against the section's geometry box (replaces the CPU facing mask) */
    public boolean cullGpuFacing = false;
    /**
     * P2-7c: two-pass Hi-Z occlusion of the opaque layers (previous frame's depth pyramid, then a retest of what only that
     * culled against this frame's pyramid: nothing visible goes missing on fast turns)
     */
    public boolean cullGpuHiZ = false;
    /** P2-7c: the translucent layer through the GPU cull with Hi-Z against the depth it is drawn against (order kept) */
    public boolean cullGpuHiZTranslucent = false;

    // ---- options the pack relied on (Sodium / Sodium Extra / Iris / Cull Fewer Leaves), see IrohOptions ----
    /** DEFAULT follows the vanilla Graphics setting, FAST / FANCY override it. */
    public String leavesQuality = "DEFAULT";
    public boolean enableVignette = true;
    // ---- Telemetry tab (card TEL-1; replaces the old showFps flag, which iroh.json migrates, see IrohOptions) ----
    /** Master switch of the on-screen overlay. */
    public boolean telemetryOverlay = false;
    public boolean telFps = true, telAvg = true, telLow1 = true, telLow01 = false, telFrameTime = false, telGraph = false;
    public boolean telGpuBusy = false, telVram = false, telRam = false, telPing = false, telTps = false, telChunks = false;
    public boolean telEntities = false, telRenderDist = false, telFeatures = false, telGenFps = false;
    /** TOP_LEFT / TOP_RIGHT / BOTTOM_LEFT / BOTTOM_RIGHT */
    public String telPosition = "TOP_LEFT";
    /** WHITE / GREEN / CYAN / YELLOW / ORANGE / PINK */
    public String telColor = "WHITE";
    /** F9 / F7 / F12 / PAUSE / SCROLL_LOCK / INSERT / HOME / END / NONE */
    public String telHotkey = "F9";
    /** 50..200 percent */
    public int telScale = 100;
    /** 10..100 percent */
    public int telOpacity = 100;
    /** -1 terrain fog off, 0 vanilla, n > 0 chunks (not verified, treated as vanilla). */
    public int fogDistanceChunks = 0;
    /** Iroh leaf culling (Cull Fewer Leaves behaviour) on/off. Off unless migrated from cull-fewer-leaves.toml. */
    public boolean leafCulling = false;
    /** 0..100: share of partly covered inner leaf faces hidden anyway, at random (Cull Fewer Leaves randomRejection). */
    public int leafCullingRejection = 0;
    /** 1..4: layers of leaves before the inside is culled (Cull Fewer Leaves depth). */
    public int leafCullingDepth = 2;
    /** ALWAYS / ONE_FRAME / ZERO_FRAMES (Sodium chunk_build_defer_mode). */
    public String chunkBuildDeferMode = "ALWAYS";
    /** Intent carried over from iris.properties; read-only until the shader pack loader exists. */
    public String shaderPack = "";
    public boolean shadersEnabled = false;
    /** P4-1b: OFF / FSR3 / FSR4 / DLSS (Iroh section of the video settings). OFF = the rc14 path. */
    public String upscaler = "OFF";
    /** P5-2: the Look switch (NAVIDSON | RAY_TRACED); read before device creation (RtSwitch.configLook) */
    public String look = "NAVIDSON";
    /** P4-1b: NATIVE_AA / QUALITY / BALANCED / PERFORMANCE / ULTRA_PERFORMANCE */
    public String upscaleQuality = "QUALITY";
    /** P4-1b / FSR-Q4: RCAS strength: below 0 = Auto (per mode, Rcas.forRatio), 0 = off, 10..100 = percent. Missing from an old iroh.json = Auto. */
    public int upscaleSharpness = -10;
    /** FSR-Q5: PIXEL_ART (default) | FORMULA | MANUAL | OFF: how the world texture LOD bias is derived */
    public String upscaleMipMode = "PIXEL_ART";
    /** FSR-Q6/Q7 reactive mask source: BASIC (own colour diff) | AUTO (FSR auto-generated) | VOXEL (voxel-aware layers) */
    public String upscaleReactiveMask = "BASIC";
    /** FSR-Q5: the bias of MANUAL in tenths of a level, -50 .. 0 */
    public int upscaleMipBias = -10;

    public void write() {
        IrohOptions.save();
    }
}
