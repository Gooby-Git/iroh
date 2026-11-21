package dev.elemore.iroh.api;

/**
 * Placeholder (api 3): off-screen render targets (create / bindForDraw / asSampler / async readback). Capability RENDER_TARGETS.
 * Not implemented before Phase 2 / 9 (Vista's renderer): every call throws {@link UnsupportedOperationException}; check
 * {@link IrohApi#capabilities()} first. The contract is written in knowledge/api.md so designs can start now.
 */
public final class IrohTargets {
    private IrohTargets() {
    }

    public static Object create(Object... args) {
        throw new UnsupportedOperationException("IrohTargets: not available yet (Phase 2/9); see knowledge/api.md");
    }
}
