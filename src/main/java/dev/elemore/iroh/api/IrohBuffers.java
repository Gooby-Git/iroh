package dev.elemore.iroh.api;

/**
 * Placeholder (api 3): GPU buffers (storage, indirect, sparse, device address, async upload). Capabilities GPU_BUFFERS, SPARSE_BUFFERS, DEVICE_ADDRESS.
 * Not implemented before Phase 2 / 9 (Vista's renderer): every call throws {@link UnsupportedOperationException}; check
 * {@link IrohApi#capabilities()} first. The contract is written in knowledge/api.md so designs can start now.
 */
public final class IrohBuffers {
    private IrohBuffers() {
    }

    public static Object create(Object... args) {
        throw new UnsupportedOperationException("IrohBuffers: not available yet (Phase 2/9); see knowledge/api.md");
    }
}
