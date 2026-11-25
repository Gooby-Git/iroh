package net.vulkanmod.vulkan.queue;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkTimelineSemaphoreSubmitInfo;

import java.nio.LongBuffer;

/**
 * P2-12 (L9): the VkTimelineSemaphoreSubmitInfo chaining of upload submits and the frame submit, as raw-handle helpers so the
 * offline lavapipe repro (timelineSyncRepro) runs exactly this code.
 */
public final class UploadSubmit {
    private UploadSubmit() {}

    /** The submit signals {@code semaphore} (a timeline) to {@code value}. */
    public static void signalTimeline(MemoryStack s, VkSubmitInfo info, long semaphore, long value) {
        info.pSignalSemaphores(s.longs(semaphore));
        info.pNext(VkTimelineSemaphoreSubmitInfo.calloc(s).sType$Default().pSignalSemaphoreValues(s.longs(value)).address());
    }

    /**
     * The frame submit waits timelines: {@code waitValues} has one entry per wait semaphore (0 for binary ones), flipped. The frame signals
     * ONE binary semaphore (renderFinished): its value entry is ignored but the counts must match (VUID-VkSubmitInfo-pNext-03241).
     */
    public static void chainWaitValues(MemoryStack s, VkSubmitInfo info, LongBuffer waitValues) {
        info.pNext(VkTimelineSemaphoreSubmitInfo.calloc(s).sType$Default().pWaitSemaphoreValues(waitValues).pSignalSemaphoreValues(s.longs(0L)).address());
    }
}
