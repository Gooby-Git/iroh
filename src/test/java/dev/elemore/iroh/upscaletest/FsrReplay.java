package dev.elemore.iroh.upscaletest;

import dev.elemore.iroh.upscale.*;
import dev.elemore.iroh.upscale.fsr.FsrException;
import dev.elemore.iroh.upscale.fsr.FsrNative;
import dev.elemore.iroh.upscale.fsr.FsrUpscaler;
import dev.elemore.iroh.upscale.fsr.NativeLoader;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK12.*;

/**
 * FSR 3.1 through the JNI shim on lavapipe (CPU Vulkan; NEVER a GPU: it refuses non-CPU devices, requires the loader to be
 * restricted to a lavapipe ICD via VK_DRIVER_FILES, and caps its own device memory at 1 GiB).
 * 1. synthetic scene pair: 320x180 render -> 640x360 (Performance), moving checker square + static textured background,
 *    40 jittered frames (the Performance sequence is 32 phases); checks: no NaN, sharper/closer to ground truth than bilinear, temporal stability, golden.
 * 2. the captured Navidson frame (colortex0 + depthtex0, zero motion vectors, no jitter): what it can and cannot show is
 *    printed with the result.
 * Last line "# fsrreplay PASS|FAIL ...". Context-creation failure on lavapipe is reported with the exact error and is a valid result.
 */
public final class FsrReplay {
    static final long CAP = 1L << 30;
    static int fails;
    static String dumpDir = "build/fsrreplay";

    // ------------------------------------------------------------------ minimal headless Vulkan
    static VkInstance instance;
    static VkPhysicalDevice phys;
    static VkDevice device;
    static VkQueue queue;
    static int family;
    static long pool, fence;
    static VkCommandBuffer cmd;
    static VkPhysicalDeviceMemoryProperties memProps;
    static long allocated;
    static String deviceName;
    static final List<Runnable> cleanup = new ArrayList<>();

    record Img(long image, long memory, int format, int w, int h, int usage, int aspect) {}
    record Buf(long buffer, long memory, long size, long ptr) {}

    static void vk(int r, String what) {
        if (r != VK_SUCCESS) throw new IllegalStateException(what + " failed: VkResult " + r);
    }

    static void init(boolean want16, StringBuilder report) {
        String icd = System.getenv("VK_DRIVER_FILES");
        if (icd == null || !icd.toLowerCase().contains("lvp"))
            throw new IllegalStateException("refusing to run: VK_DRIVER_FILES must point at the lavapipe ICD (use ./gradlew fsrReplay)");
        try (MemoryStack s = stackPush()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(s).sType$Default().pApplicationName(s.UTF8("iroh-fsr-replay")).apiVersion(VK_API_VERSION_1_2);
            VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(s).sType$Default().pApplicationInfo(app);
            PointerBuffer pp = s.mallocPointer(1);
            vk(vkCreateInstance(ici, null, pp), "vkCreateInstance");
            instance = new VkInstance(pp.get(0), ici);
            IntBuffer n = s.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, n, null);
            PointerBuffer devs = s.mallocPointer(n.get(0));
            vkEnumeratePhysicalDevices(instance, n, devs);
            for (int i = 0; i < devs.capacity(); i++) {
                VkPhysicalDevice pd = new VkPhysicalDevice(devs.get(i), instance);
                VkPhysicalDeviceProperties pr = VkPhysicalDeviceProperties.calloc(s);
                vkGetPhysicalDeviceProperties(pd, pr);
                if (pr.deviceType() == VK_PHYSICAL_DEVICE_TYPE_CPU) {
                    phys = pd;
                    deviceName = pr.deviceNameString() + " api " + VK_VERSION_MAJOR(pr.apiVersion()) + "." + VK_VERSION_MINOR(pr.apiVersion());
                    break;
                }
            }
            if (phys == null) throw new IllegalStateException("no CPU Vulkan device (lavapipe); this harness never uses a GPU");
            memProps = VkPhysicalDeviceMemoryProperties.malloc();
            vkGetPhysicalDeviceMemoryProperties(phys, memProps);

            // what FSR needs, and what lavapipe offers
            VkPhysicalDeviceVulkan12Features f12 = VkPhysicalDeviceVulkan12Features.calloc(s).sType$Default();
            VkPhysicalDeviceVulkan11Features f11 = VkPhysicalDeviceVulkan11Features.calloc(s).sType$Default().pNext(f12.address());
            VkPhysicalDeviceFeatures2 f2 = VkPhysicalDeviceFeatures2.calloc(s).sType$Default().pNext(f11.address());
            vkGetPhysicalDeviceFeatures2(phys, f2);
            VkPhysicalDeviceSubgroupProperties sg = VkPhysicalDeviceSubgroupProperties.calloc(s).sType$Default();
            VkPhysicalDeviceProperties2 p2 = VkPhysicalDeviceProperties2.calloc(s).sType$Default().pNext(sg.address());
            vkGetPhysicalDeviceProperties2(phys, p2);
            VkPhysicalDeviceFeatures ft = f2.features();
            int ops = sg.supportedOperations();
            boolean quad = (ops & VK_SUBGROUP_FEATURE_QUAD_BIT) != 0, basic = (ops & VK_SUBGROUP_FEATURE_BASIC_BIT) != 0,
                    arith = (ops & VK_SUBGROUP_FEATURE_ARITHMETIC_BIT) != 0, shuffle = (ops & VK_SUBGROUP_FEATURE_SHUFFLE_BIT) != 0;
            boolean compute = (sg.supportedStages() & VK_SHADER_STAGE_COMPUTE_BIT) != 0;
            report.append("device: ").append(deviceName).append('\n');
            report.append(String.format("features: shaderStorageImageExtendedFormats=%b WriteWithoutFormat=%b ReadWithoutFormat=%b shaderInt16=%b shaderFloat16=%b storageBuffer16BitAccess=%b%n",
                    ft.shaderStorageImageExtendedFormats(), ft.shaderStorageImageWriteWithoutFormat(), ft.shaderStorageImageReadWithoutFormat(),
                    ft.shaderInt16(), f12.shaderFloat16(), f11.storageBuffer16BitAccess()));
            report.append(String.format("subgroup: size=%d computeStage=%b basic=%b quad=%b arithmetic=%b shuffle=%b (FSR needs basic+quad, optical flow arithmetic)%n",
                    sg.subgroupSize(), compute, basic, quad, arith, shuffle));
            List<String> missing = new ArrayList<>();
            if (!ft.shaderStorageImageExtendedFormats()) missing.add("shaderStorageImageExtendedFormats");
            if (!ft.shaderStorageImageWriteWithoutFormat()) missing.add("shaderStorageImageWriteWithoutFormat");
            if (!(basic && quad && compute)) missing.add("subgroup basic+quad in compute");
            report.append("missing required: ").append(missing.isEmpty() ? "none" : missing).append('\n');
            report.append("fp16 permutations: ").append(want16 && f12.shaderFloat16() && ft.shaderInt16() ? "requested and enabled" : "not used (32-bit permutations)").append('\n');

            vkGetPhysicalDeviceQueueFamilyProperties(phys, n, null);
            VkQueueFamilyProperties.Buffer qf = VkQueueFamilyProperties.calloc(n.get(0), s);
            vkGetPhysicalDeviceQueueFamilyProperties(phys, n, qf);
            family = -1;
            for (int i = 0; i < qf.capacity(); i++) if ((qf.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) { family = i; break; }

            VkPhysicalDeviceVulkan12Features e12 = VkPhysicalDeviceVulkan12Features.calloc(s).sType$Default();
            VkPhysicalDeviceVulkan11Features e11 = VkPhysicalDeviceVulkan11Features.calloc(s).sType$Default().pNext(e12.address());
            VkPhysicalDeviceFeatures2 e2 = VkPhysicalDeviceFeatures2.calloc(s).sType$Default().pNext(e11.address());
            e2.features().shaderStorageImageExtendedFormats(ft.shaderStorageImageExtendedFormats())
                    .shaderStorageImageWriteWithoutFormat(ft.shaderStorageImageWriteWithoutFormat())
                    .shaderStorageImageReadWithoutFormat(ft.shaderStorageImageReadWithoutFormat());
            if (want16 && f12.shaderFloat16() && ft.shaderInt16()) {
                e2.features().shaderInt16(true);
                e12.shaderFloat16(true);
                e11.storageBuffer16BitAccess(f11.storageBuffer16BitAccess());
            }
            VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.calloc(1, s).sType$Default().queueFamilyIndex(family).pQueuePriorities(s.floats(1f));
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(s).sType$Default().pNext(e2.address()).pQueueCreateInfos(qci);
            vk(vkCreateDevice(phys, dci, null, pp), "vkCreateDevice");
            device = new VkDevice(pp.get(0), phys, dci);
            vkGetDeviceQueue(device, family, 0, pp);
            queue = new VkQueue(pp.get(0), device);
            LongBuffer lb = s.mallocLong(1);
            vk(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(s).sType$Default().flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(family), null, lb), "pool");
            pool = lb.get(0);
            vk(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pp), "cmd");
            cmd = new VkCommandBuffer(pp.get(0), device);
            vk(vkCreateFence(device, VkFenceCreateInfo.calloc(s).sType$Default(), null, lb), "fence");
            fence = lb.get(0);
        }
    }

    static int memType(int bits, int props) {
        for (int i = 0; i < memProps.memoryTypeCount(); i++)
            if ((bits & (1 << i)) != 0 && (memProps.memoryTypes(i).propertyFlags() & props) == props) return i;
        throw new IllegalStateException("no memory type " + bits + "/" + props);
    }

    static Img image(int fmt, int w, int h, int usage, int aspect) {
        try (MemoryStack s = stackPush()) {
            VkImageCreateInfo ci = VkImageCreateInfo.calloc(s).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(fmt).mipLevels(1).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            ci.extent().set(w, h, 1);
            LongBuffer lb = s.mallocLong(1);
            vk(vkCreateImage(device, ci, null, lb), "vkCreateImage");
            long img = lb.get(0);
            VkMemoryRequirements mr = VkMemoryRequirements.calloc(s);
            vkGetImageMemoryRequirements(device, img, mr);
            if (allocated + mr.size() > CAP) throw new IllegalStateException("1 GiB device memory cap reached");
            allocated += mr.size();
            vk(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(s).sType$Default().allocationSize(mr.size()).memoryTypeIndex(memType(mr.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)), null, lb), "vkAllocateMemory(image)");
            long mem = lb.get(0);
            vk(vkBindImageMemory(device, img, mem, 0), "bind");
            return new Img(img, mem, fmt, w, h, usage, aspect);
        }
    }

    static Buf buffer(long size) {
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            vk(vkCreateBuffer(device, VkBufferCreateInfo.calloc(s).sType$Default().size(size).usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, lb), "vkCreateBuffer");
            long b = lb.get(0);
            VkMemoryRequirements mr = VkMemoryRequirements.calloc(s);
            vkGetBufferMemoryRequirements(device, b, mr);
            if (allocated + mr.size() > CAP) throw new IllegalStateException("1 GiB device memory cap reached");
            allocated += mr.size();
            int props = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            vk(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(s).sType$Default().allocationSize(mr.size()).memoryTypeIndex(memType(mr.memoryTypeBits(), props)), null, lb), "vkAllocateMemory(buffer)");
            long mem = lb.get(0);
            vk(vkBindBufferMemory(device, b, mem, 0), "bind buffer");
            PointerBuffer pp = s.mallocPointer(1);
            vk(vkMapMemory(device, mem, 0, size, 0, pp), "map");
            return new Buf(b, mem, size, pp.get(0));
        }
    }

    static void begin() {
        try (MemoryStack s = stackPush()) {
            vkResetCommandBuffer(cmd, 0);
            vk(vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(s).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin");
        }
    }

    static void submitWait() {
        try (MemoryStack s = stackPush()) {
            vk(vkEndCommandBuffer(cmd), "end");
            VkSubmitInfo si = VkSubmitInfo.calloc(s).sType$Default().pCommandBuffers(s.pointers(cmd));
            vk(vkQueueSubmit(queue, si, fence), "submit");
            vk(vkWaitForFences(device, s.longs(fence), true, 120_000_000_000L), "wait");
            vkResetFences(device, s.longs(fence));
        }
    }

    static void layout(Img i, int from, int to) {
        try (MemoryStack s = stackPush()) {
            VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, s).sType$Default().oldLayout(from).newLayout(to)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(i.image())
                    .srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT).dstAccessMask(VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT);
            b.subresourceRange().aspectMask(i.aspect()).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, b);
        }
    }

    static void upload(Img i, Buf b, int to) {
        layout(i, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        try (MemoryStack s = stackPush()) {
            VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, s);
            r.imageSubresource().aspectMask(i.aspect()).layerCount(1);
            r.imageExtent().set(i.w(), i.h(), 1);
            vkCmdCopyBufferToImage(cmd, b.buffer(), i.image(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, r);
        }
        layout(i, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, to);
    }

    static void download(Img i, Buf b) {
        layout(i, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        try (MemoryStack s = stackPush()) {
            VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, s);
            r.imageSubresource().aspectMask(i.aspect()).layerCount(1);
            r.imageExtent().set(i.w(), i.h(), 1);
            vkCmdCopyImageToBuffer(cmd, i.image(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, b.buffer(), r);
        }
        layout(i, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);
    }

    // ------------------------------------------------------------------ the FSR run on a sequence of frames
    interface FrameSource {
        /** fills the CPU arrays for frame t: color rgba float [w*h*4], depth [w*h], mv [w*h*2], with jitter applied by the source. */
        void render(int t, float jx, float jy, float[] color, float[] depth, float[] mv);
    }

    record RunResult(float[][] outputs, long[] mem, String messages, String version) {}

    static RunResult runFsr(int rw, int rh, int dw, int dh, int frames, FrameSource src, boolean zeroJitter, boolean want16, float near, float far, boolean hdr) {
        Img color = image(VK_FORMAT_R16G16B16A16_SFLOAT, rw, rh, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        Img depth = image(VK_FORMAT_D32_SFLOAT, rw, rh, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_DEPTH_BIT);
        Img mvI = image(VK_FORMAT_R16G16_SFLOAT, rw, rh, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        Img out = image(VK_FORMAT_R16G16B16A16_SFLOAT, dw, dh, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        Buf bc = buffer((long) rw * rh * 8), bd = buffer((long) rw * rh * 4), bm = buffer((long) rw * rh * 4), bo = buffer((long) dw * dh * 8);
        int flags = FsrUpscaler.AUTO_EXPOSURE | FsrUpscaler.DEBUG_CHECKING | (hdr ? FsrUpscaler.HDR : 0);
        int shim = want16 ? 0 : FsrUpscaler.NO_FP16;
        try (FsrUpscaler up = FsrUpscaler.create(instance.address(), phys.address(), device.address(), 0L, flags, shim, rw, rh, dw, dh)) {
            long[] mem = up.memoryUsageBytes();
            JitterSequence js = new JitterSequence(rw, dw);
            float[] c = new float[rw * rh * 4], d = new float[rw * rh], m = new float[rw * rh * 2];
            float[][] outs = new float[frames][];
            boolean outInit = false;
            for (int t = 0; t < frames; t++) {
                float[] j = zeroJitter ? new float[]{0, 0} : js.offset(t);
                src.render(t, j[0], j[1], c, d, m);
                ByteBuffer cb = MemoryUtil.memByteBuffer(bc.ptr(), (int) bc.size()).order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer db = MemoryUtil.memByteBuffer(bd.ptr(), (int) bd.size()).order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer mb = MemoryUtil.memByteBuffer(bm.ptr(), (int) bm.size()).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < c.length; i++) cb.putShort(i * 2, Float.floatToFloat16(c[i]));
                for (int i = 0; i < d.length; i++) db.putFloat(i * 4, d[i]);
                for (int i = 0; i < m.length; i++) mb.putShort(i * 2, Float.floatToFloat16(m[i]));
                begin();
                upload(color, bc, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                upload(depth, bd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                upload(mvI, bm, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                if (!outInit) { layout(out, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL); outInit = true; }
                UpscaleInputs in = new UpscaleInputs();
                in.commandBuffer = cmd.address();
                in.color = new ImageRef(color.image(), color.format(), rw, rh, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, ImageRef.LAYOUT_SHADER_READ_ONLY);
                in.depth = new ImageRef(depth.image(), depth.format(), rw, rh, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, ImageRef.LAYOUT_SHADER_READ_ONLY);
                in.motionVectors = new ImageRef(mvI.image(), mvI.format(), rw, rh, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, ImageRef.LAYOUT_SHADER_READ_ONLY);
                in.output = new ImageRef(out.image(), out.format(), dw, dh, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, ImageRef.LAYOUT_GENERAL);
                in.jitterX = j[0]; in.jitterY = j[1];
                in.renderWidth = rw; in.renderHeight = rh; in.displayWidth = dw; in.displayHeight = dh;
                in.frameTimeMs = 16.6f; in.cameraNear = near; in.cameraFar = far; in.cameraFovYRadians = (float) Math.toRadians(60);
                in.reset = t == 0;
                up.dispatch(in);
                download(out, bo);
                submitWait();
                ByteBuffer ob = MemoryUtil.memByteBuffer(bo.ptr(), (int) bo.size()).order(ByteOrder.LITTLE_ENDIAN);
                float[] o = new float[dw * dh * 4];
                for (int i = 0; i < o.length; i++) o[i] = Float.float16ToFloat(ob.getShort(i * 2));
                outs[t] = o;
            }
            String msgs = up.drainMessages();
            vkDeviceWaitIdle(device);
            return new RunResult(outs, mem, msgs, FsrNative.version());
        }
    }

    // ------------------------------------------------------------------ synthetic scene
    static final float RATIO = 2f;
    static final float VX = 6f, VY = 2f;      // square velocity in DISPLAY pixels per frame
    static final float SQ = 120f;

    static float[] sceneRgb(float x, float y, int t, boolean[] onSquare) {
        float sx = 100f + VX * t, sy = 80f + VY * t;
        boolean in = x >= sx && x < sx + SQ && y >= sy && y < sy + SQ;
        if (onSquare != null) onSquare[0] = in;
        if (in) {
            int cx = (int) Math.floor((x - sx) / 12f), cy = (int) Math.floor((y - sy) / 12f);
            boolean w = ((cx + cy) & 1) == 0;
            return w ? new float[]{0.95f, 0.85f, 0.2f} : new float[]{0.1f, 0.15f, 0.6f};
        }
        float u = x / 640f, v = y / 360f;
        float base = 0.25f + 0.35f * u + 0.15f * v;
        boolean cell = ((int) Math.floor(x / 4f) + (int) Math.floor(y / 4f) & 1) == 0;
        float k = cell ? 0.12f : 0f;
        return new float[]{base + k, base * 0.9f + k, base * 0.7f + k};
    }

    static float JSX = -1, JSY = -1;   // scene sampling sign: -1 = content shifts by +jitter pixels (SDK projection convention); +1 only for the convention check
    static final int SUPER = Integer.getInteger("fsr.super", 3);   // 1 = point-sampled (aliased) render
    static final int NF = 40, LT = NF - 1;
    static final FrameSource SYNTH = (t, jx, jy, color, depth, mv) -> {
        int w = 320, h = 180;
        boolean[] on = new boolean[1];
        for (int j = 0; j < h; j++)
            for (int i = 0; i < w; i++) {
                float x = (i + 0.5f + JSX * jx) * RATIO, y = (j + 0.5f + JSY * jy) * RATIO;   // JSX/JSY = -1: content shifts by +jitter pixels (SDK projection convention)
                float[] c = sceneRgb(x, y, t, on);          // centre sample: depth + motion vector classification
                if (SUPER > 1) {                             // area-averaged colour over the render pixel footprint (what a mip-mapped / MSAA render gives)
                    float[] acc = new float[3];
                    for (int sy = 0; sy < SUPER; sy++) for (int sx = 0; sx < SUPER; sx++) {
                        float[] q = sceneRgb(x + ((sx + 0.5f) / SUPER - 0.5f) * RATIO, y + ((sy + 0.5f) / SUPER - 0.5f) * RATIO, t, null);
                        acc[0] += q[0]; acc[1] += q[1]; acc[2] += q[2];
                    }
                    c = new float[]{acc[0] / (SUPER * SUPER), acc[1] / (SUPER * SUPER), acc[2] / (SUPER * SUPER)};
                }
                int o = j * w + i;
                color[o * 4] = c[0]; color[o * 4 + 1] = c[1]; color[o * 4 + 2] = c[2]; color[o * 4 + 3] = 1f;
                depth[o] = on[0] ? 0.5f : 0.9f;
                mv[o * 2] = on[0] ? -VX / RATIO : 0f;       // current -> previous position, render pixels, unjittered
                mv[o * 2 + 1] = on[0] ? -VY / RATIO : 0f;
            }
    };

    static float[] groundTruth(int t) {
        int w = 640, h = 360;
        float[] g = new float[w * h * 4];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float r = 0, gg = 0, b = 0;
                for (int sy = 0; sy < 4; sy++)
                    for (int sx = 0; sx < 4; sx++) {
                        float[] c = sceneRgb(x + (sx + 0.5f) / 4f, y + (sy + 0.5f) / 4f, t, null);
                        r += c[0]; gg += c[1]; b += c[2];
                    }
                int o = (y * w + x) * 4;
                g[o] = r / 16; g[o + 1] = gg / 16; g[o + 2] = b / 16; g[o + 3] = 1;
            }
        return g;
    }

    /** bilinear upscale of the (jittered) render image of frame t, as the baseline "no upscaler". */
    static float[] bilinear(float[] src, int sw, int sh, int dw, int dh) {
        float[] o = new float[dw * dh * 4];
        for (int y = 0; y < dh; y++)
            for (int x = 0; x < dw; x++) {
                float fx = (x + 0.5f) * sw / dw - 0.5f, fy = (y + 0.5f) * sh / dh - 0.5f;
                int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
                float ax = fx - x0, ay = fy - y0;
                for (int c = 0; c < 3; c++) {
                    float v00 = px(src, sw, sh, x0, y0, c), v10 = px(src, sw, sh, x0 + 1, y0, c), v01 = px(src, sw, sh, x0, y0 + 1, c), v11 = px(src, sw, sh, x0 + 1, y0 + 1, c);
                    o[(y * dw + x) * 4 + c] = (v00 * (1 - ax) + v10 * ax) * (1 - ay) + (v01 * (1 - ax) + v11 * ax) * ay;
                }
                o[(y * dw + x) * 4 + 3] = 1;
            }
        return o;
    }

    static float px(float[] s, int w, int h, int x, int y, int c) {
        x = Math.max(0, Math.min(w - 1, x)); y = Math.max(0, Math.min(h - 1, y));
        return s[(y * w + x) * 4 + c];
    }

    // ------------------------------------------------------------------ metrics
    static double rmse(float[] a, float[] b, int w, int h, int x0, int y0, int x1, int y1) {
        double s = 0; long n = 0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) for (int c = 0; c < 3; c++) {
            double d = a[(y * w + x) * 4 + c] - b[(y * w + x) * 4 + c]; s += d * d; n++;
        }
        return Math.sqrt(s / n);
    }

    static double gradEnergy(float[] a, int w, int x0, int y0, int x1, int y1) {
        double s = 0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
            float l = lum(a, w, x, y);
            double dx = lum(a, w, x + 1, y) - l, dy = lum(a, w, x, y + 1) - l;
            s += dx * dx + dy * dy;
        }
        return s / ((x1 - x0) * (y1 - y0));
    }

    static float lum(float[] a, int w, int x, int y) {
        int o = (y * w + x) * 4;
        return 0.2126f * a[o] + 0.7152f * a[o + 1] + 0.0722f * a[o + 2];
    }

    static double meanAbs(float[] a, float[] b, int w, int x0, int y0, int x1, int y1) {
        double s = 0; long n = 0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) for (int c = 0; c < 3; c++) { s += Math.abs(a[(y * w + x) * 4 + c] - b[(y * w + x) * 4 + c]); n++; }
        return s / n;
    }

    static boolean finite(float[] a) {
        for (float v : a) if (Float.isNaN(v) || Float.isInfinite(v)) return false;
        return true;
    }

    static void png(float[] a, int w, int h, File f) {
        try {
            BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int o = (y * w + x) * 4;
                int r = clamp(a[o]), g = clamp(a[o + 1]), b = clamp(a[o + 2]);
                im.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
            f.getParentFile().mkdirs();
            ImageIO.write(im, "png", f);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static float[] readPng(File f, int w, int h) throws Exception {
        BufferedImage im = ImageIO.read(f);
        float[] a = new float[w * h * 4];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int p = im.getRGB(x, y);
            a[(y * w + x) * 4] = ((p >> 16) & 255) / 255f; a[(y * w + x) * 4 + 1] = ((p >> 8) & 255) / 255f; a[(y * w + x) * 4 + 2] = (p & 255) / 255f; a[(y * w + x) * 4 + 3] = 1;
        }
        return a;
    }

    static int clamp(float v) { return Math.max(0, Math.min(255, Math.round(v * 255f))); }

    static void ck(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) fails++;
    }

    // ------------------------------------------------------------------ main
    public static void main(String[] args) throws Exception {
        String capture = "/home/gooby/Projects/proctor/knowledge/perf/p3/capture1.irohframe";
        String golden = "check";
        boolean force16 = false;
        for (String a : args) {
            if (a.startsWith("--capture=")) capture = a.substring(10);
            else if (a.startsWith("--golden=")) golden = a.substring(9);
            else if (a.startsWith("--dump=")) dumpDir = a.substring(7);
            else if (a.equals("--force16")) force16 = true;
        }
        StringBuilder rep = new StringBuilder();
        try {
            if (!NativeLoader.load()) throw new IllegalStateException("libiroh_fsr unavailable: " + NativeLoader.unavailableReason());
            init(force16, rep);
        } catch (Throwable t) {
            System.out.println(rep);
            System.out.println("# fsrreplay FAIL setup: " + t);
            System.exit(1);
        }
        System.out.print(rep);
        System.out.println("native: " + FsrNative.version());
        System.out.println("estimate 320x180->640x360: " + FsrUpscaler.estimate(320, 180, 640, 360) + " B");

        // ---------------- 1. synthetic
        System.out.println("== synthetic 320x180 -> 640x360 (Performance), 40 frames, moving checker + static textured background");
        RunResult r;
        try {
            r = runFsr(320, 180, 640, 360, NF, SYNTH, false, force16, 0.1f, 1000f, false);
        } catch (Throwable t) {
            System.out.println("CONTEXT/DISPATCH FAILURE on lavapipe: " + t);
            String m = FsrNative.drainMessages();
            if (!m.isEmpty()) System.out.println("FFX messages: " + m);
            System.out.println("# fsrreplay FAIL synthetic (" + t.getClass().getSimpleName() + ")");
            System.exit(1);
            return;
        }
        System.out.println("memory: SDK internal " + r.mem[0] + " B, shim shared images " + r.mem[1] + " B (estimate " + FsrUpscaler.estimate(320, 180, 640, 360) + ")");
        if (!r.messages.isEmpty()) System.out.println("FFX messages:\n" + r.messages);
        float[] last = r.outputs[LT];
        float[] gt = groundTruth(LT);
        float[] rend = new float[320 * 180 * 4];
        float[] jl = JitterSequence.offset(LT, JitterSequence.phaseCount(320, 640));
        SYNTH.render(LT, jl[0], jl[1], rend, new float[320 * 180], new float[320 * 180 * 2]);
        float[] bil = bilinear(rend, 320, 180, 640, 360);
        for (int t = 0; t < NF; t++) ck(finite(r.outputs[t]), "frame " + t + " output has no NaN/Inf");
        // square at the last frame (display px)
        int sx0 = (int) (100 + VX * LT), sy0 = (int) (80 + VY * LT), sx1 = sx0 + 120, sy1 = sy0 + 120;
        double eF = rmse(last, gt, 640, 360, 0, 0, 640, 360), eB = rmse(bil, gt, 640, 360, 0, 0, 640, 360);
        double eFs = rmse(last, gt, 640, 360, sx0 - 6, sy0 - 6, sx1 + 6, sy1 + 6), eBs = rmse(bil, gt, 640, 360, sx0 - 6, sy0 - 6, sx1 + 6, sy1 + 6);
        double eFb = rmse(last, gt, 640, 360, 480, 0, 640, 360), eBb = rmse(bil, gt, 640, 360, 480, 0, 640, 360);
        System.out.printf("RMSE vs 4x4-supersampled ground truth: whole FSR %.4f bilinear %.4f | moving square FSR %.4f bilinear %.4f | static background FSR %.4f bilinear %.4f%n", eF, eB, eFs, eBs, eFb, eBb);
        double gF = gradEnergy(last, 640, 480, 0, 639, 359), gB = gradEnergy(bil, 640, 480, 0, 639, 359), gG = gradEnergy(gt, 640, 480, 0, 639, 359);
        double gFs = gradEnergy(last, 640, sx0, sy0, sx1, sy1), gBs = gradEnergy(bil, 640, sx0, sy0, sx1, sy1), gGs = gradEnergy(gt, 640, sx0, sy0, sx1, sy1);
        System.out.printf("gradient energy: static bg FSR %.5f bilinear %.5f truth %.5f | square FSR %.5f bilinear %.5f truth %.5f%n", gF, gB, gG, gFs, gBs, gGs);
        ck(eF < eB, "FSR is closer to ground truth than bilinear (whole frame)");
        ck(eFb < eBb, "FSR is closer to ground truth than bilinear (static background texture)");
        ck(gF > gB, "FSR keeps more fine static detail than bilinear (gradient energy)");
        double d1 = meanAbs(r.outputs[LT], r.outputs[LT - 1], 640, 480, 0, 640, 360), d2 = meanAbs(r.outputs[LT - 1], r.outputs[LT - 2], 640, 480, 0, 640, 360);
        double db = meanAbs(bil, bilinear(sceneFrame(LT - 1), 320, 180, 640, 360), 640, 480, 0, 640, 360);
        System.out.printf("temporal stability, static region mean |frame N - N-1|: FSR last-prev %.5f prev-prev %.5f | bilinear (jitter flicker) %.5f%n", d1, d2, db);
        ck(d1 < 0.03 && d2 < 0.03, "static region is temporally stable (< 0.03)");
        ck(d1 <= db * 1.1, "FSR is not flickerier than the jittered bilinear baseline");
        // ghosting: pixels the square left behind (frame 7 region t=0..6 trail left of the square, x 100..142) must be background again
        double trail = rmse(last, gt, 640, 360, 160, 140, 325, 200);
        System.out.printf("trail region (where the square was) RMSE vs truth %.4f%n", trail);
        ck(trail < 0.12, "no heavy ghost trail behind the moving square (< 0.12)");
        File dd = new File(dumpDir);
        png(gt, 640, 360, new File(dd, "synth_truth.png")); png(bil, 640, 360, new File(dd, "synth_bilinear.png"));
        png(last, 640, 360, new File(dd, "synth_fsr_last.png")); png(r.outputs[0], 640, 360, new File(dd, "synth_fsr_f0.png"));
        png(rend, 320, 180, new File(dd, "synth_input_last.png"));
        System.out.println("images: " + dd.getAbsolutePath() + "/synth_{truth,bilinear,fsr_last,fsr_f0,input_last}.png");
        // the jitter sign convention is part of the contract with the in-game card: the opposite sign must be clearly worse
        JSX = 1; JSY = 1;
        RunResult wr = runFsr(320, 180, 640, 360, NF, SYNTH, false, force16, 0.1f, 1000f, false);
        JSX = -1; JSY = -1;
        double eW = rmse(wr.outputs[LT], gt, 640, 360, 0, 0, 640, 360);
        System.out.printf("jitter convention: RMSE with the SDK convention %.4f, with the opposite sign %.4f%n", eF, eW);
        ck(eW > eF * 1.15, "opposite jitter sign is clearly worse (content must shift by +jitterOffset pixels in x and y)");
        // golden (8-bit PNG of the f7 output; lavapipe/Mesa pinned)
        File gf = new File("src/test/resources/golden/fsr-synth-640x360.png");
        if (golden.equals("update") || !gf.exists()) {
            if (!golden.equals("update")) System.out.println("golden missing: creating " + gf);
            png(last, 640, 360, gf);
        } else {
            float[] g8 = readPng(gf, 640, 360);
            double mean = meanAbs(last, g8, 640, 0, 0, 640, 360), mx = 0;
            for (int i = 0; i < last.length; i += 4) for (int c = 0; c < 3; c++) mx = Math.max(mx, Math.abs(last[i + c] - g8[i + c]));
            System.out.printf("golden: mean abs diff %.5f (%.2f/255), max %.4f (%.1f/255)%n", mean, mean * 255, mx, mx * 255);
            ck(mean * 255 <= 1.0 && mx * 255 <= 12.0, "matches the stored golden within tolerance (mean <= 1/255, max <= 12/255)");
        }

        // ---------------- 2. captured Navidson frame
        File cap = new File(capture);
        if (cap.isDirectory()) {
            System.out.println("== captured Navidson frame 1280x720 -> 1920x1080 (Quality), zero MVs, no jitter, 4 identical frames");
            int w = 1280, h = 720;
            float[] c3 = readF32(new File(cap, "colortex0.f32"), w * h * 4), dt = readF32(new File(cap, "depthtex0.f32"), w * h * 4);
            double dmin = 9, dmax = -9; for (int i = 0; i < w * h; i++) { dmin = Math.min(dmin, dt[i * 4]); dmax = Math.max(dmax, dt[i * 4]); }
            double cmax = 0; for (int i = 0; i < w * h; i++) for (int c = 0; c < 3; c++) cmax = Math.max(cmax, c3[i * 4 + c]);
            System.out.printf("capture: colortex0 (raster colour; colortex3 is all zeros in this capture) max %.3f, depthtex0 channel0 range [%.4f, %.4f]%n", cmax, dmin, dmax);
            FrameSource fs = (t, jx, jy, color, depth, mv) -> {
                for (int i = 0; i < w * h; i++) {
                    color[i * 4] = Math.min(c3[i * 4], 60000f); color[i * 4 + 1] = Math.min(c3[i * 4 + 1], 60000f); color[i * 4 + 2] = Math.min(c3[i * 4 + 2], 60000f); color[i * 4 + 3] = 1f;
                    depth[i] = Math.max(0f, Math.min(1f, dt[i * 4])); mv[i * 2] = 0; mv[i * 2 + 1] = 0;
                }
            };
            try {
                RunResult cr = runFsr(w, h, 1920, 1080, 4, fs, true, force16, 0.05f, 192f, false);
                System.out.println("memory: SDK internal " + cr.mem[0] + " B + shared " + cr.mem[1] + " B (estimate " + FsrUpscaler.estimate(w, h, 1920, 1080) + ")");
                if (!cr.messages.isEmpty()) System.out.println("FFX messages:\n" + cr.messages);
                float[] o = cr.outputs[3], b = bilinear(c3, w, h, 1920, 1080);
                for (int t = 0; t < 4; t++) ck(finite(cr.outputs[t]), "capture frame " + t + " output has no NaN/Inf");
                double rm = rmse(o, b, 1920, 1080, 0, 0, 1920, 1080), st = meanAbs(cr.outputs[3], cr.outputs[2], 1920, 0, 0, 1920, 1080);
                double gO = gradEnergy(o, 1920, 0, 0, 1919, 1079), gBi = gradEnergy(b, 1920, 0, 0, 1919, 1079);
                System.out.printf("capture result: RMSE FSR vs bilinear %.4f (PSNR %.1f dB), frame-to-frame mean abs diff %.6f, gradient energy FSR %.6f bilinear %.6f%n", rm, 20 * Math.log10(1.0 / Math.max(rm, 1e-9)), st, gO, gBi);
                ck(rm < 0.08, "static capture: output stays close to the plain upscale (RMSE < 0.08)");
                ck(st < 0.005, "static capture: output is stable over identical frames (< 0.005)");
                png(o, 1920, 1080, new File(dd, "navidson_fsr.png")); png(b, 1920, 1080, new File(dd, "navidson_bilinear.png"));
                float[] in720 = new float[w * h * 4]; for (int i = 0; i < in720.length; i++) in720[i] = i % 4 == 3 ? 1f : c3[i];
                png(in720, w, h, new File(dd, "navidson_input.png"));
                System.out.println("images: " + dd.getAbsolutePath() + "/navidson_{input,fsr,bilinear}.png");
                System.out.println("this test CAN show: the context and all passes run on a real Navidson raster buffer (colortex0 + depthtex0), no NaN/Inf, colour/exposure preserved, stable output for a static camera, memory at 720p->1080p.");
                System.out.println("this test CANNOT show: temporal reconstruction quality (the capture is unjittered, one frame), motion-vector handling, ghosting, reactive masks: needs the in-game jitter + MV card (P4-1).");
            } catch (Throwable t) {
                System.out.println("CAPTURE RUN FAILURE: " + t);
                fails++;
            }
        } else System.out.println("capture dir missing, skipped: " + cap);
        System.out.println("device memory used by this harness: " + allocated + " B of cap " + CAP);
        System.out.println("# fsrreplay " + (fails == 0 ? "PASS" : "FAIL " + fails + " checks"));
        System.exit(fails == 0 ? 0 : 1);
    }

    static float[] sceneFrame(int t) {
        float[] c = new float[320 * 180 * 4];
        float[] j = JitterSequence.offset(t, JitterSequence.phaseCount(320, 640));
        SYNTH.render(t, j[0], j[1], c, new float[320 * 180], new float[320 * 180 * 2]);
        return c;
    }

    static float[] readF32(File f, int n) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(f.toPath())).order(ByteOrder.LITTLE_ENDIAN);
        float[] a = new float[n];
        b.asFloatBuffer().get(a);
        return a;
    }
}
