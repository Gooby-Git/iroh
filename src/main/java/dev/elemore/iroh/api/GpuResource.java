package dev.elemore.iroh.api;

/**
 * api 4: a GPU object a mod created through Iroh (buffer, compute or indirect pipeline, render target). Owned by the
 * mod id given at creation: Iroh frees everything an owner still holds when the world unloads, when the owner's hook is
 * removed after repeated failures, or at shutdown ("owner dies = Iroh frees"). Frees are deferred until the GPU is done.
 */
public interface GpuResource extends AutoCloseable {
    String owner();

    String name();

    /** False once closed (by the mod or by Iroh). */
    boolean alive();

    /** Frees it (deferred on the GPU side). Idempotent. Render thread. */
    @Override
    void close();
}
