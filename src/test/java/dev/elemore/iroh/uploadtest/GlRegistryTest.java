package dev.elemore.iroh.uploadtest;

import net.vulkanmod.gl.VkGlRenderbuffer;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * rc8 (P0 per-world GPU leak): GL name -> VulkanImage registries return to baseline over create / delete / reuse cycles,
 * and every delete reaches VulkanImage.free exactly once (the base's glDeleteRenderbuffers dropped the map entry and kept
 * the image: ~3.7 MB of device memory per deleted screen-sized renderbuffer). Images are made without a device
 * (Unsafe.allocateInstance); VulkanImage.FREE is replaced by a recorder. {@code ./gradlew glRegistryTest}.
 */
public final class GlRegistryTest {
    private static int pass, fail;

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    public static void main(String[] args) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
        IdentityHashMap<VulkanImage, Integer> freed = new IdentityHashMap<>();
        VulkanImage.FREE = img -> freed.merge(img, 1, Integer::sum);

        // renderbuffers: 3 "worlds" of 500 create + attach + delete
        int base = VkGlRenderbuffer.liveCount();
        List<VulkanImage> made = new ArrayList<>();
        for (int world = 0; world < 3; world++) {
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                int id = VkGlRenderbuffer.genId();
                VulkanImage img = (VulkanImage) unsafe.allocateInstance(VulkanImage.class);
                VkGlRenderbuffer.setVulkanImage(id, img);
                made.add(img);
                ids.add(id);
            }
            for (int id : ids) VkGlRenderbuffer.deleteRenderbuffer(id);
            VkGlRenderbuffer.deleteRenderbuffer(ids.get(0)); // double delete: no-op
            VkGlRenderbuffer.deleteRenderbuffer(-12345);     // unknown name: no-op
        }
        boolean allOnce = made.stream().allMatch(i -> freed.getOrDefault(i, 0) == 1);
        check("renderbuffers: 3 x 500 create/delete -> registry back to baseline (" + VkGlRenderbuffer.liveCount() + " vs " + base + ")",
                VkGlRenderbuffer.liveCount() == base);
        check("renderbuffers: every deleted image freed exactly once (" + freed.size() + "/" + made.size() + ")", allOnce && freed.size() == made.size());

        // textures: create/delete/reuse
        freed.clear();
        int tbase = VkGlTexture.liveCount();
        List<VulkanImage> timgs = new ArrayList<>();
        for (int world = 0; world < 3; world++) {
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                int id = VkGlTexture.genTextureId();
                VulkanImage img = (VulkanImage) unsafe.allocateInstance(VulkanImage.class);
                VkGlTexture.getTexture(id).setVulkanImage(img);
                timgs.add(img);
                ids.add(id);
            }
            for (int id : ids) VkGlTexture.glDeleteTextures(id);
            VkGlTexture.glDeleteTextures(ids.get(1));
        }
        check("textures: 3 x 500 create/delete -> registry back to baseline (" + VkGlTexture.liveCount() + " vs " + tbase + ")",
                VkGlTexture.liveCount() == tbase);
        check("textures: every deleted image freed exactly once", timgs.stream().allMatch(i -> freed.getOrDefault(i, 0) == 1) && freed.size() == timgs.size());

        // rc9 (td-598433): re-specifying ONE texture id 10,000 times (same size, other size, depth, mip-level changes):
        // one live image at the end, every replaced image freed exactly once
        freed.clear();
        java.util.List<VulkanImage> created = new java.util.ArrayList<>();
        VkGlTexture.FACTORY = (name, owner, depth, w, h, fmt, mips) -> {
            try {
                VulkanImage img = (VulkanImage) unsafe.allocateInstance(VulkanImage.class);
                unsafe.putInt(img, unsafe.objectFieldOffset(VulkanImage.class.getDeclaredField("width")), w);
                unsafe.putInt(img, unsafe.objectFieldOffset(VulkanImage.class.getDeclaredField("height")), h);
                unsafe.putInt(img, unsafe.objectFieldOffset(VulkanImage.class.getDeclaredField("format")), fmt);
                unsafe.putInt(img, unsafe.objectFieldOffset(VulkanImage.class.getDeclaredField("mipLevels")), mips);
                created.add(img);
                return img;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        int tex = VkGlTexture.genTextureId();
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(3);
        int[][] sizes = {{1280, 720}, {1280, 720}, {854, 480}, {1280, 720}, {64, 64}};
        int[] formats = {0x1908 /* RGBA */, 0x1902 /* DEPTH_COMPONENT */, 0x8CAC /* DEPTH_COMPONENT32F */};
        int sameSizeAllocs = 0;
        int lastW = -1, lastH = -1, lastF = -1;
        for (int i = 0; i < 10_000; i++) {
            int[] sz = sizes[rnd.nextInt(sizes.length)];
            int fm = formats[rnd.nextInt(formats.length)];
            int before = created.size();
            if (rnd.nextInt(10) == 0) {
                VkGlTexture.setMaxLevelForTest(tex, rnd.nextInt(3)); // a mip-count change may reallocate
                lastW = -1;
                continue;
            }
            VkGlTexture.specifyStorageForTest(tex, 0, fm, sz[0], sz[1]);
            if (created.size() > before && sz[0] == lastW && sz[1] == lastH && fm == lastF) sameSizeAllocs++;
            lastW = sz[0]; lastH = sz[1]; lastF = fm;
        }
        long live = created.stream().filter(img -> !freed.containsKey(img)).count();
        boolean once = created.stream().allMatch(img -> freed.getOrDefault(img, 0) <= 1);
        check("10,000 re-specifications of one id: " + created.size() + " images created, " + live + " live at the end (1 expected)", live == 1 && once);
        check("same size + same format re-specification reuses the image (" + sameSizeAllocs + " needless reallocations)", sameSizeAllocs == 0);
        // rc12: the same size every frame for 1000 frames (GPUBooster / Satin respecifying their targets) is a no-op
        int before1000 = created.size();
        for (int frame = 0; frame < 1000; frame++)
            VkGlTexture.specifyStorageForTest(tex, 0, 0x1902, 1280, 720);
        int afterFirst = created.size();
        for (int frame = 0; frame < 1000; frame++)
            VkGlTexture.specifyStorageForTest(tex, 0, 0x1902, 1280, 720);
        check("respecify 1280x720 depth every frame for 1000 frames: at most one new image (" + (afterFirst - before1000) + " then "
                + (created.size() - afterFirst) + ")", afterFirst - before1000 <= 1 && created.size() == afterFirst);
        int sizeChangeBefore = created.size();
        VkGlTexture.specifyStorageForTest(tex, 0, 0x1902, 1920, 1080);
        VulkanImage newest = created.get(created.size() - 1);
        long liveNow = created.stream().filter(img -> !freed.containsKey(img)).count();
        check("respecify with a new size: one new image, the old one freed exactly once (" + liveNow + " live)",
                created.size() == sizeChangeBefore + 1 && liveNow == 1 && !freed.containsKey(newest)
                        && created.stream().allMatch(img -> freed.getOrDefault(img, 0) <= 1));
        VkGlTexture.glDeleteTextures(tex);
        check("delete frees the last one: none live", created.stream().allMatch(img -> freed.getOrDefault(img, 0) == 1));
        // rc12 (td-598433): a RenderTarget resize with a Satin-like injector (vanilla colour + depth, plus an extra
        // still-depth texture created in createBuffers and released in destroyBuffers), 1000 resizes: live images constant
        int baseTex = VkGlTexture.liveCount();
        int[] target = new int[3];
        long liveBefore = 0;
        for (int r = 0; r < 1000; r++) {
            if (r > 0) for (int id : target) VkGlTexture.glDeleteTextures(id); // destroyBuffers (+ Satin's delete inject)
            int w = 1280 + (r % 2) * 640, h = 720 + (r % 2) * 360;
            target[0] = VkGlTexture.genTextureId();
            VkGlTexture.specifyStorageForTest(target[0], 0, 0x1908, w, h);
            target[1] = VkGlTexture.genTextureId();
            VkGlTexture.specifyStorageForTest(target[1], 0, 0x1902, w, h);
            target[2] = VkGlTexture.genTextureId(); // Satin's still-depth texture
            VkGlTexture.specifyStorageForTest(target[2], 0, 0x1902, w, h);
            if (r == 0) liveBefore = created.stream().filter(img -> !freed.containsKey(img)).count();
        }
        long liveAfter = created.stream().filter(img -> !freed.containsKey(img)).count();
        check("1000 Satin-like RenderTarget resizes: live images constant (" + liveBefore + " -> " + liveAfter + "), GL names "
                + (VkGlTexture.liveCount() - baseTex), liveAfter == liveBefore && VkGlTexture.liveCount() - baseTex == 3);
        for (int id : target) VkGlTexture.glDeleteTextures(id);
        VkGlTexture.FACTORY = null;

        System.out.println("# glregistry " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
