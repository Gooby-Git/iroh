package dev.elemore.iroh.api;

/** api 4: a compute pipeline ({@link IrohCompute#pipeline}). */
public interface ComputePipelineHandle extends GpuResource {
    int storageBuffers();

    int pushBytes();
}
