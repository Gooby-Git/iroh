package dev.elemore.iroh.uploadtest;

import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.device.SyncMemCaps;

import static org.lwjgl.vulkan.VK10.*;

/**
 * P2-12 (L9 / L10): the pure decisions. Timeline / budget / priority are each used only when OFFERED (and not switched off); render
 * targets get priority 1.0 only with the priority extension enabled and a dedicated allocation from 32 MiB; the upload wait mask
 * is never ALL_COMMANDS and covers every stage that reads uploaded data. {@code ./gradlew syncMemCapsTest}; last line "# syncmemcaps PASS n/n".
 */
public final class SyncMemCapsTest {
    private static int pass, fail;

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    public static void main(String[] args) {
        var d = SyncMemCaps.decide(true, true, true, true, true, true);
        check("everything offered and on: timeline + budget + priority (" + d.path() + ")", d.timeline() && d.budget() && d.priority());
        d = SyncMemCaps.decide(false, false, false, false, true, true);
        check("nothing offered: binary semaphores, VMA estimate, default priority (" + d.path() + ")", !d.timeline() && !d.budget() && !d.priority());
        d = SyncMemCaps.decide(true, true, true, false, true, true);
        check("priority extension without the memoryPriority feature: no priority", !d.priority() && d.budget() && d.timeline());
        d = SyncMemCaps.decide(true, true, true, true, false, false);
        check("both switches off: fallback everywhere", !d.timeline() && !d.budget() && !d.priority());
        d = SyncMemCaps.decide(true, false, true, true, true, true);
        check("priority without budget is independent", d.priority() && !d.budget());

        // allocation hints (decision with priority off: no priority hint at all)
        int rt = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, tex = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        check("dedicated: 5K RGBA8 colour target (56 MiB); 4K RGBA8 (31.6 MiB) is just under",
                SyncMemCaps.imageDedicated(rt, 5120, 2880, VK_FORMAT_R8G8B8A8_UNORM) && !SyncMemCaps.imageDedicated(rt, 3840, 2160, VK_FORMAT_R8G8B8A8_UNORM));
        check("not dedicated: 1080p RGBA8 target (8 MiB)", !SyncMemCaps.imageDedicated(rt, 1920, 1080, VK_FORMAT_R8G8B8A8_UNORM));
        check("dedicated: 1440p RGBA16F target (28 MiB: no), 4K RGBA16F (63 MiB: yes)",
                !SyncMemCaps.imageDedicated(rt, 2560, 1440, VK_FORMAT_R16G16B16A16_SFLOAT) && SyncMemCaps.imageDedicated(rt, 3840, 2160, VK_FORMAT_R16G16B16A16_SFLOAT));
        check("a big sampled texture that is no render target is never dedicated", !SyncMemCaps.imageDedicated(tex, 8192, 8192, VK_FORMAT_R8G8B8A8_UNORM));
        check("priority hint is -1 (VMA default) while the extension is off", SyncMemCaps.imagePriority(rt) < 0f);

        int w = Synchronization.UPLOAD_WAIT_STAGES;
        check("upload wait mask is not ALL_COMMANDS", (w & VK_PIPELINE_STAGE_ALL_COMMANDS_BIT) == 0);
        check("upload wait mask covers vertex/index fetch, indirect commands, vertex+fragment+compute reads and transfer reads",
                (w & (VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT
                    | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT))
                == (VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT
                    | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT));
        check("upload wait mask leaves the attachment stages free (clears and early depth start before uploads finish)",
                (w & (VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT)) == 0);

        // timeline waits: one entry per semaphore, the highest value wins, consumed by takeUploadWaits
        Synchronization sync = Synchronization.INSTANCE;
        sync.addTimelineWait(11L, 3L);
        sync.addTimelineWait(11L, 5L);
        sync.addTimelineWait(11L, 4L);
        sync.addTimelineWait(22L, 1L);
        long[][] t = sync.takeUploadWaits();
        check("timeline waits: 2 semaphores, 11 -> 5 (latest upload), 22 -> 1", t[0].length == 2 && t[0][0] == 11L && t[1][0] == 5L && t[0][1] == 22L && t[1][1] == 1L);
        check("timeline waits are consumed by the frame submit", sync.takeUploadWaits()[0].length == 0);

        System.out.println("# syncmemcaps " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
