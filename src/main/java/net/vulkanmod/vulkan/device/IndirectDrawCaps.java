package net.vulkanmod.vulkan.device;

import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;

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

    private static volatile boolean multiDrawIndirect;
    private static volatile boolean firstInstance;
    /** before any device exists: direct (always valid) */
    private static volatile TerrainTier tier = TerrainTier.DIRECT;

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

    public static TerrainTier tier() {
        return tier;
    }

    public static boolean firstInstanceEnabled() {
        return firstInstance;
    }

    /** Render thread, per frame: draw terrain through indirect commands (device tier AND the user's option). */
    public static boolean useTerrainIndirect() {
        return tier == TerrainTier.INDIRECT && net.vulkanmod.Initializer.CONFIG.indirectDraw;
    }

    public static String report() {
        return "terrainDraw=" + (tier == TerrainTier.INDIRECT ? "indirect" : "direct")
                + " (multiDrawIndirect=" + multiDrawIndirect + " drawIndirectFirstInstance=" + firstInstance + ")";
    }
}
