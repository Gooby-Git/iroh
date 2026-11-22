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

    // ---- options the pack relied on (Sodium / Sodium Extra / Iris / Cull Fewer Leaves), see IrohOptions ----
    /** DEFAULT follows the vanilla Graphics setting, FAST / FANCY override it. */
    public String leavesQuality = "DEFAULT";
    public boolean enableVignette = true;
    public boolean showFps = false;
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
    /** P4-1b: FSR sharpening 0 (off) .. 100 */
    public int upscaleSharpness = 0;

    public void write() {
        IrohOptions.save();
    }
}
