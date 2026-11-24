package dev.elemore.iroh.shaderpack.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * V-5: the pack path is render-thread only (PackRuntimeImpl, PackLifecycle, FrameExecutor and VkPackBackend are not
 * thread-safe), but its runtime switches ({@code iroh.pack.enabled}, {@code iroh.pack.path}) are applied by
 * DebugSwitches on the CALLER's thread (Proctor's {@code proctor-conn}, any mod). A disable from there freed the pack
 * images while the render thread was recording a pack frame ("use after free: pack:colortex0#alt") and both threads
 * then tore the executor down at once, scheduling the same VkFramebuffer for destruction twice (ladder run 10).
 * <p>
 * {@link #call} runs the change on the render thread: directly when already there, else queued to the render thread's
 * task queue (drained between frames, never inside a pack frame) and the caller waits for the answer up to
 * {@code timeoutMs}. On timeout the change still lands later and the caller gets {@code ok (queued ...)}. Callers must
 * not hold a lock the render thread can need while they wait (PackRuntime's synchronized methods run INSIDE the task).
 */
public final class RenderThreadCall {

    private RenderThreadCall() {}

    /** offline seam (tests): the "render thread" check and its task queue; both null = Minecraft's */
    static volatile BooleanSupplier testOnRenderThread;
    static volatile Executor testExecutor;

    public static String call(Supplier<String> op, long timeoutMs) {
        BooleanSupplier on = testOnRenderThread;
        Executor ex = testExecutor;
        if (on == null || ex == null) {
            if (!Game.available())
                return op.get(); // no game (offline tools): nothing renders concurrently
            on = Game::onRenderThread;
            ex = Game::execute;
        }
        if (on.getAsBoolean())
            return op.get();
        CompletableFuture<String> f = new CompletableFuture<>();
        ex.execute(() -> {
            try {
                f.complete(op.get());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return "ok (queued: the render thread applies it at its next task drain)";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ok (queued: interrupted while waiting for the render thread)";
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException re)
                throw re;
            if (c instanceof Error er)
                throw er;
            throw new IllegalStateException(c);
        }
    }

    /** game lookups in their own class: loaded only in game, so offline tests never touch Minecraft */
    private static final class Game {
        static boolean available() {
            try {
                return net.minecraft.client.Minecraft.getInstance() != null;
            } catch (Throwable t) {
                return false;
            }
        }

        static boolean onRenderThread() {
            return com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread();
        }

        static void execute(Runnable r) {
            net.minecraft.client.Minecraft.getInstance().execute(r);
        }
    }
}
