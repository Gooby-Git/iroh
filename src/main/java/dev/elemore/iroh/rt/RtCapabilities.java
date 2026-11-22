package dev.elemore.iroh.rt;

/**
 * What the Vulkan device offers the RT core. RT needs acceleration structures + ray query + buffer device address;
 * opacity micromaps and position fetch are optional accelerators (not required by the v1 design).
 */
public record RtCapabilities(boolean accelerationStructure, boolean rayQuery, boolean bufferDeviceAddress, boolean opacityMicromap,
                             boolean positionFetch, long deviceLocalBytes) {
    public static final RtCapabilities NONE = new RtCapabilities(false, false, false, false, false, 0);

    public boolean supported() {
        return accelerationStructure && rayQuery && bufferDeviceAddress;
    }
}
