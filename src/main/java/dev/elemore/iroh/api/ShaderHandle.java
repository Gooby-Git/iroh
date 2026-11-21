package dev.elemore.iroh.api;

/** A compiled program from {@link IrohShaders#compile}. Immutable; any thread. */
public interface ShaderHandle {
    String id();

    ShaderLayout layout();

    /** False when compilation failed: draws with this shader then draw nothing (the error was logged once). */
    boolean valid();
}
