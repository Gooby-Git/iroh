package dev.elemore.iroh.temporal;

import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4d;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * P4-1a GPU golden test on LAVAPIPE (CPU Vulkan; the loader is restricted to the lavapipe ICD by the Gradle task, so
 * the real GPU is never touched). A two-frame scene (ground plane at y = 0, a 4x4x4 box, sky at the cleared depth 1.0)
 * is rendered as a depth image analytically for frame 2's camera (jittered, as the game would); the camera moved
 * between frames (strafe + rise + yaw). The REAL {@link VelocityPass} (velocity.comp through the game's shaderc
 * settings) computes motion vectors; each pixel is compared with the analytic answer: the scene point under that
 * pixel (ray-cast, unjittered) projected with frame 1's camera, prevUv - curUv in Iroh's axes. Then the debug view
 * (mode velocity) with a static camera must be black everywhere. Debug PNGs go to build/p4-1a/.
 * {@code ./gradlew velocityGoldenTest}; last line {@code # velocitygolden PASS n/n}.
 */
public final class VelocityGoldenTest {
    static final int W = 96, H = 54;
    static int pass, total;

    static void check(String what, boolean ok) {
        total++;
        if (ok) pass++;
        else System.out.println("FAIL " + what);
    }

    static void vk(int r, String what) {
        if (r != VK_SUCCESS)
            throw new IllegalStateException(what + " failed: " + r);
    }

    // --- tiny headless Vulkan --------------------------------------------------------------------------------------
    static VkInstance instance;
    static VkPhysicalDevice phys;
    static VkDevice device;
    static VkQueue queue;
    static int family;
    static long pool;

    static void init() {
        try (MemoryStack s = MemoryStack.stackPush()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(s).sType$Default().pApplicationName(s.UTF8("iroh-velocity-golden")).apiVersion(VK12.VK_API_VERSION_1_2);
            VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(s).sType$Default().pApplicationInfo(app);
            PointerBuffer pi = s.mallocPointer(1);
            vk(vkCreateInstance(ici, null, pi), "vkCreateInstance");
            instance = new VkInstance(pi.get(0), ici);
            var n = s.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, n, null);
            PointerBuffer pds = s.mallocPointer(n.get(0));
            vkEnumeratePhysicalDevices(instance, n, pds);
            phys = new VkPhysicalDevice(pds.get(0), instance);
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(s);
            vkGetPhysicalDeviceProperties(phys, props);
            System.out.println("device: " + props.deviceNameString() + " (type " + props.deviceType() + ")");
            if (props.deviceType() != VK_PHYSICAL_DEVICE_TYPE_CPU)
                throw new IllegalStateException("not a CPU device: refusing to run on real hardware (check VK_DRIVER_FILES)");
            vkGetPhysicalDeviceQueueFamilyProperties(phys, n, null);
            VkQueueFamilyProperties.Buffer qf = VkQueueFamilyProperties.calloc(n.get(0), s);
            vkGetPhysicalDeviceQueueFamilyProperties(phys, n, qf);
            family = -1;
            for (int i = 0; i < qf.capacity() && family < 0; i++)
                if ((qf.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0)
                    family = i;
            VkDeviceQueueCreateInfo.Buffer q = VkDeviceQueueCreateInfo.calloc(1, s);
            q.get(0).sType$Default().queueFamilyIndex(family).pQueuePriorities(s.floats(1f));
            VkPhysicalDeviceFeatures f = VkPhysicalDeviceFeatures.calloc(s).shaderStorageImageExtendedFormats(true);
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(s).sType$Default().pQueueCreateInfos(q).pEnabledFeatures(f);
            PointerBuffer pd = s.mallocPointer(1);
            vk(vkCreateDevice(phys, dci, null, pd), "vkCreateDevice");
            device = new VkDevice(pd.get(0), phys, dci, VK12.VK_API_VERSION_1_2);
            PointerBuffer pq = s.mallocPointer(1);
            vkGetDeviceQueue(device, family, 0, pq);
            queue = new VkQueue(pq.get(0), device);
            VkCommandPoolCreateInfo cpi = VkCommandPoolCreateInfo.calloc(s).sType$Default().queueFamilyIndex(family);
            LongBuffer pl = s.mallocLong(1);
            vk(vkCreateCommandPool(device, cpi, null, pl), "pool");
            pool = pl.get(0);
        }
    }

    static int memType(int bits) {
        try (MemoryStack s = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties mp = VkPhysicalDeviceMemoryProperties.calloc(s);
            vkGetPhysicalDeviceMemoryProperties(phys, mp);
            int want = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            for (int i = 0; i < mp.memoryTypeCount(); i++)
                if ((bits & (1 << i)) != 0 && (mp.memoryTypes(i).propertyFlags() & want) == want)
                    return i;
            for (int i = 0; i < mp.memoryTypeCount(); i++)
                if ((bits & (1 << i)) != 0)
                    return i;
        }
        throw new IllegalStateException("no memory type");
    }

    record Img(long image, long memory, long view) {}

    static Img image(int format, int usage) {
        try (MemoryStack s = MemoryStack.stackPush()) {
            VkImageCreateInfo ci = VkImageCreateInfo.calloc(s).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(format)
                    .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            ci.extent().set(W, H, 1);
            LongBuffer p = s.mallocLong(1);
            vk(vkCreateImage(device, ci, null, p), "image");
            long img = p.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(s);
            vkGetImageMemoryRequirements(device, img, req);
            VkMemoryAllocateInfo ai = VkMemoryAllocateInfo.calloc(s).sType$Default().allocationSize(req.size()).memoryTypeIndex(memType(req.memoryTypeBits()));
            vk(vkAllocateMemory(device, ai, null, p), "alloc");
            long mem = p.get(0);
            vkBindImageMemory(device, img, mem, 0);
            VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(s).sType$Default().image(img).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
            vi.subresourceRange().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1);
            vk(vkCreateImageView(device, vi, null, p), "view");
            return new Img(img, mem, p.get(0));
        }
    }

    record Buf(long buffer, long memory, ByteBuffer map) {}

    static Buf buffer(int bytes) {
        try (MemoryStack s = MemoryStack.stackPush()) {
            VkBufferCreateInfo ci = VkBufferCreateInfo.calloc(s).sType$Default().size(bytes)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer p = s.mallocLong(1);
            vk(vkCreateBuffer(device, ci, null, p), "buffer");
            long b = p.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(s);
            vkGetBufferMemoryRequirements(device, b, req);
            VkMemoryAllocateInfo ai = VkMemoryAllocateInfo.calloc(s).sType$Default().allocationSize(req.size()).memoryTypeIndex(memType(req.memoryTypeBits()));
            vk(vkAllocateMemory(device, ai, null, p), "alloc");
            long mem = p.get(0);
            vkBindBufferMemory(device, b, mem, 0);
            PointerBuffer pp = s.mallocPointer(1);
            vkMapMemory(device, mem, 0, bytes, 0, pp);
            return new Buf(b, mem, MemoryUtil.memByteBuffer(pp.get(0), bytes));
        }
    }

    static void barrier(VkCommandBuffer cb, long img, int oldL, int newL, int srcStage, int srcAcc, int dstStage, int dstAcc) {
        try (MemoryStack s = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, s).sType$Default().oldLayout(oldL).newLayout(newL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(img)
                    .srcAccessMask(srcAcc).dstAccessMask(dstAcc);
            b.subresourceRange().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1);
            vkCmdPipelineBarrier(cb, srcStage, dstStage, 0, null, null, b);
        }
    }

    static void copy(VkCommandBuffer cb, long buf, long img, boolean toImage) {
        try (MemoryStack s = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, s);
            r.get(0).imageSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
            r.get(0).imageExtent().set(W, H, 1);
            if (toImage) vkCmdCopyBufferToImage(cb, buf, img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, r);
            else vkCmdCopyImageToBuffer(cb, img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buf, r);
        }
    }

    // --- the scene ---------------------------------------------------------------------------------------------------
    /** nearest hit distance t along dir from origin (camera-relative), or +inf: ground y = -camY, box [bx..bx+4]^3 */
    static double cast(double[] cam, double dx, double dy, double dz) {
        double best = Double.POSITIVE_INFINITY;
        if (dy < -1e-9) {
            double t = (0 - cam[1]) / dy;
            if (t > 0) best = t;
        }
        double[] lo = {3 - cam[0], 0 - cam[1], -14 - cam[2]}, hi = {7 - cam[0], 4 - cam[1], -10 - cam[2]};
        double[] d = {dx, dy, dz};
        double tmin = 0, tmax = Double.POSITIVE_INFINITY;
        boolean hit = true;
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-12) {
                if (0 < lo[a] || 0 > hi[a]) { hit = false; break; }
            } else {
                double t1 = lo[a] / d[a], t2 = hi[a] / d[a];
                tmin = Math.max(tmin, Math.min(t1, t2));
                tmax = Math.min(tmax, Math.max(t1, t2));
            }
        }
        if (hit && tmax >= tmin && tmin > 0) best = Math.min(best, tmin);
        return best;
    }

    public static void main(String[] args) throws Exception {
        init();
        float fov = 70f;
        Matrix4f p = new Matrix4f().perspective((float) Math.toRadians(fov), (float) W / H, 0.05f, Float.POSITIVE_INFINITY, true);
        // frame 1 (previous) and frame 2 (current) cameras
        double[] camPrev = {0.0, 1.62, 0.0}, camCur = {0.35, 1.70, -0.10};
        Quaternionf rPrev = new Quaternionf().rotateY((float) Math.toRadians(-8)).rotateX((float) Math.toRadians(-12));
        Quaternionf rCur = new Quaternionf().rotateY((float) Math.toRadians(-6.5)).rotateX((float) Math.toRadians(-12.5));
        // view rotation = conjugate of the camera orientation (GameRenderer: camera.rotation().conjugate())
        Matrix4d prPrev = new Matrix4d().set(p).mul(new Matrix4d().set(new Matrix4f().rotation(new Quaternionf(rPrev).conjugate())));
        Matrix4d prCur = new Matrix4d().set(p).mul(new Matrix4d().set(new Matrix4f().rotation(new Quaternionf(rCur).conjugate())));
        Matrix4d invCur = new Matrix4d(prCur).invert();
        float jx = 0.3125f, jy = -0.2222f; // a jitter in pixels (the depth below is rendered jittered)
        double jnx = 2.0 * jx / W, jny = 2.0 * jy / H;

        // depth for frame 2 (jittered), plus the analytic expected motion (unjittered surface point)
        float[] depth = new float[W * H];
        double[][] expect = new double[W * H][];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                double nx = (x + 0.5) / W * 2 - 1, ny = (y + 0.5) / H * 2 - 1;
                // jittered render: the pixel centre samples the unjittered scene at ndc - jitter
                double ux = nx - jnx, uy = ny - jny;
                Vector4d far = new Vector4d(ux, uy, 0.5, 1).mul(invCur);
                double dx = far.x / far.w, dy = far.y / far.w, dz = far.z / far.w;
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                dx /= len; dy /= len; dz /= len;
                double t = cast(camCur, dx, dy, dz);
                int i = y * W + x;
                if (Double.isInfinite(t)) {
                    depth[i] = 1.0f; // sky: cleared depth
                    // expected: direction reprojected with rotation only
                    Vector4d c = new Vector4d(dx, dy, dz, 0).mul(prPrev);
                    expect[i] = new double[]{(c.x / c.w - ux) * 0.5, (c.y / c.w - uy) * 0.5, 1};
                } else {
                    double wx = camCur[0] + dx * t, wy = camCur[1] + dy * t, wz = camCur[2] + dz * t;
                    Vector4d c = new Vector4d(dx * t, dy * t, dz * t, 1).mul(prCur);
                    depth[i] = (float) (c.z / c.w);
                    double[] pu = TemporalMath.projectUv(prPrev, camPrev[0], camPrev[1], camPrev[2], wx, wy, wz);
                    expect[i] = new double[]{pu[0] - (ux + 1) * 0.5, pu[1] - (uy + 1) * 0.5, 0};
                }
            }

        Img dImg = image(VK_FORMAT_R32_SFLOAT, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        Img vImg = image(VK_FORMAT_R16G16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        Img cImg = image(VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        Buf up = buffer(W * H * 4), vOut = buffer(W * H * 4), cOut = buffer(W * H * 4);
        for (int i = 0; i < W * H; i++)
            up.map().putFloat(i * 4, depth[i]);

        // hand-only depth (what the depth buffer holds at world end after vanilla's pre-hand clear): a block of
        // hand texels in the lower right at depth 0.2, everything else cleared to 1.0
        handImgAlways = image(VK_FORMAT_R32_SFLOAT, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        handUp = buffer(W * H * 4);
        boolean[] isHand = new boolean[W * H];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                isHand[y * W + x] = x >= W - 30 && y < 20;
                handUp.map().putFloat((y * W + x) * 4, isHand[y * W + x] ? 0.2f : 1.0f);
            }

        VelocityPass pass2 = new VelocityPass(device, 1);
        Matrix4d m = TemporalMath.reprojection(prPrev, prCur, camCur[0] - camPrev[0], camCur[1] - camPrev[1], camCur[2] - camPrev[2], new Matrix4d());

        runFrame(dImg, vImg, cImg, up, vOut, cOut, pass2, m, (float) jnx, (float) jny, jx, jy, 1, true);

        double maxErr = 0, maxErrSky = 0, sumAbs = 0;
        int sky = 0;
        for (int i = 0; i < W * H; i++) {
            float mx = Float.float16ToFloat(vOut.map().getShort(i * 4)), my = Float.float16ToFloat(vOut.map().getShort(i * 4 + 2));
            double ex = (mx - expect[i][0]) * W, ey = (my - expect[i][1]) * H;
            double e = Math.max(Math.abs(ex), Math.abs(ey));
            if (expect[i][2] == 1) { maxErrSky = Math.max(maxErrSky, e); sky++; } else maxErr = Math.max(maxErr, e);
            sumAbs += Math.abs(mx * W) + Math.abs(my * H);
        }
        System.out.printf("surface max err %.4f px, sky max err %.4f px (%d sky pixels), mean |mv| %.3f px%n", maxErr, maxErrSky, sky, sumAbs / (W * H));
        check("scene has sky and surfaces", sky > 50 && sky < W * H - 500);
        check("motion is not trivially zero", sumAbs / (W * H) > 0.5);
        check("GPU velocity == analytic on surfaces (<= 0.05 px)", maxErr <= 0.05);
        check("GPU velocity == analytic on the sky (<= 0.05 px)", maxErrSky <= 0.05);
        // CPU twin agrees with the shader
        double maxTwin = 0;
        double[] mv = new double[2];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                TemporalMath.motionUv(m, (x + 0.5) / W * 2 - 1 - jnx, (y + 0.5) / H * 2 - 1 - jny, depth[i], mv);
                float mx = Float.float16ToFloat(vOut.map().getShort(i * 4));
                maxTwin = Math.max(maxTwin, Math.abs(mx - mv[0]) * W);
            }
        check("TemporalMath.motionUv twin == shader (" + maxTwin + " px)", maxTwin <= 0.02);
        writePng(cOut, "velocity-debug-moving.png");

        // with the hand: hand texels 0, everything else as before (scene depth from the pre-hand copy)
        handImg = handImgAlways;
        runFrame(dImg, vImg, cImg, up, vOut, cOut, pass2, m, (float) jnx, (float) jny, jx, jy, 1, false);
        handImg = null;
        double maxHand = 0, maxRest = 0;
        for (int i = 0; i < W * H; i++) {
            float mx = Float.float16ToFloat(vOut.map().getShort(i * 4)), my = Float.float16ToFloat(vOut.map().getShort(i * 4 + 2));
            if (isHand[i]) maxHand = Math.max(maxHand, Math.max(Math.abs(mx), Math.abs(my)));
            else maxRest = Math.max(maxRest, Math.max(Math.abs(mx - expect[i][0]) * W, Math.abs(my - expect[i][1]) * H));
        }
        check("hand texels: zero motion", maxHand == 0);
        check("non-hand texels from the pre-hand scene depth == analytic (" + maxRest + " px)", maxRest <= 0.05);
        writePng(cOut, "velocity-debug-hand.png");

        // static camera: debug view black everywhere (Proctor U3 offline analogue)
        runFrame(dImg, vImg, cImg, up, vOut, cOut, pass2, new Matrix4d(), (float) jnx, (float) jny, jx, jy, 1, false);
        boolean black = true;
        for (int i = 0; i < W * H; i++)
            black &= (cOut.map().getInt(i * 4) & 0x00FFFFFF) == 0;
        check("static camera: velocity debug view all black", black);
        writePng(cOut, "velocity-debug-static.png");
        // depth view runs (mode 3): sky white, near ground darker
        runFrame(dImg, vImg, cImg, up, vOut, cOut, pass2, new Matrix4d(), (float) jnx, (float) jny, jx, jy, 3, false);
        int skyIdx = -1, groundIdx = (2) * W + W / 2;
        for (int i = W * H - 1; i >= 0 && skyIdx < 0; i--) if (expect[i][2] == 1) skyIdx = i;
        int skyG = cOut.map().get(skyIdx * 4) & 0xFF, gG = cOut.map().get(groundIdx * 4) & 0xFF;
        check("depth view: sky brighter than near ground (" + skyG + " > " + gG + ")", skyG > 250 && gG < skyG);
        writePng(cOut, "depth-debug.png");

        pass2.destroy();
        vkDeviceWaitIdle(device);
        System.out.println("# velocitygolden " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }

    static boolean depthUploaded;
    /** when set, the run uses it as the end-of-world depth (hand only) and the scene depth as the pre-hand copy */
    static Img handImg, handImgAlways;
    static Buf handUp;

    static void runFrame(Img d, Img v, Img c, Buf up, Buf vOut, Buf cOut, VelocityPass vp, Matrix4d m,
                         float jnx, float jny, float jx, float jy, int mode, boolean first) {
        try (MemoryStack s = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            PointerBuffer pcb = s.mallocPointer(1);
            vk(vkAllocateCommandBuffers(device, ai, pcb), "cb");
            VkCommandBuffer cb = new VkCommandBuffer(pcb.get(0), device);
            vkBeginCommandBuffer(cb, VkCommandBufferBeginInfo.calloc(s).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
            int top = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, tr = VK_PIPELINE_STAGE_TRANSFER_BIT, cs = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
            if (!depthUploaded) {
                barrier(cb, d.image(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, top, 0, tr, VK_ACCESS_TRANSFER_WRITE_BIT);
                copy(cb, up.buffer(), d.image(), true);
                barrier(cb, d.image(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, tr, VK_ACCESS_TRANSFER_WRITE_BIT, cs, VK_ACCESS_SHADER_READ_BIT);
                barrier(cb, handImgAlways.image(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, top, 0, tr, VK_ACCESS_TRANSFER_WRITE_BIT);
                copy(cb, handUp.buffer(), handImgAlways.image(), true);
                barrier(cb, handImgAlways.image(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, tr, VK_ACCESS_TRANSFER_WRITE_BIT, cs, VK_ACCESS_SHADER_READ_BIT);
                depthUploaded = true;
            }
            barrier(cb, v.image(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, top, 0, cs, VK_ACCESS_SHADER_WRITE_BIT);
            barrier(cb, c.image(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, top, 0, tr, VK_ACCESS_TRANSFER_WRITE_BIT);
            VkClearColorValue grey = VkClearColorValue.calloc(s);
            grey.float32(0, 0.5f).float32(1, 0.5f).float32(2, 0.5f).float32(3, 1f);
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, s);
            range.get(0).set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1);
            vkCmdClearColorImage(cb, c.image(), VK_IMAGE_LAYOUT_GENERAL, grey, range);
            barrier(cb, c.image(), VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, tr, VK_ACCESS_TRANSFER_WRITE_BIT, cs, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);

            if (handImg != null)
                vp.record(cb, 0, handImg.view(), d.view(), v.view(), c.view(), W, H, m, jnx, jny, jx, jy, mode);
            else
                vp.record(cb, 0, d.view(), 0L, v.view(), c.view(), W, H, m, jnx, jny, jx, jy, mode);

            barrier(cb, v.image(), VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, cs, VK_ACCESS_SHADER_WRITE_BIT, tr, VK_ACCESS_TRANSFER_READ_BIT);
            barrier(cb, c.image(), VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, cs, VK_ACCESS_SHADER_WRITE_BIT, tr, VK_ACCESS_TRANSFER_READ_BIT);
            copy(cb, vOut.buffer(), v.image(), false);
            copy(cb, cOut.buffer(), c.image(), false);
            vkEndCommandBuffer(cb);
            VkSubmitInfo si = VkSubmitInfo.calloc(s).sType$Default().pCommandBuffers(s.pointers(cb));
            vk(vkQueueSubmit(queue, si, VK_NULL_HANDLE), "submit");
            vkQueueWaitIdle(queue);
            vkFreeCommandBuffers(device, pool, cb);
        }
    }

    static void writePng(Buf b, String name) throws Exception {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int i = (y * W + x) * 4;
                int r = b.map().get(i) & 0xFF, g = b.map().get(i + 1) & 0xFF, bl = b.map().get(i + 2) & 0xFF;
                img.setRGB(x, H - 1 - y, (r << 16) | (g << 8) | bl); // Iroh rows are bottom-up: flip for viewing
            }
        File dir = new File("build/p4-1a");
        dir.mkdirs();
        ImageIO.write(img, "png", new File(dir, name));
    }
}
