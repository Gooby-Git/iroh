package dev.elemore.iroh.api;

/** A pipeline from {@link IrohDraw#pipeline}. Immutable, kept by Iroh across resizes; any thread may hold it. */
public interface PipelineHandle {
    PipelineDecl decl();

    boolean valid();
}
