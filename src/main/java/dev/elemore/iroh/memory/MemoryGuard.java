package dev.elemore.iroh.memory;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaBudget;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Iroh (rc8, SYSTEM SAFETY rule 9): the Vulkan side of {@link MemoryBudget}. Samples VMA statistics and the driver's
 * VK_EXT_memory_budget numbers about once a second (frame start, off the hot path), warns and trims at 85% of the cap,
 * refuses non-essential allocations at the cap, and turns every VK_ERROR_OUT_OF_DEVICE_MEMORY / OUT_OF_HOST_MEMORY into
 * one ERROR with the per-owner breakdown, a trim, fail-fast for every later allocation (the driver is never asked again
 * in a loop), and a clean exit from the world to the title screen with a plain message.
 */
public final class MemoryGuard {
    private static final Logger LOGGER = LogManager.getLogger("Iroh");
    public static final MemoryBudget BUDGET = new MemoryBudget(0);
    /** rc9 (td-598433): runaway growth rules (memory rate, per-owner image rate). */
    public static final GrowthMonitor GROWTH = new GrowthMonitor();
    private static boolean growthEscalated;
    private static long lastSample;
    private static volatile boolean leaveWorld;

    /** Thrown instead of calling the driver again once it reported out of memory (or by the failing call itself). */
    public static final class IrohOutOfMemory extends RuntimeException {
        public IrohOutOfMemory(String what) {
            super(what);
        }
    }

    private MemoryGuard() {
    }

    public static void configure(int maxDeviceMemoryMiB) {
        BUDGET.setConfiguredCapMiB(maxDeviceMemoryMiB);
    }

    /** Render thread, every frame: samples at most once a second; acts on transitions; leaves the world after an OOM. */
    public static void tick() {
        if (leaveWorld) {
            leaveWorld = false;
            leaveWorldNow();
        }
        long now = System.nanoTime();
        if (now - lastSample < 1_000_000_000L) return;
        lastSample = now;
        sampleNow();
        // rc9: rate rules
        var events = GROWTH.sample(now, BUDGET.irohUsage(), net.vulkanmod.vulkan.memory.MemoryManager.ownerCounts());
        if (events.spikeBytes() > 0) {
            growthEscalated = true;
            LOGGER.error("Iroh: graphics memory grew by {} MiB within 30 s; {} | {}", events.spikeBytes() / MemoryBudget.MiB,
                    BUDGET.summary(), net.vulkanmod.vulkan.memory.MemoryManager.liveSummary());
            trim();
        }
        for (var e : events.newlyFlagged().entrySet()) {
            growthEscalated = true;
            LOGGER.error("Iroh: '{}' created {} more live images within 60 s (runaway); new texture names from it get no image from now on. {}",
                    e.getKey(), e.getValue(), net.vulkanmod.vulkan.memory.MemoryManager.liveSummary());
        }
        // growth kept going after the rules fired: close the world at 90% of the cap instead of reaching the driver limit
        if (growthEscalated && BUDGET.cap() > 0 && BUDGET.irohUsage() >= (long) (BUDGET.cap() * 0.90)) {
            growthEscalated = false;
            outOfMemory("runaway growth reached 90% of Iroh's cap");
        }
        if (BUDGET.takeWarn()) {
            LOGGER.warn("Iroh: graphics memory at {} of Iroh's cap; trimming. {} | {}", BUDGET.state() == MemoryBudget.State.CAPPED ? "100%" : "85%",
                    BUDGET.summary(), net.vulkanmod.vulkan.memory.MemoryManager.liveSummary());
            trim();
        }
    }

    static void sampleNow() {
        var props = net.vulkanmod.vulkan.device.DeviceManager.memoryProperties;
        if (props == null) return;
        int heaps = props.memoryHeapCount();
        int largest = -1;
        long largestSize = 0;
        for (int i = 0; i < heaps; i++) {
            if ((props.memoryHeaps(i).flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0 && props.memoryHeaps(i).size() > largestSize) {
                largest = i;
                largestSize = props.memoryHeaps(i).size();
            }
        }
        if (largest < 0) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VmaBudget.Buffer b = VmaBudget.calloc(16, stack);
            Vma.vmaGetHeapBudgets(net.vulkanmod.vulkan.Vulkan.getAllocator(), b);
            long iroh = 0;
            for (int i = 0; i < heaps; i++)
                if ((props.memoryHeaps(i).flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0)
                    iroh += b.get(i).statistics().blockBytes();
            BUDGET.sample(largestSize, b.get(largest).budget(), b.get(largest).usage(), iroh + EXTERNAL.get());
        }
    }

    /** rc9: a NEW image for {@code owner} is refused while that owner is flagged for runaway growth. */
    public static boolean refuseNewImage(String owner) {
        return GROWTH.isFlagged(owner);
    }

    /** rc9: a world started rendering (growth rules start their warm-up). */
    public static void worldStarted() {
        GROWTH.worldStarted(System.nanoTime());
        growthEscalated = false;
    }

    /**
     * P4-1b: device memory Iroh holds OUTSIDE VMA (FidelityFX contexts allocate with vkAllocateMemory themselves);
     * counted in Iroh's usage at the next sample. Add on create, subtract on destroy.
     */
    public static final java.util.concurrent.atomic.AtomicLong EXTERNAL = new java.util.concurrent.atomic.AtomicLong();

    public static void addExternal(long bytes) {
        EXTERNAL.addAndGet(bytes);
    }

    /** Non-essential allocation check (optional caches, dynamic textures nobody samples, extra targets). */
    public static boolean allowOptional(long bytes) {
        return BUDGET.allow(bytes, false);
    }

    /** Before any device allocation: fail fast after an out-of-memory instead of asking the driver again. */
    public static void beforeAllocation(String what) {
        if (BUDGET.state() == MemoryBudget.State.OUT_OF_MEMORY)
            throw new IrohOutOfMemory("refused after an earlier out-of-memory: " + what);
    }

    /**
     * Checks a Vulkan result. For VK_ERROR_OUT_OF_DEVICE_MEMORY / OUT_OF_HOST_MEMORY: logs once with the breakdown,
     * trims, schedules leaving the world and throws {@link IrohOutOfMemory} (never a retry). Other codes: returned
     * unchanged for the caller's own handling.
     */
    public static int check(int result, String what) {
        if (result == VK_ERROR_OUT_OF_DEVICE_MEMORY || result == VK_ERROR_OUT_OF_HOST_MEMORY) {
            outOfMemory(what + " (" + (result == VK_ERROR_OUT_OF_DEVICE_MEMORY ? "VK_ERROR_OUT_OF_DEVICE_MEMORY" : "VK_ERROR_OUT_OF_HOST_MEMORY") + ")");
            throw new IrohOutOfMemory(what);
        }
        return result;
    }

    public static void outOfMemory(String what) {
        if (BUDGET.outOfMemory()) {
            LOGGER.error("Iroh: the graphics driver is out of memory at {}. Freeing what can be freed and closing the world to protect the system. {} | {}",
                    what, BUDGET.summary(), net.vulkanmod.vulkan.memory.MemoryManager.liveSummary(), new Throwable("failed here"));
            trim();
            leaveWorld = true;
        }
    }

    /** Frees what can be freed without changing what is drawn: pooled staging blocks. */
    static void trim() {
        var ring = net.vulkanmod.vulkan.memory.buffer.StagingBuffer.ring();
        if (ring != null) ring.trimPool();
    }

    private static void leaveWorldNow() {
        Minecraft mc = Minecraft.getInstance();
        Component title = Component.literal("Iroh ran out of graphics memory");
        Component msg = Component.literal("Iroh ran out of graphics memory and closed the world to protect your system. "
                + "The log (latest.log) has the details.");
        try {
            if (mc.level != null) {
                mc.level.disconnect();
                mc.disconnect(new net.minecraft.client.gui.screens.AlertScreen(
                        () -> mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen()), title, msg));
            } else {
                mc.setScreen(new net.minecraft.client.gui.screens.AlertScreen(
                        () -> mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen()), title, msg));
            }
        } catch (Throwable t) {
            LOGGER.error("Iroh: could not leave the world after the out-of-memory", t);
        }
    }

    /** World unloaded: memory released, sampling starts over. */
    public static void worldUnloaded() {
        if (BUDGET.state() == MemoryBudget.State.OUT_OF_MEMORY)
            LOGGER.info("Iroh: world closed after the out-of-memory; graphics memory checks resume");
        BUDGET.reset();
        GROWTH.worldEnded();
        growthEscalated = false;
        lastSample = 0;
    }

    public static String statsLine() {
        return BUDGET.summary();
    }
}
