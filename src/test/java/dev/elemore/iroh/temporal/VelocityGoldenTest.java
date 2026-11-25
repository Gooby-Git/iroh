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
    static final double[] BOX_LO = {3, 0, -14}, BOX_HI = {7, 4, -10};

    /** nearest hit distance t along dir from origin (camera-relative), or +inf; hitBox[0] = the box was hit */
    static double cast(double[] cam, double dx, double dy, double dz, boolean[] hitBox) {
        double best = Double.POSITIVE_INFINITY;
        hitBox[0] = false;
        if (dy < -1e-9) {
            double t = (0 - cam[1]) / dy;
            if (t > 0) best = t;
        }
        double[] lo = {BOX_LO[0] - cam[0], BOX_LO[1] - cam[1], BOX_LO[2] - cam[2]}, hi = {BOX_HI[0] - cam[0], BOX_HI[1] - cam[1], BOX_HI[2] - cam[2]};
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
        if (hit && tmax >= tmin && tmin > 0 && tmin < best) { best = tmin; hitBox[0] = true; }
        return best;
    }

    static Img dImg, hImg, vImg, cImg, mImg, rImg, tImg, ocImg, odImg, pImg, sImg;
    static Buf dUp, hUp, cUp, ocUp, odUp, pUp, sUp, vOut, cOut, mOut, rOut, tOut;

    static float f16(Buf b, int i, int c) { return Float.float16ToFloat(b.map().getShort(i * 4 + c * 2)); }
    static float r8(Buf b, int i) { return (b.map().get(i) & 0xFF) / 255f; }

    public static void main(String[] args) throws Exception {
        init();
        float fov = 70f;
        Matrix4f p = new Matrix4f().perspective((float) Math.toRadians(fov), (float) W / H, 0.05f, Float.POSITIVE_INFINITY, true);
        double[] camPrev = {0.0, 1.62, 0.0}, camCur = {0.35, 1.70, -0.10};
        Quaternionf rPrev = new Quaternionf().rotateY((float) Math.toRadians(-8)).rotateX((float) Math.toRadians(-12));
        Quaternionf rCur = new Quaternionf().rotateY((float) Math.toRadians(-6.5)).rotateX((float) Math.toRadians(-12.5));
        // view rotation = conjugate of the camera orientation (GameRenderer: camera.rotation().conjugate())
        Matrix4d prPrev = new Matrix4d().set(p).mul(new Matrix4d().set(new Matrix4f().rotation(new Quaternionf(rPrev).conjugate())));
        Matrix4d prCur = new Matrix4d().set(p).mul(new Matrix4d().set(new Matrix4f().rotation(new Quaternionf(rCur).conjugate())));
        Matrix4d invCur = new Matrix4d(prCur).invert();
        float jx = 0.3125f, jy = -0.2222f; // a jitter in pixels (the depth below is rendered jittered)
        double jnx = 2.0 * jx / W, jny = 2.0 * jy / H;
        // the box is an OBJECT that moved since the previous frame: +0.3 x, -0.1 z, body yaw +6 degrees about its base centre
        double[] objCur = {5.0, 0.0, -12.0}, objPrev = {4.7, 0.0, -11.9};
        double yawCur = 36.0, yawPrev = 30.0;

        float[] depth = new float[W * H];
        double[][] expect = new double[W * H][];      // camera-only expectation
        double[][] expectObj = new double[W * H][];   // with the box's own motion (box pixels only)
        boolean[] onBox = new boolean[W * H];
        boolean[] hb = new boolean[1];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                double nx = (x + 0.5) / W * 2 - 1, ny = (y + 0.5) / H * 2 - 1;
                double ux = nx - jnx, uy = ny - jny;     // jittered render: the pixel centre samples the scene at ndc - jitter
                Vector4d far = new Vector4d(ux, uy, 0.5, 1).mul(invCur);
                double dx = far.x / far.w, dy = far.y / far.w, dz = far.z / far.w;
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                dx /= len; dy /= len; dz /= len;
                double t = cast(camCur, dx, dy, dz, hb);
                int i = y * W + x;
                onBox[i] = hb[0];
                if (Double.isInfinite(t)) {
                    depth[i] = 1.0f; // sky: cleared depth
                    Vector4d c = new Vector4d(dx, dy, dz, 0).mul(prPrev);
                    expect[i] = new double[]{(c.x / c.w - ux) * 0.5, (c.y / c.w - uy) * 0.5, 1};
                } else {
                    double wx = camCur[0] + dx * t, wy = camCur[1] + dy * t, wz = camCur[2] + dz * t;
                    Vector4d c = new Vector4d(dx * t, dy * t, dz * t, 1).mul(prCur);
                    depth[i] = (float) (c.z / c.w);
                    double[] pu = TemporalMath.projectUv(prPrev, camPrev[0], camPrev[1], camPrev[2], wx, wy, wz);
                    expect[i] = new double[]{pu[0] - (ux + 1) * 0.5, pu[1] - (uy + 1) * 0.5, 0};
                    if (hb[0]) {
                        // rigid: prevLocal = Ry(yawCur - yawPrev) * (w - objCur); prevWorld = objPrev + prevLocal
                        double a = Math.toRadians(yawCur - yawPrev), lx = wx - objCur[0], ly = wy - objCur[1], lz = wz - objCur[2];
                        double px = Math.cos(a) * lx + Math.sin(a) * lz, pz = -Math.sin(a) * lx + Math.cos(a) * lz;
                        double[] po = TemporalMath.projectUv(prPrev, camPrev[0], camPrev[1], camPrev[2], objPrev[0] + px, objPrev[1] + ly, objPrev[2] + pz);
                        expectObj[i] = new double[]{po[0] - (ux + 1) * 0.5, po[1] - (uy + 1) * 0.5};
                    }
                }
            }

        int SR = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, ST = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        dImg = image(VK_FORMAT_R32_SFLOAT, SR);
        hImg = image(VK_FORMAT_R32_SFLOAT, SR);
        odImg = image(VK_FORMAT_R32_SFLOAT, SR);
        pImg = image(VK_FORMAT_R32_SFLOAT, SR);   // FSR-Q7 phase depth
        sImg = image(VK_FORMAT_R32_SFLOAT, SR);   // FSR-Q6 SDK reactive
        ocImg = image(VK_FORMAT_R8G8B8A8_UNORM, SR);
        vImg = image(VK_FORMAT_R16G16_SFLOAT, ST);
        cImg = image(VK_FORMAT_R8G8B8A8_UNORM, ST | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        mImg = image(VK_FORMAT_R32_SFLOAT, ST);
        rImg = image(VK_FORMAT_R8_UNORM, ST);
        tImg = image(VK_FORMAT_R8_UNORM, ST);
        int n4 = W * H * 4;
        pUp = buffer(n4); sUp = buffer(n4); dUp = buffer(n4); hUp = buffer(n4); cUp = buffer(n4); ocUp = buffer(n4); odUp = buffer(n4);
        vOut = buffer(n4); cOut = buffer(n4); mOut = buffer(n4); rOut = buffer(W * H); tOut = buffer(W * H);

        // hand-only end depth: a block of hand texels in the lower right at depth 0.2, everything else cleared to 1.0
        boolean[] isHand = new boolean[W * H];
        // masks: the opaque copy differs from the final colour in a "particles" rectangle (no depth: reactive) and a
        // "water" rectangle where a translucent surface also wrote depth in front of the opaque scene (T&C)
        boolean[] particles = new boolean[W * H], water = new boolean[W * H], weather = new boolean[W * H];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                isHand[i] = x >= W - 30 && y < 20;
                particles[i] = x >= 4 && x < 24 && y >= 30 && y < 46 && !isHand[i];
                water[i] = x >= 40 && x < 60 && y >= 2 && y < 12 && !isHand[i] && depth[i] < 0.9999f;
                weather[i] = x >= 60 && x < 80 && y >= 2 && y < 12 && !isHand[i] && depth[i] < 0.9999f;   // clouds / weather: drawn after the phase copy
                dUp.map().putFloat(i * 4, depth[i]);
                hUp.map().putFloat(i * 4, isHand[i] ? 0.2f : 1.0f);
                float odv = water[i] ? Math.min(1f, depth[i] + 0.0005f) : weather[i] ? Math.min(1f, depth[i] + 0.001f) : depth[i];
                odUp.map().putFloat(i * 4, odv);                                                         // opaque behind the water / weather
                pUp.map().putFloat(i * 4, weather[i] ? Math.min(1f, depth[i] + 0.0005f) : water[i] ? depth[i] : odv);         // phase copy: weather not drawn yet
                sUp.map().putFloat(i * 4, 0.6f);                                                         // a constant SDK reactive value
                cUp.map().putInt(i * 4, 0xFF808080);                                                    // final colour: grey
                ocUp.map().putInt(i * 4, particles[i] || water[i] || weather[i] ? 0xFF000000 | 0x202020 : 0xFF808080); // opaque copy
            }
        upload(dImg, dUp); upload(hImg, hUp); upload(odImg, odUp); upload(ocImg, ocUp); upload(pImg, pUp); upload(sImg, sUp);

        VelocityPass vp = new VelocityPass(device, 2);
        Matrix4d m = TemporalMath.reprojection(prPrev, prCur, camCur[0] - camPrev[0], camCur[1] - camPrev[1], camCur[2] - camPrev[2], new Matrix4d());
        VelocityPass.Frame f = new VelocityPass.Frame();
        f.width = W; f.height = H; f.reproject = m; f.invCurPR = invCur;
        f.jitterNdcX = (float) jnx; f.jitterNdcY = (float) jny; f.jitterPxX = jx; f.jitterPxY = jy;

        // 1. camera only, no hand, no masks, no objects
        f.mode = 1;
        run(vp, f, false, false);
        double maxErr = 0, maxErrSky = 0, sumAbs = 0;
        int sky = 0;
        for (int i = 0; i < W * H; i++) {
            float mx = f16(vOut, i, 0), my = f16(vOut, i, 1);
            double e = Math.max(Math.abs((mx - expect[i][0]) * W), Math.abs((my - expect[i][1]) * H));
            if (expect[i][2] == 1) { maxErrSky = Math.max(maxErrSky, e); sky++; } else maxErr = Math.max(maxErr, e);
            sumAbs += Math.abs(mx * W) + Math.abs(my * H);
        }
        System.out.printf("surface max err %.4f px, sky max err %.4f px (%d sky pixels), mean |mv| %.3f px%n", maxErr, maxErrSky, sky, sumAbs / (W * H));
        check("scene has sky and surfaces", sky > 50 && sky < W * H - 500);
        check("motion is not trivially zero", sumAbs / (W * H) > 0.5);
        check("GPU velocity == analytic on surfaces (<= 0.05 px)", maxErr <= 0.05);
        check("GPU velocity == analytic on the sky (<= 0.05 px)", maxErrSky <= 0.05);
        double maxTwin = 0;
        double[] mv = new double[2];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                TemporalMath.motionUv(m, (x + 0.5) / W * 2 - 1 - jnx, (y + 0.5) / H * 2 - 1 - jny, depth[i], mv);
                maxTwin = Math.max(maxTwin, Math.abs(f16(vOut, i, 0) - mv[0]) * W);
            }
        check("TemporalMath.motionUv twin == shader (" + maxTwin + " px)", maxTwin <= 0.02);
        double maxDepth = 0;
        for (int i = 0; i < W * H; i++) maxDepth = Math.max(maxDepth, Math.abs(mOut.map().getFloat(i * 4) - depth[i]));
        check("merged depth = scene depth without a hand", maxDepth == 0);
        writePng(cOut, "velocity-debug-moving.png");

        // 2. moving object: ObjectMotion packs the box (two frames of history), the pass takes its motion on box pixels
        ObjectMotion om = new ObjectMotion();
        om.beginFrame(1);
        om.submit(7L << 8, objPrev[0], objPrev[1], objPrev[2], yawPrev, new double[]{BOX_LO[0] - 0.3, BOX_LO[1], BOX_LO[2] + 0.1}, new double[]{BOX_HI[0] - 0.3, BOX_HI[1], BOX_HI[2] + 0.1});
        om.build(camPrev, camPrev, null, f);
        om.beginFrame(2);
        om.submit(7L << 8, objCur[0], objCur[1], objCur[2], yawCur, BOX_LO, BOX_HI);
        om.submit(9L << 8, -20, 0, -30, 0, new double[]{-20.3, 0, -30.3}, new double[]{-19.7, 1.8, -29.7}); // new entity: camera-only + T&C
        om.build(camCur, camPrev, prPrev, f);
        check("object list: moving box + new entity", f.objectCount == 2);
        f.mode = 2;
        run(vp, f, false, false);
        double maxObj = 0;
        int objPx = 0, grazed = 0;
        for (int i = 0; i < W * H; i++) {
            if (!onBox[i]) continue;
            objPx++;
            double e = Math.max(Math.abs((f16(vOut, i, 0) - expectObj[i][0]) * W), Math.abs((f16(vOut, i, 1) - expectObj[i][1]) * H));
            maxObj = Math.max(maxObj, e);
            if (r8(cOut, i * 4 + 2) > 0.5f) grazed++;
        }
        System.out.printf("object: %d box pixels, max err %.4f px%n", objPx, maxObj);
        check("box pixels carry the object's rigid motion (<= 0.05 px)", objPx > 100 && maxObj <= 0.05);
        check("debug view marks object pixels (blue)", grazed == objPx);
        boolean tcObj = true;
        for (int i = 0; i < W * H; i++) if (onBox[i]) tcObj &= Math.abs(r8(tOut, i) - om.tcMoving) < 0.01f;
        check("object pixels: T&C = tcMoving", tcObj);
        f.objectCount = 0;

        // 3. hand: hand texels -> zero motion without a hand reprojection, the hand's own with one; merged depth has the hand
        f.mode = 1;
        run(vp, f, true, false);
        double maxHand = 0, maxRest = 0;
        for (int i = 0; i < W * H; i++) {
            if (isHand[i]) maxHand = Math.max(maxHand, Math.max(Math.abs(f16(vOut, i, 0)), Math.abs(f16(vOut, i, 1))));
            else maxRest = Math.max(maxRest, Math.max(Math.abs(f16(vOut, i, 0) - expect[i][0]) * W, Math.abs(f16(vOut, i, 1) - expect[i][1]) * H));
        }
        check("hand texels: zero motion without a hand reprojection", maxHand == 0);
        check("non-hand texels from the pre-hand scene depth == analytic (" + maxRest + " px)", maxRest <= 0.05);
        boolean mergedOk = true;
        for (int i = 0; i < W * H; i++) mergedOk &= mOut.map().getFloat(i * 4) == (isHand[i] ? 0.2f : depth[i]);
        check("merged depth: hand depth on the hand, scene depth elsewhere", mergedOk);
        writePng(cOut, "velocity-debug-hand.png");
        // hand bob: previous hand transform = current one translated by +0.02 NDC x -> hand texels move by +0.01 UV
        f.handReproject = new Matrix4d().translationRotateScale(0.02, 0, 0, 0, 0, 0, 1, 1, 1, 1);
        run(vp, f, true, false);
        double hx = 0;
        int hn = 0;
        for (int i = 0; i < W * H; i++) if (isHand[i]) { hx += f16(vOut, i, 0); hn++; }
        check("hand reprojection applied to hand texels (" + hx / hn + " UV)", Math.abs(hx / hn - 0.01) < 1e-4);
        f.handReproject = null;

        // 4. reactive + T&C masks from the opaque copy
        f.mode = 2;
        run(vp, f, true, true);
        double rPart = 1, rElse = 0, tWater = 1, rWater = 0;
        for (int i = 0; i < W * H; i++) {
            if (particles[i]) rPart = Math.min(rPart, r8(rOut, i));
            else if (water[i]) { tWater = Math.min(tWater, r8(tOut, i)); rWater = Math.max(rWater, r8(rOut, i)); }
            else if (!isHand[i] && !weather[i]) rElse = Math.max(rElse, r8(rOut, i));
        }
        System.out.printf("masks: reactive particles min %.3f, elsewhere max %.3f; water T&C min %.3f reactive max %.3f%n", rPart, rElse, tWater, rWater);
        check("reactive ~0.9 where only colour changed (particles, weather, clouds)", Math.abs(rPart - 0.9) < 0.01);
        check("reactive 0 where nothing changed", rElse == 0);
        check("translucent surface with depth: T&C 0.8, reactive halved", Math.abs(tWater - 0.8) < 0.01 && Math.abs(rWater - 0.45) < 0.01);
        boolean handMask = true;
        for (int i = 0; i < W * H; i++) if (isHand[i]) handMask &= r8(rOut, i) == 0 && Math.abs(r8(tOut, i) - f.tcHand) < 0.01f;
        check("hand: reactive 0, T&C tcHand", handMask);
        writePng(cOut, "reactive-debug.png");

        // 4b. FSR-Q6 SDK mode: reactive = the SDK pass image (constant 0.6), halved in front of the opaque depth, 0 on the hand
        f.mode = 0;
        sdkOn = true;
        run(vp, f, true, true);
        sdkOn = false;
        double rs = 1, rsWater = 1; boolean sdkHand = true;
        for (int i = 0; i < W * H; i++) {
            if (isHand[i]) sdkHand &= r8(rOut, i) == 0;
            else if (water[i] || weather[i]) rsWater = Math.min(rsWater, Math.abs(r8(rOut, i) - 0.3));
            else rs = Math.min(rs, Math.abs(r8(rOut, i) - 0.6));
        }
        check("sdk mode: reactive = the SDK value (0.6) where nothing is in front", rs < 0.01);
        check("sdk mode: halved (0.3) in front of the opaque depth, T&C 0.8", rsWater < 0.01);
        check("sdk mode: hand reactive 0", sdkHand);

        // 4c. FSR-Q7 voxel mode, no phase copy: pixels in front of the opaque depth are band A, the rest "other"
        f.voxel = true;
        run(vp, f, true, true);
        double pa = 0, wa = 0, ta = 1;
        for (int i = 0; i < W * H; i++) {
            if (particles[i]) pa = r8(rOut, i);
            else if (water[i]) { wa = r8(rOut, i); ta = Math.min(ta, r8(tOut, i)); }
        }
        var vp0 = dev.elemore.iroh.upscale.VoxelMask.Params.DEFAULT;
        float diff = (0x80 - 0x20) / 255f;
        check("voxel (no phase): drawn without depth = other class scale (" + vp0.scaleOther() + " x diff)", Math.abs(pa - Math.min(0.9f, vp0.scaleOther() * diff)) < 0.01);
        check("voxel (no phase): in front = band A reactive scale " + vp0.scaleA() + ", T&C " + vp0.tcA(), Math.abs(wa - Math.min(vp0.maxA(), vp0.scaleA() * diff)) < 0.01 && Math.abs(ta - vp0.tcA()) < 0.01);

        // 4d. voxel with the phase copy: weather pixels (phase depth behind the scene depth) are band B
        phaseOn = true;
        run(vp, f, true, true);
        phaseOn = false;
        double rwB = 1, twB = 1, rwA = 0;
        for (int i = 0; i < W * H; i++) {
            if (weather[i]) { rwB = Math.min(rwB, Math.abs(r8(rOut, i) - Math.min(vp0.maxB(), vp0.scaleB() * diff))); twB = Math.min(twB, Math.abs(r8(tOut, i) - vp0.tcB())); }
            else if (water[i]) rwA = Math.max(rwA, Math.abs(r8(rOut, i) - Math.min(vp0.maxA(), vp0.scaleA() * diff)));
        }
        check("voxel (phase): clouds / weather pixels take band B reactive and T&C", rwB < 0.01 && twB < 0.01);
        check("voxel (phase): translucent terrain stays band A", rwA < 0.01);
        f.voxel = false;

        // 5. static camera: debug view black everywhere (Proctor U3 offline analogue)
        f.reproject = new Matrix4d();
        f.mode = 1;
        run(vp, f, false, false);
        boolean black = true;
        for (int i = 0; i < W * H; i++) black &= (cOut.map().getInt(i * 4) & 0x00FFFFFF) == 0;
        check("static camera: velocity debug view all black", black);
        writePng(cOut, "velocity-debug-static.png");
        f.mode = 3;
        run(vp, f, false, false);
        int skyIdx = -1, groundIdx = 2 * W + W / 2;
        for (int i = W * H - 1; i >= 0 && skyIdx < 0; i--) if (expect[i][2] == 1) skyIdx = i;
        int skyG = cOut.map().get(skyIdx * 4) & 0xFF, gG = cOut.map().get(groundIdx * 4) & 0xFF;
        check("depth view: sky brighter than near ground (" + skyG + " > " + gG + ")", skyG > 250 && gG < skyG);
        writePng(cOut, "depth-debug.png");

        vp.destroy();
        vkDeviceWaitIdle(device);
        System.out.println("# velocitygolden " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }

    static void upload(Img img, Buf b) {
        try (MemoryStack s = MemoryStack.stackPush()) {
            VkCommandBuffer cb = beginCb(s);
            barrier(cb, img.image(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
            copy(cb, b.buffer(), img.image(), true);
            barrier(cb, img.image(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            endCb(s, cb);
        }
    }

    static VkCommandBuffer beginCb(MemoryStack s) {
        VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
        PointerBuffer pcb = s.mallocPointer(1);
        vk(vkAllocateCommandBuffers(device, ai, pcb), "cb");
        VkCommandBuffer cb = new VkCommandBuffer(pcb.get(0), device);
        vkBeginCommandBuffer(cb, VkCommandBufferBeginInfo.calloc(s).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
        return cb;
    }

    static void endCb(MemoryStack s, VkCommandBuffer cb) {
        vkEndCommandBuffer(cb);
        vk(vkQueueSubmit(queue, VkSubmitInfo.calloc(s).sType$Default().pCommandBuffers(s.pointers(cb)), VK_NULL_HANDLE), "submit");
        vkQueueWaitIdle(queue);
        vkFreeCommandBuffers(device, pool, cb);
    }

    static int frameNo;
    static boolean phaseOn, sdkOn;

    /** One dispatch: scene depth as end depth (or the hand-only end depth + scene copy), optional masks; downloads all outputs. */
    static void run(VelocityPass vp, VelocityPass.Frame f, boolean hand, boolean masks) {
        f.endDepthView = hand ? hImg.view() : dImg.view();
        f.sceneDepthView = hand ? dImg.view() : 0L;
        f.velocityView = vImg.view();
        f.colourView = cImg.view();
        f.mergedDepthView = mImg.view();
        f.reactiveView = rImg.view();
        f.tcView = tImg.view();
        f.opaqueColourView = masks ? ocImg.view() : 0L;
        f.opaqueDepthView = masks ? odImg.view() : 0L;
        f.phaseDepthView = phaseOn ? pImg.view() : 0L;
        f.sdkReactiveView = sdkOn ? sImg.view() : 0L;
        try (MemoryStack s = MemoryStack.stackPush()) {
            VkCommandBuffer cb = beginCb(s);
            int top = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, tr = VK_PIPELINE_STAGE_TRANSFER_BIT, cs = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
            for (Img o : new Img[]{vImg, mImg, rImg, tImg})
                barrier(cb, o.image(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, top, 0, cs, VK_ACCESS_SHADER_WRITE_BIT);
            // final colour: grey
            barrier(cb, cImg.image(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, top, 0, tr, VK_ACCESS_TRANSFER_WRITE_BIT);
            copy(cb, cUp.buffer(), cImg.image(), true);
            barrier(cb, cImg.image(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, tr, VK_ACCESS_TRANSFER_WRITE_BIT, cs, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);

            vp.record(cb, frameNo++, f);

            for (Img o : new Img[]{vImg, mImg, rImg, tImg, cImg})
                barrier(cb, o.image(), VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, cs, VK_ACCESS_SHADER_WRITE_BIT, tr, VK_ACCESS_TRANSFER_READ_BIT);
            copy(cb, vOut.buffer(), vImg.image(), false);
            copy(cb, mOut.buffer(), mImg.image(), false);
            copy(cb, rOut.buffer(), rImg.image(), false);
            copy(cb, tOut.buffer(), tImg.image(), false);
            copy(cb, cOut.buffer(), cImg.image(), false);
            endCb(s, cb);
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
