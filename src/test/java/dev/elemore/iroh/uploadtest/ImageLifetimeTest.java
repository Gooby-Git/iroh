package dev.elemore.iroh.uploadtest;

import dev.elemore.iroh.compat.SatinStillDepth;
import dev.elemore.iroh.diag.ImageTrace;
import dev.elemore.iroh.glshim.GlNet;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.buffer.StagingBuffer;
import net.vulkanmod.vulkan.memory.staging.StagingRing;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * rc13 (td-598433): image lifetimes across the GL entry points, the Satin x GPUBooster still-depth leak and its guard,
 * the image trace, and the deferred free of an image no frame ever used. Runs with {@code -Diroh.imageTrace=satin}.
 * {@code ./gradlew imageLifetimeTest}; last line "# imagelifetime PASS n/n".
 */
public final class ImageLifetimeTest {
    private static int pass, fail;

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    /** Stand-in for RenderTarget after Satin's mixin merged its field (same name and initial value). */
    static final class FakeTarget {
        int colorTextureId = -1, depthBufferId = -1;
        @SuppressWarnings("unused") private int satin$stillDepthTexture = -1;
    }

    static sun.misc.Unsafe unsafe;
    static String nextOwner = "GlTexture depth by minecraft via satin";
    static final IdentityHashMap<VulkanImage, Integer> freed = new IdentityHashMap<>();
    static final List<VulkanImage> created = new ArrayList<>();

    static VulkanImage fakeImage(String name, String owner, int w, int h, int fmt) {
        try {
            VulkanImage img = (VulkanImage) unsafe.allocateInstance(VulkanImage.class);
            unsafe.putObject(img, unsafe.objectFieldOffset(VulkanImage.class.getDeclaredField("name")), name);
            img.owner = owner;
            unsafe.putInt(img, unsafe.objectFieldOffset(VulkanImage.class.getDeclaredField("width")), w);
            unsafe.putInt(img, unsafe.objectFieldOffset(VulkanImage.class.getDeclaredField("height")), h);
            unsafe.putInt(img, unsafe.objectFieldOffset(VulkanImage.class.getDeclaredField("format")), fmt);
            unsafe.putInt(img, unsafe.objectFieldOffset(VulkanImage.class.getDeclaredField("mipLevels")), 1);
            return img;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static long live() {
        return created.stream().filter(i -> !freed.containsKey(i)).count();
    }

    /** Raw GL glGenTextures(n, ptr) through the native-level handler (what GL11.glGenTextures() reaches). */
    static int rawGen(GlNet.Handler gen) {
        long ids = MemoryUtil.nmemAlloc(4), n = MemoryUtil.nmemAlloc(4), p = MemoryUtil.nmemAlloc(8), args = MemoryUtil.nmemAlloc(16);
        try {
            MemoryUtil.memPutInt(n, 1);
            MemoryUtil.memPutAddress(p, ids);
            MemoryUtil.memPutAddress(args, n);
            MemoryUtil.memPutAddress(args + 8, p);
            gen.call(args);
            return MemoryUtil.memGetInt(ids);
        } finally {
            MemoryUtil.nmemFree(ids); MemoryUtil.nmemFree(n); MemoryUtil.nmemFree(p); MemoryUtil.nmemFree(args);
        }
    }

    static void rawDelete(GlNet.Handler del, int id) {
        long ids = MemoryUtil.nmemAlloc(4), n = MemoryUtil.nmemAlloc(4), p = MemoryUtil.nmemAlloc(8), args = MemoryUtil.nmemAlloc(16);
        try {
            MemoryUtil.memPutInt(ids, id);
            MemoryUtil.memPutInt(n, 1);
            MemoryUtil.memPutAddress(p, ids);
            MemoryUtil.memPutAddress(args, n);
            MemoryUtil.memPutAddress(args + 8, p);
            del.call(args);
        } finally {
            MemoryUtil.nmemFree(ids); MemoryUtil.nmemFree(n); MemoryUtil.nmemFree(p); MemoryUtil.nmemFree(args);
        }
    }

    public static void main(String[] args) throws Exception {
        Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        unsafe = (sun.misc.Unsafe) uf.get(null);
        VulkanImage.FREE = img -> {
            freed.merge(img, 1, Integer::sum);
            if (img.traced) ImageTrace.queued(img, "test free");
        };
        List<String> trace = new ArrayList<>();
        ImageTrace.SINK = trace::add;
        check("trace is on for this run (-Diroh.imageTrace=satin)", ImageTrace.ON);
        VkGlTexture.FACTORY = (name, owner, depth, w, h, fmt, mips) -> {
            // owners as the game names them: the test has no mod frames on its stack
            // (offline there is no device depth format, so the owner comes from the test, not from the format)
            VulkanImage img = fakeImage(name, nextOwner, w, h, fmt);
            created.add(img);
            return img;
        };

        // 1. one GL name space: raw GL (native handler, = GL11.glGenTextures / glDeleteTextures) and GlStateManager
        //    (_genTexture / _deleteTexture / TextureUtil.generateTextureId / releaseTextureId all call VkGlTexture)
        GlNet.Handler gen = GlNet.handlerForTest("glGenTextures"), del = GlNet.handlerForTest("glDeleteTextures");
        check("raw GL handlers registered", gen != null && del != null);
        int base = VkGlTexture.liveCount();
        long liveBase = live();
        List<Integer> raw = new ArrayList<>(), mgr = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int a = rawGen(gen);
            VkGlTexture.specifyStorageForTest(a, 0, 0x1902, 1280, 720);
            raw.add(a);
            int b = VkGlTexture.genTextureId();
            VkGlTexture.specifyStorageForTest(b, 0, 0x1902, 1280, 720);
            mgr.add(b);
        }
        check("raw and GlStateManager names never collide", raw.stream().noneMatch(mgr::contains));
        for (int a : raw) VkGlTexture.glDeleteTextures(a);   // raw-GL name released via GlStateManager._deleteTexture
        for (int b : mgr) rawDelete(del, b);                 // GlStateManager name released via raw glDeleteTextures
        check("raw-GL names deleted via GlStateManager and vice versa: registry back to baseline (" + (VkGlTexture.liveCount() - base) + ")",
                VkGlTexture.liveCount() == base);
        check("every image of those 400 names freed exactly once (" + (live() - liveBase) + " live)",
                live() == liveBase && created.stream().allMatch(i -> freed.getOrDefault(i, 0) == 1));

        // 2. the td-598433 mechanism: Satin's still-depth is released only from inside the vanilla destroyBuffers body,
        //    which GPUBooster's HEAD-cancel skips. 11 targets x 10 resizes, without and with Iroh's guard.
        Field still = FakeTarget.class.getDeclaredField("satin$stillDepthTexture");
        still.setAccessible(true);
        Field found = SatinStillDepth.find(FakeTarget.class);
        check("guard finds Satin's field by name", found != null && found.getName().equals("satin$stillDepthTexture"));
        SatinStillDepth.RELEASE = VkGlTexture::glDeleteTextures; // TextureUtil.releaseTextureId -> _deleteTexture
        List<Integer> stillIds = new ArrayList<>();
        for (boolean guard : new boolean[]{false, true}) {
            FakeTarget[] targets = new FakeTarget[11];
            for (int t = 0; t < targets.length; t++) targets[t] = new FakeTarget();
            long before = live();
            long afterFirst = 0;
            for (int r = 0; r <= 10; r++) {
                int w = 1280 + (r % 2) * 640, h = 720 + (r % 2) * 360;
                for (FakeTarget t : targets) {
                    if (t.colorTextureId > 0) {
                        // destroyBuffers: (guard: Iroh HEAD at priority 900) then GPUBooster deleteRBON HEAD-cancel
                        if (guard) SatinStillDepth.releaseVia(still, t, "destroyBuffers");
                        VkGlTexture.glDeleteTextures(t.depthBufferId);
                        VkGlTexture.glDeleteTextures(t.colorTextureId);
                        t.depthBufferId = t.colorTextureId = -1;
                    }
                    // createBuffers: (guard: Iroh HEAD), vanilla colour + depth, Satin's still-depth inject
                    if (guard) SatinStillDepth.releaseVia(still, t, "createBuffers");
                    t.colorTextureId = VkGlTexture.genTextureId();
                    VkGlTexture.specifyStorageForTest(t.colorTextureId, 0, 0x1908, w, h);
                    t.depthBufferId = VkGlTexture.genTextureId();
                    VkGlTexture.specifyStorageForTest(t.depthBufferId, 0, 0x1902, w, h);
                    int sd = rawGen(gen); // Satin: GL11.glGenTextures()
                    stillIds.add(sd);
                    VkGlTexture.specifyStorageForTest(sd, 0, 0x1902, w, h);
                    still.setInt(t, sd);
                }
                if (r == 0) afterFirst = live();
            }
            long growth = live() - afterFirst;
            if (!guard)
                check("without the guard (GPUBooster cancels destroyBuffers): +11 still-depth images per resize, as in game (" + growth + " over 10)",
                        growth == 110);
            else
                check("with the guard: live images constant over 10 resizes of 11 targets (" + growth + ")", growth == 0);
            // close the targets (PostChain.close -> destroyBuffers, cancelled again by GPUBooster)
            for (FakeTarget t : targets) {
                SatinStillDepth.releaseVia(still, t, "destroyBuffers");
                VkGlTexture.glDeleteTextures(t.depthBufferId);
                VkGlTexture.glDeleteTextures(t.colorTextureId);
            }
            if (guard) check("with the guard: closing the targets frees everything they made (" + (live() - before) + " left)", live() == before);
            // clean the unguarded run's leak for the next phase
            for (int id : stillIds) if (VkGlTexture.exists(id)) VkGlTexture.glDeleteTextures(id);
            stillIds.clear();
        }
        int doubleBefore = (int) freed.values().stream().filter(v -> v > 1).count();
        FakeTarget twice = new FakeTarget();
        int sd = rawGen(gen);
        VkGlTexture.specifyStorageForTest(sd, 0, 0x1902, 64, 64);
        still.setInt(twice, sd);
        SatinStillDepth.releaseVia(still, twice, "destroyBuffers"); // Iroh
        int after = still.getInt(twice);
        if (after > -1) VkGlTexture.glDeleteTextures(after);         // Satin's own inject (not cancelled): sees -1
        check("guard + Satin's own release (nothing cancels): one delete, never a double free",
                after == -1 && freed.values().stream().filter(v -> v > 1).count() == doubleBefore);

        // 3. the trace: create, delete request (API, existed), free; live ids in the report
        trace.clear();
        int traced = VkGlTexture.genTextureId();
        VkGlTexture.specifyStorageForTest(traced, 0, 0x1902, 320, 200);
        int liveTraced = ImageTrace.liveCount();
        String rep = ImageTrace.report();
        VkGlTexture.glDeleteTextures(traced);
        VkGlTexture.glDeleteTextures(traced); // double delete: a no-op, not traced again
        String all = String.join("\n", trace);
        if (Boolean.getBoolean("verbose")) System.out.println(all + "\n" + rep);
        check("trace logs the creation with size, owner and stack", all.contains("create GL " + traced + " 320x200") && all.contains("by minecraft via satin")
                && all.contains("at dev.elemore.iroh.uploadtest.ImageLifetimeTest.main"));
        check("memStats report lists the live traced id with its age", rep.contains("GL " + traced + " 320x200 age ") && rep.contains("live 1"));
        check("trace logs the delete request with API and 'existed'", all.contains("delete GL " + traced + " via ") && all.contains("texture existed"));
        check("trace logs where the image went", all.contains("queued GlTexture " + traced));
        check("trace: id gone from the live list after delete (" + liveTraced + " -> " + ImageTrace.liveCount() + ")", ImageTrace.liveCount() == liveTraced - 1);
        int colorBefore = trace.size();
        int untraced = VkGlTexture.genTextureId();
        nextOwner = "GlTexture color by minecraft via gpu_booster";
        VkGlTexture.specifyStorageForTest(untraced, 0, 0x1908, 16, 16); // not traced
        VkGlTexture.glDeleteTextures(untraced);
        check("non-matching owners are not traced", trace.size() == colorBefore);
        VkGlTexture.FACTORY = null;

        // 4. deferred free of an image no frame ever used: epoch-gated, completes once its epoch's frame finished
        StagingRing ring = new StagingRing(new StagingRing.Factory() {
            @Override public StagingRing.Block create(long size) {
                long addr = MemoryUtil.nmemAlloc(size);
                return new StagingRing.Block() {
                    @Override public long bufferId() { return addr; }
                    @Override public long address() { return addr; }
                    @Override public long size() { return size; }
                };
            }
            @Override public void destroy(StagingRing.Block b) { MemoryUtil.nmemFree(b.address()); }
        }, 1 << 20, 8 << 20);
        StagingBuffer.setRingForTest(ring);
        MemoryManager.createInstance(2);
        MemoryManager mm = MemoryManager.getInstance();
        VulkanImage never = fakeImage("never used", "GlTexture depth by minecraft via satin", 8, 8, 0);
        never.traced = true;
        trace.clear();
        mm.addToFreeable(never); // id 0: doFree only traces (no device)
        check("never-used image queued at the open epoch", mm.pendingEpochImages() == 1 && String.join("\n", trace).contains("deferred free at epoch"));
        // frames: Renderer order = frameSlotDone(slot) -> initFrame(slot) -> ... -> flushConsumers(slot) (endEpoch)
        int frames = 0;
        boolean freedEarly = false;
        for (int f = 0; f < 6 && mm.pendingEpochImages() > 0; f++) {
            int slot = f % 2;
            Synchronization.INSTANCE.frameSlotDone(slot);
            mm.initFrame(slot);
            if (f == 0 && mm.pendingEpochImages() == 0) freedEarly = true;
            Synchronization.INSTANCE.endEpoch(slot);
            frames++;
        }
        check("not destroyed before its epoch's frame finished", !freedEarly);
        check("deferred free of a never-used image completes within 3 frames (" + frames + ")", mm.pendingEpochImages() == 0 && frames <= 3);
        check("trace logs the Vulkan destroy", String.join("\n", trace).contains("destroyed never used"));

        System.out.println("# imagelifetime " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
