package net.vulkanmod.vulkan.device;

import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;

/**
 * P2-3 (RQ-P2D-a): the terrain draw tier, decided once at device creation.
 * <p>
 * Terrain indirect commands ({@code DrawBuffers.buildDrawBatchesIndirect} and the pack FULL path's
 * {@code writePackCommands}) carry firstInstance = the packed section offset ({@code terrain.vsh} decodes
 * gl_InstanceIndex). A non-zero firstInstance in an indirect command needs {@code drawIndirectFirstInstance}
 * (VUID-VkDrawIndexedIndirectCommand-firstInstance-00554) and drawCount &gt; 1 needs {@code multiDrawIndirect}.
 * Direct draws ({@code vkCmdDrawIndexed}) may use any firstInstance without a feature, so a device lacking either
 * bit draws terrain directly (tier T-C of docs/P2-PLAN.md); the {@code indirectDraw} option is then ignored.
 * None of the 17 hardware-matrix profiles lacks either bit (every desktop GPU offers both).
 */
public final class IndirectDrawCaps {
    public enum TerrainTier { INDIRECT, DIRECT }

/**
* P2-7b (L5b): the GPU terrain-cull tier. COUNT (T-A): compute compaction + vkCmdDrawIndexedIndirectCount; FIXED (T-B,
* indirect tier without drawIndirectCount): compute writes instanceCount 0 for culled commands in place, fixed-count
* vkCmdDrawIndexedIndirect; CPU (T-C, direct terrain draws): no GPU culling, the CPU path draws.
*/
public enum CullTier { COUNT, FIXED, CPU }

    private static volatile boolean multiDrawIndirect;
    private static volatile boolean firstInstance;
    /** before any device exists: direct (always valid) */
    private static volatile TerrainTier tier = TerrainTier.DIRECT;
private static volatile boolean drawIndirectCount;

    private IndirectDrawCaps() {}

    /** VulkanMod's original indirect-draw requirement ({@link Device#isDrawIndirectSupported}). */
    public static boolean multiDrawSupported(VkPhysicalDeviceFeatures available, VkPhysicalDeviceVulkan11Features available11) {
        return available.multiDrawIndirect() && available11.shaderDrawParameters();
    }

    /** The pure decision: indirect terrain only when both features are enabled on the device. */
    public static TerrainTier decide(boolean multiDrawIndirectEnabled, boolean firstInstanceEnabled) {
        return multiDrawIndirectEnabled && firstInstanceEnabled ? TerrainTier.INDIRECT : TerrainTier.DIRECT;
    }

    /**
     * Production device-creation step (DeviceManager.createLogicalDevice, and the hwCapsRepro / terrainIndirectRepro
     * harnesses): enables multiDrawIndirect + shaderDrawParameters and drawIndirectFirstInstance where offered and records
     * the tier. Writes only the given enable structs.
     */
    public static TerrainTier apply(VkPhysicalDeviceFeatures available, VkPhysicalDeviceVulkan11Features available11,
                                    VkPhysicalDeviceFeatures enable, VkPhysicalDeviceVulkan11Features enable11) {
        boolean mdi = multiDrawSupported(available, available11);
        boolean fi = available.drawIndirectFirstInstance();
        enable.multiDrawIndirect(mdi);
        enable11.shaderDrawParameters(mdi);
        enable.drawIndirectFirstInstance(fi);
        multiDrawIndirect = mdi;
        firstInstance = fi;
        tier = decide(mdi, fi);
        return tier;
    }

    /**
* P2-7b: device-creation step for drawIndirectCount (Vulkan 1.2 core feature; api 4 V-M1 enabled it where offered):
* enables it where offered and records it for {@link #cullTier}. DeviceManager.createLogicalDevice and hwCapsRepro.
*/
public static boolean applyDrawCount(VkPhysicalDeviceVulkan12Features available12, VkPhysicalDeviceVulkan12Features enable12) {
boolean dc = available12.drawIndirectCount();
enable12.drawIndirectCount(dc);
drawIndirectCount = dc;
return dc;
}

/** test harnesses that create their own device: record what they enabled */
public static void noteDrawCount(boolean enabled) {
drawIndirectCount = enabled;
}

/** P2-7b: the pure decision (hwCapsRepro checks it against the raw features) */
public static CullTier decideCull(TerrainTier terrain, boolean drawIndirectCountEnabled) {
if (terrain != TerrainTier.INDIRECT) return CullTier.CPU;
return drawIndirectCountEnabled ? CullTier.COUNT : CullTier.FIXED;
}

public static CullTier cullTier() {
return decideCull(tier, drawIndirectCount);
}

public static boolean drawIndirectCountEnabled() {
return drawIndirectCount;
}

public static TerrainTier tier() {
        return tier;
    }

    public static boolean firstInstanceEnabled() {
        return firstInstance;
    }

    /**
     * api 4 (P2-3 review b97ae0e #1): multiDrawIndirectCount commands carry firstInstance, and its no-drawIndirectCount fallback issues
     * vkCmdDrawIndexedIndirect with drawCount = maxDraws: offered to mods only when both features are enabled (every desktop GPU).
     */
    public static boolean api4IndirectAllowed() {
        return multiDrawIndirect && firstInstance;
    }

    /** Render thread, per frame: draw terrain through indirect commands (device tier AND the user's option). */
    public static boolean useTerrainIndirect() {
        return tier == TerrainTier.INDIRECT && net.vulkanmod.Initializer.CONFIG.indirectDraw;
    }

    public static String report() {
        return "terrainDraw=" + (tier == TerrainTier.INDIRECT ? "indirect" : "direct")
                + " (multiDrawIndirect=" + multiDrawIndirect + " drawIndirectFirstInstance=" + firstInstance + ")"
+ " terrainCull=" + switch (cullTier()) { case COUNT -> "count(T-A)"; case FIXED -> "fixed(T-B)"; case CPU -> "cpu(T-C)"; };
    }
}
