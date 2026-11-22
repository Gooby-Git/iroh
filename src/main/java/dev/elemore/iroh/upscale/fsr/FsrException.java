package dev.elemore.iroh.upscale.fsr;

/** An FFX / Vulkan error from the native shim; the message starts with the call and the FFX error NAME and code. */
public class FsrException extends RuntimeException {
    public FsrException(String message) {
        super(message);
    }
}
