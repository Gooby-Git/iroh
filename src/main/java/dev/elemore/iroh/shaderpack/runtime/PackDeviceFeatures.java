package dev.elemore.iroh.shaderpack.runtime;

import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;

/**
 * PACK-FULL (T-2 I-7/I-9, VUID-RuntimeSpirv-NonWritable-06341): core features shader packs need from the device, enabled
 * by DeviceManager whenever the device offers them (a feature bit only makes the API legal; the pack can be switched on
 * at runtime, so it cannot wait for a launch flag). Navidson's shadow VERTEX shader stores into {@code voxel_img}
 * (colored-lighting voxelisation, {@code writeonly uimage3D}): without {@code vertexPipelineStoresAndAtomics} every
 * shadow pipeline (and each rebuild at a resize) was a validation error and undefined on the driver. Fragment-stage
 * image stores (puddle / reflection voxels in some option sets) need {@code fragmentStoresAndAtomics}. Per-buffer blend
 * ({@code blend.<program>.<buffer>}, Navidson: colortex4 off in gbuffers_water) needs {@code independentBlend}
 * (VUID-VkPipelineColorBlendStateCreateInfo-pAttachments-00605); without it VkPackBackend uses attachment 0's state.
 * The offline harnesses build their device with the same set ({@code HeadlessVkBackend.Features.GAME}).
 */
public final class PackDeviceFeatures {

    private PackDeviceFeatures() {}

    /** what the game device was created with (DeviceManager sets these; read by IrohVkBackend) */
    public static volatile boolean vertexStores, fragmentStores, independentBlend;

    /** enables the pack's bits on {@code enable} where {@code avail} offers them; returns a report fragment */
    public static String apply(VkPhysicalDeviceFeatures avail, VkPhysicalDeviceFeatures enable) {
        boolean v = avail.vertexPipelineStoresAndAtomics(), f = avail.fragmentStoresAndAtomics(), b = avail.independentBlend();
        enable.vertexPipelineStoresAndAtomics(v);
        enable.fragmentStoresAndAtomics(f);
        enable.independentBlend(b);
        return "vertexPipelineStoresAndAtomics=" + v + " fragmentStoresAndAtomics=" + f + " independentBlend=" + b;
    }
}
