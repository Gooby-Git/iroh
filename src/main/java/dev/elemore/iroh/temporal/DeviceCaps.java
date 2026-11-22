package dev.elemore.iroh.temporal;

import dev.elemore.iroh.debug.DebugSwitches;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;

/**
 * Card P4-1a: device capabilities the temporal upscaler inputs and FSR 3.1 need (fsr.md A.11), and the switch that
 * enables them at device creation. Enabled ONLY when present AND requested: {@code iroh.upscale.features=on}, or
 * {@code auto} (default) with {@code -Diroh.upscale.mode} set to a non-off value at launch. With the mode unset the
 * device is created exactly as in rc14 (only samplerAnisotropy, logicOp, multiDrawIndirect, wideLines, Vulkan 1.1
 * shaderDrawParameters). Features cannot change after device creation, so turning the mode on at runtime through
 * IrohDebug gets render scale + bilinear only (velocity and FSR need a relaunch with the property).
 */
public final class DeviceCaps {
    private DeviceCaps() {}

    /** features were requested and applied at device creation */
    public static boolean requested;
    public static boolean storageExtendedFormats, storageWriteWithoutFormat, storageReadWithoutFormat;
    public static boolean shaderInt16, shaderFloat16, storage16Bit;
    public static boolean subgroupBasic, subgroupQuad, subgroupArithmetic, subgroupInCompute;
    public static int subgroupSize;
    /** VK_FORMAT_R16G16_SFLOAT supports STORAGE_IMAGE with optimal tiling */
    public static boolean rg16fStorage;

    /** P4-1b: set from iroh.json before the device is created (the upscaler setting is not Off) */
    public static volatile boolean configRequested;

    /** decision taken before device creation (system properties + {@link #configRequested}: no game state) */
    public static boolean shouldEnable() {
        String f = DebugSwitches.getOr(UpscaleSwitches.FEATURES, "auto");
        if (f.equals("on"))
            return true;
        if (f.equals("off"))
            return false;
        if (configRequested)
            return true;
        ScaleMode m = ScaleMode.parse(System.getProperty(UpscaleSwitches.MODE));
        return m != null && m.on();
    }

    /**
     * Called from DeviceManager.createLogicalDevice. Queries what is available (always: cheap, read-only) and, when
     * {@link #shouldEnable()}, sets the matching bits in {@code enable} / {@code enable11} / {@code enable12}. The caller
     * owns the pNext chain: {@code enable12} is the device's ONE Vulkan 1.2 feature struct (api 4 puts drawIndirectCount
     * in it; a second struct of the same sType in the chain would be invalid).
     */
    public static void apply(MemoryStack stack, VkPhysicalDevice pd,
                             VkPhysicalDeviceFeatures available, VkPhysicalDeviceFeatures enable,
                             VkPhysicalDeviceVulkan11Features available11, VkPhysicalDeviceVulkan11Features enable11,
                             VkPhysicalDeviceVulkan12Features enable12) {
        storageExtendedFormats = available.shaderStorageImageExtendedFormats();
        storageWriteWithoutFormat = available.shaderStorageImageWriteWithoutFormat();
        storageReadWithoutFormat = available.shaderStorageImageReadWithoutFormat();
        shaderInt16 = available.shaderInt16();
        storage16Bit = available11 != null && available11.storageBuffer16BitAccess();

        VkPhysicalDeviceVulkan12Features avail12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
        VkPhysicalDeviceFeatures2 f2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(avail12.address());
        vkGetPhysicalDeviceFeatures2(pd, f2);
        shaderFloat16 = avail12.shaderFloat16();

        VkPhysicalDeviceSubgroupProperties sg = VkPhysicalDeviceSubgroupProperties.calloc(stack).sType$Default();
        VkPhysicalDeviceProperties2 p2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(sg.address());
        vkGetPhysicalDeviceProperties2(pd, p2);
        subgroupSize = sg.subgroupSize();
        int ops = sg.supportedOperations();
        subgroupBasic = (ops & VK_SUBGROUP_FEATURE_BASIC_BIT) != 0;
        subgroupQuad = (ops & VK_SUBGROUP_FEATURE_QUAD_BIT) != 0;
        subgroupArithmetic = (ops & VK_SUBGROUP_FEATURE_ARITHMETIC_BIT) != 0;
        subgroupInCompute = (sg.supportedStages() & VK_SHADER_STAGE_COMPUTE_BIT) != 0;

        VkFormatProperties fp = VkFormatProperties.calloc(stack);
        vkGetPhysicalDeviceFormatProperties(pd, VK_FORMAT_R16G16_SFLOAT, fp);
        rg16fStorage = (fp.optimalTilingFeatures() & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) != 0;

        requested = shouldEnable();
        if (!requested)
            return;
        enable.shaderStorageImageExtendedFormats(storageExtendedFormats);
        enable.shaderStorageImageWriteWithoutFormat(storageWriteWithoutFormat);
        enable.shaderStorageImageReadWithoutFormat(storageReadWithoutFormat);
        enable.shaderInt16(shaderInt16);
        if (enable11 != null)
            enable11.storageBuffer16BitAccess(storage16Bit);
        if (shaderFloat16 && enable12 != null)
            enable12.shaderFloat16(true);
    }

    /**
     * INT-2: {@link #apply} that never throws: on any failure (forced by -Diroh.test.failInit=deviceCaps) the upscale
     * features are reported off (velocity / FSR unavailable, render scale + bilinear still work) with ONE log line, and
     * the bits it may already have set are cleared again, so the device is created with the rc14 feature set + api 4's
     * drawIndirectCount.
     */
    public static void applySafely(MemoryStack stack, VkPhysicalDevice pd,
                                   VkPhysicalDeviceFeatures available, VkPhysicalDeviceFeatures enable,
                                   VkPhysicalDeviceVulkan11Features available11, VkPhysicalDeviceVulkan11Features enable11,
                                   VkPhysicalDeviceVulkan12Features enable12) {
        try {
            dev.elemore.iroh.diag.FeatureFailures.inject("deviceCaps");
            apply(stack, pd, available, enable, available11, enable11, enable12);
        } catch (RuntimeException e) {
            requested = false;
            try {
                if (enable != null) {
                    enable.shaderStorageImageExtendedFormats(false);
                    enable.shaderStorageImageWriteWithoutFormat(false);
                    enable.shaderStorageImageReadWithoutFormat(false);
                    enable.shaderInt16(false);
                }
                if (enable11 != null) enable11.storageBuffer16BitAccess(false);
                if (enable12 != null) enable12.shaderFloat16(false);
            } catch (RuntimeException ignored) {
            }
            dev.elemore.iroh.diag.FeatureFailures.record("deviceCaps", e, "upscale device features not enabled: velocity and FSR unavailable, render scale stays bilinear");
        }
    }

    /** the camera velocity compute pass can run */
    public static boolean velocityCapable() {
        return requested && storageExtendedFormats && rg16fStorage;
    }

    /** what FSR 3.1's VK backend needs (non-16-bit permutations); subgroup quad in compute is required by its shaders */
    public static boolean fsrCapable() {
        return requested && storageExtendedFormats && storageWriteWithoutFormat && subgroupBasic && subgroupQuad && subgroupInCompute;
    }

    public static String report() {
        return "upscale device features " + (requested ? "ENABLED" : "not requested")
                + ": storageExtendedFormats=" + storageExtendedFormats + " writeWithoutFormat=" + storageWriteWithoutFormat
                + " readWithoutFormat=" + storageReadWithoutFormat + " rg16fStorage=" + rg16fStorage
                + " int16=" + shaderInt16 + " float16=" + shaderFloat16 + " storage16=" + storage16Bit
                + " subgroup(size " + subgroupSize + " basic=" + subgroupBasic + " quad=" + subgroupQuad
                + " arithmetic=" + subgroupArithmetic + " compute=" + subgroupInCompute + ")"
                + " -> velocity " + velocityCapable() + ", fsr " + fsrCapable();
    }
}
