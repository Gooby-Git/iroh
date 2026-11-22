package dev.elemore.iroh.packtest;

import dev.elemore.iroh.diag.ImageTrace;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.ArrayList;
import java.util.List;

/**
 * G1 fix 2: {@code -Diroh.imageTrace=pack:} follows pack render targets (VulkanImages with no GL name): create, queue,
 * destroy, and the live count in the report goes back to zero. {@code ./gradlew imageTracePackTest}.
 */
public final class ImageTracePackTest {
    static int pass, total;

    static void check(String what, boolean ok) {
        total++;
        if (ok) pass++;
        else System.out.println("FAIL " + what);
    }

    static VulkanImage fake(String name) throws Exception {
        var f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        sun.misc.Unsafe u = (sun.misc.Unsafe) f.get(null);
        VulkanImage img = (VulkanImage) u.allocateInstance(VulkanImage.class);
        u.putObject(img, u.objectFieldOffset(VulkanImage.class.getDeclaredField("name")), name);
        img.owner = "pack";
        u.putInt(img, u.objectFieldOffset(VulkanImage.class.getDeclaredField("width")), 64);
        u.putInt(img, u.objectFieldOffset(VulkanImage.class.getDeclaredField("height")), 36);
        return img;
    }

    public static void main(String[] args) throws Exception {
        List<String> out = new ArrayList<>();
        ImageTrace.SINK = out::add;
        check("trace on with filter pack:", ImageTrace.ON);
        List<VulkanImage> imgs = new ArrayList<>();
        for (String n : new String[]{"pack:colortex0#main", "pack:colortex0#alt", "pack:depthtex0"}) {
            VulkanImage v = fake(n);
            ImageTrace.createdImage(v);
            imgs.add(v);
        }
        VulkanImage other = fake("Iroh scene colour");
        other.owner = "scene";
        ImageTrace.createdImage(other);
        check("3 pack images live, scene image not traced", ImageTrace.liveCount() == 3 && !other.traced);
        check("report names them", ImageTrace.report().contains("live 3") && ImageTrace.report().contains("pack:depthtex0"));
        for (VulkanImage v : imgs) {
            ImageTrace.queued(v, "deferred free");
            ImageTrace.destroyed(v);
        }
        check("zero live after destroy: " + ImageTrace.report(), ImageTrace.liveCount() == 0 && ImageTrace.report().contains("live 0"));
        check("create/queued/destroyed lines", out.stream().filter(s -> s.contains("create pack:")).count() == 3
                && out.stream().filter(s -> s.contains("destroyed pack:")).count() == 3);
        System.out.println("# imagetracepack " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }
}
