package dev.elemore.iroh.uploadtest;

import net.vulkanmod.vulkan.texture.ImageBarrierBatch;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * rc5 (td-1cb256): the upload flush's layout transitions. 50,000 queued images (a pack-world join queues thousands in one
 * flush) recorded through a fake recorder: the render thread's MemoryStack must not move, there must be one barrier call
 * per (src, dst) stage group, and every barrier must carry its image and layouts. The old path allocated one
 * VkImageMemoryBarrier per image on the thread stack and overflowed it. {@code ./gradlew barrierBatchTest}; last line
 * "# barrierbatch PASS n/n".
 */
public final class BarrierBatchTest {
    private static int pass, fail;

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    record Call(int src, int dst, int n, long firstImage, long lastImage) {}

    public static void main(String[] args) {
        ImageBarrierBatch batch = new ImageBarrierBatch();
        List<Call> calls = new ArrayList<>();
        long[] seen = new long[50_000];
        int[] seenN = {0};
        boolean[] fieldsOk = {true};
        ImageBarrierBatch.Recorder fake = (cb, src, dst, b) -> {
            calls.add(new Call(src, dst, b.remaining(), b.get(0).image(), b.get(b.remaining() - 1).image()));
            for (int i = 0; i < b.remaining(); i++) {
                VkImageMemoryBarrier x = b.get(i);
                if (x.sType() != VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER) fieldsOk[0] = false;
                if (x.subresourceRange().levelCount() != VK_REMAINING_MIP_LEVELS) fieldsOk[0] = false;
                if (src == VK_PIPELINE_STAGE_TRANSFER_BIT && (x.oldLayout() != VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                        || x.newLayout() != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL || x.srcAccessMask() != VK_ACCESS_TRANSFER_WRITE_BIT))
                    fieldsOk[0] = false;
                if (seenN[0] < seen.length) seen[seenN[0]++] = x.image();
            }
        };
        long sp0 = MemoryStack.stackGet().getPointer();

        // 1. 50,000 uniform upload transitions (TRANSFER_DST -> SHADER_READ_ONLY)
        for (int i = 0; i < 50_000; i++)
            batch.add(1000 + i, VK_IMAGE_ASPECT_COLOR_BIT, 0, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        batch.flush(null, fake);
        check("50,000 images: one barrier call (" + calls.size() + ")", calls.size() == 1 && calls.get(0).n() == 50_000 && batch.lastCalls() == 1);
        boolean order = seenN[0] == 50_000;
        for (int i = 0; i < seenN[0] && order; i++) order = seen[i] == 1000 + i;
        check("every image present once, in queue order", order);
        check("barrier fields (sType, layouts, access, all mips)", fieldsOk[0]);
        check("MemoryStack pointer unchanged (" + (sp0 - MemoryStack.stackGet().getPointer()) + " B)", MemoryStack.stackGet().getPointer() == sp0);
        check("batch empty after flush", batch.size() == 0);

        // 2. mixed stage pairs: one call per group, insertion order inside a group
        calls.clear();
        seenN[0] = 0;
        int[][] pairs = {{VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT},
                {VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT},
                {VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT}};
        for (int i = 0; i < 30_000; i++) {
            int[] p = pairs[i % 3];
            batch.add(i, VK_IMAGE_ASPECT_COLOR_BIT, 0, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    p[0], VK_ACCESS_TRANSFER_WRITE_BIT, p[1], VK_ACCESS_SHADER_READ_BIT);
        }
        batch.flush(null, fake);
        boolean grouped = calls.size() == 3 && calls.stream().allMatch(c -> c.n() == 10_000);
        boolean inner = true;
        for (Call c : calls) inner &= c.lastImage() - c.firstImage() == 3L * 9_999;
        check("3 stage groups: 3 calls of 10,000 (" + calls.size() + ")", grouped);
        check("insertion order kept inside each group", inner);
        check("MemoryStack pointer unchanged after the grouped flush", MemoryStack.stackGet().getPointer() == sp0);

        // 3. repeated small flushes reuse the array (no growth, no stack)
        for (int f = 0; f < 1000; f++) {
            for (int i = 0; i < 40; i++)
                batch.add(i, VK_IMAGE_ASPECT_COLOR_BIT, 0, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            batch.flush(null, (cb, s, d, b) -> {});
        }
        check("1000 frames x 40 images: stack unchanged", MemoryStack.stackGet().getPointer() == sp0);
        batch.free();

        // 4. the old pattern (one calloc per image on the caller's frame) does overflow: the case this test guards
        boolean overflow = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < 50_000; i++) VkImageMemoryBarrier.calloc(1, stack);
        } catch (OutOfMemoryError e) {
            overflow = e.getMessage() != null && e.getMessage().contains("stack");
        }
        check("old per-image stack calloc pattern overflows LWJGL's stack (" + overflow + ")", overflow);
        check("stack restored after the old-pattern demo", MemoryStack.stackGet().getPointer() == sp0);

        System.out.println("# barrierbatch " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        if (fail != 0) System.exit(1);
    }
}
