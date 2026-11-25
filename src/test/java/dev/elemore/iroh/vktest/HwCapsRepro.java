package dev.elemore.iroh.vktest;

import dev.elemore.iroh.temporal.DeviceCaps;
import dev.elemore.iroh.upscale.fsr.FsrUpscaler;
import net.vulkanmod.vulkan.device.IndirectDrawCaps;
import net.vulkanmod.vulkan.device.SyncMemCaps;
import net.vulkanmod.vulkan.util.VkEnum;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.lwjgl.vulkan.VK12.*;

/**
 * H1B (RQ-H1-c): the PRODUCTION capability decision (DeviceCaps.apply -> linkFeatureChain -> vkCreateDevice -> the tier getters) run once per
 * device, so the hardware matrix (tools/hw-matrix.sh, Khronos profiles on lavapipe) exercises it per GPU tier. For each device it:
 * (1) creates the rc14 device (upscale features off) and checks nothing is enabled; (2) creates the upscale device with the real chain and checks
 * every enabled bit is offered, and that DeviceCaps' fsr / velocity / fp16 answers equal an independent derivation from raw Vulkan queries;
 * (3) builds the real FSR context when fsrCapable says so (must succeed); (4) validation errors must be 0. Reports tier, enabled features and
 * upscaler availability. P2-3: also the terrain draw tier (indirect only with drawIndirectFirstInstance enabled, direct otherwise).
 * Run: ./gradlew hwCapsRepro. Last line "# hwcaps PASS|FAIL ..." (key=value tier line follows as "# hwcaps-tier ...").
 */
public final class HwCapsRepro {
    static final List<String> PROBLEMS = new ArrayList<>();

    public static void main(String[] a) {
        String icd = System.getenv("VK_DRIVER_FILES");
        if (icd == null || !icd.toLowerCase().contains("lvp")) throw new IllegalStateException("refusing to run: VK_DRIVER_FILES must point at lavapipe");
        String tier = "?", name = "?", extra = "";
        try (MemoryStack s = MemoryStack.stackPush()) {
            Float16Repro.init(s);
            VkPhysicalDevice phys = Float16Repro.phys;
            VkPhysicalDeviceProperties pr = VkPhysicalDeviceProperties.calloc(s);
            vkGetPhysicalDeviceProperties(phys, pr);
            name = pr.deviceNameString();
            Set<String> exts = VkEnum.deviceExtensions(phys);
            int before = Float16Repro.ERRORS.size();

            // (1) rc14 device: features not requested
            System.setProperty("iroh.upscale.features", "off");
            create(s, false);
            check(!DeviceCaps.requested && !DeviceCaps.enabledFloat16 && !DeviceCaps.enabledInt16 && !DeviceCaps.enabledStorage16 && !DeviceCaps.fp16ShadersAllowed(),
                "features off must enable nothing: " + DeviceCaps.report());
            check(!DeviceCaps.velocityCapable() && !DeviceCaps.fsrCapable(), "features off must report no velocity/fsr");

            // (2) upscale device, the real decision
            System.setProperty("iroh.upscale.features", "on");
            boolean fsrMade = create(s, true);
            Raw raw = Raw.query(s, phys);
            boolean fsrExpect = raw.extFormats && raw.writeWithoutFormat && raw.sgBasic && raw.sgQuad && raw.sgCompute;
            boolean velExpect = raw.extFormats && raw.rg16fStorage;
            boolean fp16Expect = raw.float16 && raw.int16 && raw.storage16;
            check(DeviceCaps.requested, "features=on must be requested");
            check(DeviceCaps.fsrCapable() == fsrExpect, "fsrCapable " + DeviceCaps.fsrCapable() + " != raw " + fsrExpect);
            check(DeviceCaps.velocityCapable() == velExpect, "velocityCapable " + DeviceCaps.velocityCapable() + " != raw " + velExpect);
            check(DeviceCaps.fp16ShadersAllowed() == fp16Expect, "fp16ShadersAllowed " + DeviceCaps.fp16ShadersAllowed() + " != raw " + fp16Expect);
            check(!DeviceCaps.enabledFloat16 || raw.float16, "float16 enabled but not offered");
            check(!DeviceCaps.enabledInt16 || raw.int16, "int16 enabled but not offered");
            check(!DeviceCaps.enabledStorage16 || raw.storage16, "storage16 enabled but not offered");
            // idempotent: a second decision on the same inputs gives the same answer
            String r1 = DeviceCaps.report();
            create(s, true);
            check(r1.equals(DeviceCaps.report()), "decision is not deterministic: " + r1 + " vs " + DeviceCaps.report());

            boolean natives = dev.elemore.iroh.upscale.fsr.NativeLoader.load();
            if (natives && DeviceCaps.fsrCapable()) check(fsrMade, "FSR context could not be created on a device DeviceCaps calls fsrCapable");

            String upTier = !DeviceCaps.velocityCapable() ? "base" : !DeviceCaps.fsrCapable() ? "velocity" : DeviceCaps.fp16ShadersAllowed() ? "fsr-fp16" : "fsr-fp32";
            // the profiles layer keeps lavapipe's vendorID/deviceName, so the profile file name (nv-*) is the vendor hint (review 71f9e93 #5)
            String pfile = System.getenv("VK_KHRONOS_PROFILES_PROFILE_FILE");
            boolean nvidia = pr.vendorID() == 0x10DE || (pfile != null && new java.io.File(pfile).getName().startsWith("nv-"));
            tier = upTier;
            extra = " fsr3=" + (natives && DeviceCaps.fsrCapable() ? "available" : natives ? "needs-device-features" : "needs-native-library")
                + " fsrCtx=" + (fsrMade ? "created" : "n/a") + " dlss=" + (nvidia ? "notYet(nvidia)" : "needsNvidia")
                + rtTier(s, phys, exts) + terrainTier(s, phys) + syncMemPath(s, phys, exts);
            for (String e : Float16Repro.ERRORS.subList(before, Float16Repro.ERRORS.size())) {
                System.out.println("    ! " + e.substring(0, Math.min(170, e.length())).replace("\n", " "));
            }
            check(Float16Repro.ERRORS.size() == before, (Float16Repro.ERRORS.size() - before) + " validation errors");
            System.out.println("# hwcaps-tier device=\"" + name + "\" api=" + VK_API_VERSION_MAJOR(pr.apiVersion()) + "." + VK_API_VERSION_MINOR(pr.apiVersion())
                + " exts=" + exts.size() + " tier=" + tier + " fp16=" + DeviceCaps.enabledFloat16 + " int16=" + DeviceCaps.enabledInt16 + " storage16=" + DeviceCaps.enabledStorage16
                + " subgroup=" + DeviceCaps.subgroupSize + (DeviceCaps.subgroupQuad ? "+quad" : "") + " rg16fStorage=" + DeviceCaps.rg16fStorage + extra);
        } catch (Throwable t) {
            PROBLEMS.add("crash: " + t);
            t.printStackTrace(System.out);
        }
        for (String p : PROBLEMS) System.out.println("  problem: " + p);
        boolean ok = PROBLEMS.isEmpty();
        System.out.println((ok ? "# hwcaps PASS" : "# hwcaps FAIL") + " " + name + " tier=" + tier);
        System.exit(ok ? 0 : 1);
    }

    /** RT tier hook: the 2b branch has no RT core. */
    static String rtTier(MemoryStack s, VkPhysicalDevice phys, Set<String> exts) { return " rt=n/a(branch has no RT)"; }

    static void check(boolean ok, String msg) { if (!ok) PROBLEMS.add(msg); }

    /** What a raw Vulkan query says, independent of DeviceCaps. */
    record Raw(boolean extFormats, boolean writeWithoutFormat, boolean float16, boolean int16, boolean storage16,
               boolean sgBasic, boolean sgQuad, boolean sgCompute, boolean rg16fStorage) {
        static Raw query(MemoryStack outer, VkPhysicalDevice phys) {
            try (MemoryStack s = outer.push()) {
                VkPhysicalDeviceVulkan11Features a11 = VkPhysicalDeviceVulkan11Features.calloc(s).sType$Default();
                VkPhysicalDeviceVulkan12Features a12 = VkPhysicalDeviceVulkan12Features.calloc(s).sType$Default();
                VkPhysicalDeviceFeatures2 a2 = VkPhysicalDeviceFeatures2.calloc(s).sType$Default().pNext(a11.address());
                a11.pNext(a12.address());
                vkGetPhysicalDeviceFeatures2(phys, a2);
                VkPhysicalDeviceSubgroupProperties sg = VkPhysicalDeviceSubgroupProperties.calloc(s).sType$Default();
                vkGetPhysicalDeviceProperties2(phys, VkPhysicalDeviceProperties2.calloc(s).sType$Default().pNext(sg.address()));
                VkFormatProperties fp = VkFormatProperties.calloc(s);
                vkGetPhysicalDeviceFormatProperties(phys, VK_FORMAT_R16G16_SFLOAT, fp);
                VkPhysicalDeviceFeatures f = a2.features();
                int ops = sg.supportedOperations();
                return new Raw(f.shaderStorageImageExtendedFormats(), f.shaderStorageImageWriteWithoutFormat(), a12.shaderFloat16(), f.shaderInt16(), a11.storageBuffer16BitAccess(),
                    (ops & VK_SUBGROUP_FEATURE_BASIC_BIT) != 0, (ops & VK_SUBGROUP_FEATURE_QUAD_BIT) != 0, (sg.supportedStages() & VK_SHADER_STAGE_COMPUTE_BIT) != 0,
                    (fp.optimalTilingFeatures() & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) != 0);
            }
        }
    }

    /** The production device-creation sequence (DeviceManager order). Returns true when an FSR context was created on it (only attempted when fsrCapable). */
    static boolean create(MemoryStack outer, boolean tryFsr) {
        return create(outer, tryFsr, false);
    }

    /** {@code maskFirstInstance}: the device reports no drawIndirectFirstInstance (P2-3 fallback case: a GPU without it). */
    static boolean create(MemoryStack outer, boolean tryFsr, boolean maskFirstInstance) {
        VkPhysicalDevice phys = Float16Repro.phys;
        try (MemoryStack s = outer.push()) {
            VkPhysicalDeviceVulkan12Features e12 = VkPhysicalDeviceVulkan12Features.calloc(s).sType$Default();
            VkPhysicalDeviceVulkan11Features e11 = VkPhysicalDeviceVulkan11Features.calloc(s).sType$Default();
            VkPhysicalDeviceFeatures2 e2 = VkPhysicalDeviceFeatures2.calloc(s).sType$Default();
            VkPhysicalDeviceVulkan11Features a11 = VkPhysicalDeviceVulkan11Features.calloc(s).sType$Default();
            VkPhysicalDeviceVulkan12Features a12 = VkPhysicalDeviceVulkan12Features.calloc(s).sType$Default();
            VkPhysicalDeviceFeatures2 a2 = VkPhysicalDeviceFeatures2.calloc(s).sType$Default().pNext(a11.address());
            a11.pNext(a12.address());
            vkGetPhysicalDeviceFeatures2(phys, a2);
            if (maskFirstInstance) a2.features().drawIndirectFirstInstance(false);
            IndirectDrawCaps.applyDrawCount(a12, e12); // P2-7b: DeviceManager order (drawIndirectCount, then the terrain tier)
            lastEnabledDrawCount = e12.drawIndirectCount();
            IndirectDrawCaps.applyMinmax(a12, e12); // P2-7c: DeviceManager order (samplerFilterMinmax where offered, then the R32F format bit)
            IndirectDrawCaps.noteMinmaxFormat(net.vulkanmod.render.chunk.cull.HiZPyramid.r32fMinmax(phys));
            lastEnabledMinmax = e12.samplerFilterMinmax();
            IndirectDrawCaps.apply(a2.features(), a11, e2.features(), e11); // P2-3: DeviceManager order (before DeviceCaps)
            lastEnabledMdi = e2.features().multiDrawIndirect();
            lastEnabledFirstInstance = e2.features().drawIndirectFirstInstance();
            DeviceCaps.apply(s, phys, a2.features(), e2.features(), a11, e11, e12);
            DeviceCaps.linkFeatureChain(e2, e11, e12);
            extendChain(s, phys, e12);
            VkDeviceQueueCreateInfo.Buffer q = VkDeviceQueueCreateInfo.calloc(1, s).sType$Default().queueFamilyIndex(0).pQueuePriorities(s.floats(1f));
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(s).sType$Default().pQueueCreateInfos(q).pNext(e2.address());
            deviceExtensions(s, dci);
            PointerBuffer pd = s.mallocPointer(1);
            Float16Repro.ck(vkCreateDevice(phys, dci, null, pd));
            VkDevice vdev = new VkDevice(pd.get(0), phys, dci);
            boolean made = false;
            if (tryFsr && DeviceCaps.fsrCapable() && dev.elemore.iroh.upscale.fsr.NativeLoader.load()) {
                int shim = DeviceCaps.fp16ShadersAllowed() ? 0 : FsrUpscaler.NO_FP16;
                try (FsrUpscaler up = FsrUpscaler.create(Float16Repro.instance.address(), phys.address(), vdev.address(), 0L, FsrUpscaler.AUTO_EXPOSURE, shim, 64, 64, 128, 128)) {
                    made = true;
                } catch (RuntimeException e) {
                    System.out.println("  fsr create failed: " + e.getMessage());
                }
            }
            vkDeviceWaitIdle(vdev);
            vkDestroyDevice(vdev, null);
            return made;
        }
    }

    static boolean lastEnabledMdi, lastEnabledFirstInstance;

    /**
     * P2-3 (RQ-P2D-a): terrain draw tier. Indirect terrain commands carry firstInstance (packed section offset), so the indirect tier
     * must only be chosen with drawIndirectFirstInstance (and multiDrawIndirect) ENABLED on the created device; otherwise the direct
     * fallback (T-C). Checked on the real device, on the same device with firstInstance masked (no profile lacks it), and on the
     * pure decision table. Returns the tier-line fragment.
     */
    static boolean lastEnabledDrawCount, lastEnabledMinmax;

    static String terrainTier(MemoryStack s, VkPhysicalDevice phys) {
        boolean rawMdi, rawSdp, rawFi, rawDc, rawMinmax;
        try (MemoryStack f = s.push()) {
            VkPhysicalDeviceVulkan12Features a12 = VkPhysicalDeviceVulkan12Features.calloc(f).sType$Default();
            VkPhysicalDeviceVulkan11Features a11 = VkPhysicalDeviceVulkan11Features.calloc(f).sType$Default().pNext(a12.address());
            VkPhysicalDeviceFeatures2 a2 = VkPhysicalDeviceFeatures2.calloc(f).sType$Default().pNext(a11.address());
            vkGetPhysicalDeviceFeatures2(phys, a2);
            rawMdi = a2.features().multiDrawIndirect(); rawSdp = a11.shaderDrawParameters(); rawFi = a2.features().drawIndirectFirstInstance();
            rawDc = a12.drawIndirectCount();
            rawMinmax = a12.samplerFilterMinmax();
        }
        // the device as created by the last production create(..) call (the upscale device)
        IndirectDrawCaps.TerrainTier t = IndirectDrawCaps.tier();
        IndirectDrawCaps.TerrainTier expect = rawMdi && rawSdp && rawFi ? IndirectDrawCaps.TerrainTier.INDIRECT : IndirectDrawCaps.TerrainTier.DIRECT;
        check(t == expect, "terrain tier " + t + " != raw " + expect + " (mdi=" + rawMdi + " sdp=" + rawSdp + " firstInstance=" + rawFi + ")");
        check(t != IndirectDrawCaps.TerrainTier.INDIRECT || (lastEnabledFirstInstance && lastEnabledMdi),
            "indirect terrain tier without drawIndirectFirstInstance/multiDrawIndirect ENABLED on the device");
        check(!lastEnabledFirstInstance || rawFi, "drawIndirectFirstInstance enabled but not offered");
        check(!lastEnabledMdi || (rawMdi && rawSdp), "multiDrawIndirect enabled but not offered");
        String real = t == IndirectDrawCaps.TerrainTier.INDIRECT ? "indirect" : "direct";
        // P2-7b: GPU terrain-cull tier from the same device: COUNT (T-A) / FIXED (T-B) / CPU (T-C)
        IndirectDrawCaps.CullTier ct = IndirectDrawCaps.cullTier();
        IndirectDrawCaps.CullTier ctExpect = IndirectDrawCaps.decideCull(expect, rawDc);
        check(ct == ctExpect, "cull tier " + ct + " != raw " + ctExpect + " (drawIndirectCount=" + rawDc + ")");
        check(lastEnabledDrawCount == rawDc, "drawIndirectCount enabled=" + lastEnabledDrawCount + " offered=" + rawDc);
        String cull = switch (ct) { case COUNT -> "count(T-A)"; case FIXED -> "fixed(T-B)"; case CPU -> "cpu(T-C)"; };
        // P2-7c: Hi-Z pyramid reduction path from the same device: MINMAX (samplerFilterMinmax + R32F minmax format bit) / SHADER / NONE
        boolean fmtMinmax = net.vulkanmod.render.chunk.cull.HiZPyramid.r32fMinmax(phys);
        IndirectDrawCaps.HiZPath hz = IndirectDrawCaps.hiZPath(), hzExpect = IndirectDrawCaps.decideHiZ(ctExpect, rawMinmax, fmtMinmax);
        check(hz == hzExpect, "hiz path " + hz + " != raw " + hzExpect + " (samplerFilterMinmax=" + rawMinmax + " r32fMinmax=" + fmtMinmax + ")");
        check(lastEnabledMinmax == rawMinmax, "samplerFilterMinmax enabled=" + lastEnabledMinmax + " offered=" + rawMinmax);
        check(IndirectDrawCaps.decideHiZ(IndirectDrawCaps.CullTier.CPU, true, true) == IndirectDrawCaps.HiZPath.NONE
                && IndirectDrawCaps.decideHiZ(IndirectDrawCaps.CullTier.FIXED, true, false) == IndirectDrawCaps.HiZPath.SHADER
                && IndirectDrawCaps.decideHiZ(IndirectDrawCaps.CullTier.COUNT, false, true) == IndirectDrawCaps.HiZPath.SHADER
                && IndirectDrawCaps.decideHiZ(IndirectDrawCaps.CullTier.COUNT, true, true) == IndirectDrawCaps.HiZPath.MINMAX, "decideHiZ table");
        cull += " hiz=" + hz.name().toLowerCase();
        // fallback: the same device without drawIndirectFirstInstance must choose direct draws and create cleanly
        create(s, false, true);
        check(IndirectDrawCaps.tier() == IndirectDrawCaps.TerrainTier.DIRECT, "masked firstInstance: tier " + IndirectDrawCaps.tier() + " (expected DIRECT)");
        check(!lastEnabledFirstInstance, "masked firstInstance: still enabled");
        // pure decision table
        for (int i = 0; i < 4; i++) {
            boolean m = (i & 1) != 0, fi = (i & 2) != 0;
            check(IndirectDrawCaps.decide(m, fi) == (m && fi ? IndirectDrawCaps.TerrainTier.INDIRECT : IndirectDrawCaps.TerrainTier.DIRECT),
                "decide(mdi=" + m + ", firstInstance=" + fi + ")");
        }
        create(s, false); // leave the real decision behind
        check(IndirectDrawCaps.tier() == t, "terrain tier not restored");
        for (IndirectDrawCaps.TerrainTier tt : IndirectDrawCaps.TerrainTier.values())
            for (boolean dc : new boolean[]{false, true})
                check(IndirectDrawCaps.decideCull(tt, dc) == (tt != IndirectDrawCaps.TerrainTier.INDIRECT ? IndirectDrawCaps.CullTier.CPU
                        : dc ? IndirectDrawCaps.CullTier.COUNT : IndirectDrawCaps.CullTier.FIXED), "decideCull(" + tt + ", " + dc + ")");
        return " terrainDraw=" + real + (t == IndirectDrawCaps.TerrainTier.INDIRECT ? "(firstInstance)" : "(T-C fallback)") + " terrainFallback=direct terrainCull=" + cull;
    }

    static SyncMemCaps.Decision lastSyncMem;

    /** Branch hook: extra feature structs the branch's own device code chains behind the 1.2 struct (P2-12: timeline semaphore, memory priority). */
    static void extendChain(MemoryStack s, VkPhysicalDevice phys, VkPhysicalDeviceVulkan12Features e12) {
        Set<String> exts = VkEnum.deviceExtensions(phys);
        VkPhysicalDeviceVulkan12Features a12 = VkPhysicalDeviceVulkan12Features.calloc(s).sType$Default();
        vkGetPhysicalDeviceFeatures2(phys, VkPhysicalDeviceFeatures2.calloc(s).sType$Default().pNext(a12.address()));
        lastSyncMem = SyncMemCaps.apply(exts, a12.timelineSemaphore(), SyncMemCaps.priorityFeatureOffered(s, phys, exts), e12);
        if (lastSyncMem.priority()) SyncMemCaps.chainPriority(e12, VkPhysicalDeviceMemoryPriorityFeaturesEXT.calloc(s));
    }

    /** Branch hook: device extensions the branch's own device code enables (P2-12: memory budget / priority when offered). */
    static void deviceExtensions(MemoryStack s, VkDeviceCreateInfo dci) {
        List<String> names = SyncMemCaps.extensionNames();
        if (names.isEmpty()) return;
        PointerBuffer pb = s.mallocPointer(names.size());
        for (String n : names) pb.put(s.UTF8(n));
        dci.ppEnabledExtensionNames(pb.flip());
    }

    /**
     * P2-12: the sync / memory path this device takes (timeline vs binary semaphores, VK_EXT_memory_budget, VK_EXT_memory_priority), checked
     * against a raw query (used only when offered) and with each switch off (the fallback creates a clean device). Returns the tier-line fragment.
     */
    static String syncMemPath(MemoryStack s, VkPhysicalDevice phys, Set<String> exts) {
        SyncMemCaps.Decision real = lastSyncMem;
        boolean rawTl;
        try (MemoryStack f = s.push()) {
            VkPhysicalDeviceVulkan12Features a12 = VkPhysicalDeviceVulkan12Features.calloc(f).sType$Default();
            vkGetPhysicalDeviceFeatures2(phys, VkPhysicalDeviceFeatures2.calloc(f).sType$Default().pNext(a12.address()));
            rawTl = a12.timelineSemaphore();
        }
        boolean rawBudget = exts.contains(SyncMemCaps.EXT_BUDGET), rawPrio = exts.contains(SyncMemCaps.EXT_PRIORITY) && SyncMemCaps.priorityFeatureOffered(s, phys, exts);
        check(real != null && real.timeline() == rawTl && real.budget() == rawBudget && real.priority() == rawPrio,
            "sync/memory decision " + real + " != raw (timeline=" + rawTl + " budget=" + rawBudget + " priority=" + rawPrio + ")");
        String path = real == null ? "?" : real.path();
        String oldT = System.getProperty("iroh.sync.timeline"), oldM = System.getProperty("iroh.memory.ext");
        try {
            System.setProperty("iroh.sync.timeline", "off");
            System.setProperty("iroh.memory.ext", "off");
            create(s, false);
            check(lastSyncMem != null && !lastSyncMem.timeline() && !lastSyncMem.budget() && !lastSyncMem.priority(), "switches off: decision still " + lastSyncMem);
        } finally {
            restore("iroh.sync.timeline", oldT);
            restore("iroh.memory.ext", oldM);
        }
        create(s, false); // leave the real decision behind
        check(lastSyncMem != null && lastSyncMem.equals(real), "sync/memory decision not restored: " + lastSyncMem);
        return " syncPath=" + path.replace(' ', ',');
    }

    private static void restore(String k, String v) {
        if (v == null) System.clearProperty(k); else System.setProperty(k, v);
    }
}
