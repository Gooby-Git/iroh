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

        System.out.println("# glregistry " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
