package dev.elemore.iroh.shaderpack;

/**
 * Scope rule (owner, 2026-11-21, DECISIONS "Settings UI"): the loader serves Navidson only. No pack picker, no shader
 * options screen; Navidson's options are fixed at ship time and resolved at load (the front end constant-folds them
 * through the option rewriter and the preprocessor; the pass graph keeps only served, switched-on programs, used
 * targets and the dimension folders Navidson ships). Other packs remain free translator test inputs (packHarness /
 * packGraph) and are never loaded by the runtime.
 */
public final class NavidsonOnly {

    /** The marker file only Navidson has (its own settings file, owner-written). */
    public static final String MARKER = "lib/shaderSettings/navidson.glsl";

    private NavidsonOnly() {}

    /** @return null when {@code pack} is Navidson, else the reason the runtime refuses it */
    public static String refusal(PackSource pack) {
        if (!pack.exists(MARKER))
            return "Iroh's loader serves Navidson only (owner decision 2026-11-21): " + pack.name() + " has no " + MARKER;
        if (!pack.exists("shaders.properties"))
            return pack.name() + ": no shaders.properties";
        return null;
    }
}
