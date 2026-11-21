package dev.elemore.iroh.api;

/**
 * Placeholder (api 3): compute dispatch (direct / indirect, declared barriers, async compute queue). Capability COMPUTE.
 * Not implemented before Phase 2 / 9 (Vista's renderer): every call throws {@link UnsupportedOperationException}; check
 * {@link IrohApi#capabilities()} first. The contract is written in knowledge/api.md so designs can start now.
 */
public final class IrohCompute {
    private IrohCompute() {
    }

    public static Object create(Object... args) {
        throw new UnsupportedOperationException("IrohCompute: not available yet (Phase 2/9); see knowledge/api.md");
    }
}
