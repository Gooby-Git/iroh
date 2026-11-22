package dev.elemore.iroh.rt;

/**
 * The ONE user-facing look switch (DECISIONS "Settings UI": no shader selection screen; an Iroh section in video
 * settings): Navidson through the pack loader (every card) or Iroh's ray tracer (RT-capable cards). Default NAVIDSON.
 */
public enum RtLook {
    NAVIDSON, RAY_TRACED
}
