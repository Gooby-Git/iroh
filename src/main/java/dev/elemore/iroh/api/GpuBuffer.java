package dev.elemore.iroh.api;

/** api 4: a device-local buffer ({@link IrohBuffers}). */
public interface GpuBuffer extends GpuResource {
    long bytes();

    /** {@link IrohBuffers#STORAGE} | {@link IrohBuffers#INDIRECT} | {@link IrohBuffers#INDEX} | {@link IrohBuffers#VERTEX}. */
    int usage();
}
