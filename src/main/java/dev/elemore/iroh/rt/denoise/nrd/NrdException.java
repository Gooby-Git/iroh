package dev.elemore.iroh.rt.denoise.nrd;

/** An NRD / Vulkan error from the shim or the executor; messages start with the failing call. */
public class NrdException extends RuntimeException {
    public NrdException(String m) {
        super(m);
    }
}
