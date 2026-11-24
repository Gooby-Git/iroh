package net.vulkanmod.vulkan.util;

import org.lwjgl.system.MemoryStack;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * H1A (RQ-H1-a): runs a call that enumerates an unbounded driver list inside LWJGL (new VkInstance reads every device extension
 * onto the thread's MemoryStack, 260 B per extension, 56-64 KB of the 64 KB default on a 212-extension NVIDIA driver) on a short-lived
 * thread whose MemoryStack is large, so the caller's stack depth and the driver's extension count no longer matter. If the
 * thread-local cannot be replaced (reflection refused) the call runs inline, which is the old behaviour.
 */
public final class WideStack {
    public static final int DEFAULT_BYTES = 4 << 20;
    private static final Field TLS = findTls();

    private WideStack() {}

    private static Field findTls() {
        try {
            Field f = MemoryStack.class.getDeclaredField("TLS");
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean available() { return TLS != null; }

    @SuppressWarnings("unchecked")
    public static <T> T call(Supplier<T> fn) {
        if (TLS == null) return fn.get();
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                ThreadLocal<MemoryStack> tl = (ThreadLocal<MemoryStack>) TLS.get(null);
                tl.set(MemoryStack.create(DEFAULT_BYTES));
                out.set(fn.get());
            } catch (Throwable e) {
                err.set(e);
            }
        }, "iroh-wide-stack");
        t.setDaemon(true);
        t.start();
        // review (gemini 71f9e93 #1): the worker reads the caller's native create-info; never leave the caller (which frees it) before the worker is done
        boolean interrupted = false;
        while (t.isAlive()) {
            try {
                t.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        Throwable e = err.get();
        if (e instanceof RuntimeException r) throw r;
        if (e instanceof Error r) throw r;
        if (e != null) throw new IllegalStateException(e);
        return out.get();
    }
}
