package dev.elemore.iroh.configtest;

import dev.elemore.iroh.config.FogDecision;
import dev.elemore.iroh.config.FogDecision.*;

/**
 * rc14 (td-f57b86): camera states x option values -> expected fog start / end / shape. Expected values are written out
 * by hand from vanilla 1.21.1 FogRenderer.setupFog (javap of the named jar) and the OpenGL pack's Sodium Extra rule;
 * Iroh's {@link FogDecision} must produce them. {@code ./gradlew fogDecisionTest}; last line "# fogdecision PASS n/n".
 */
public final class FogDecisionTest {
    private static int pass, fail;

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    static Camera cam(Mode m, Fluid f, Effect e, float ev, float d, boolean thick) {
        return new Camera(m, f, false, false, e, ev, d, thick, 1.0F, false);
    }

    static boolean eq(Fog a, float s, float e, Shape sh) {
        return Math.abs(a.start() - s) < 1e-3 && Math.abs(a.end() - e) < 1e-3 && a.shape() == sh;
    }

    /** One row: vanilla value, then what the option leaves with -1 and with 0, given the boss bar state. */
    static void row(String name, Camera c, boolean boss, float s, float e, Shape sh, boolean offRemoves) {
        Fog v = FogDecision.vanilla(c);
        check(name + ": vanilla " + s + ".." + e + " " + sh + " (got " + v + ")", eq(v, s, e, sh));
        for (int option : new int[]{-1, 0}) {
            Fog out = FogDecision.apply(option, c.mode(), c.fluid(), c.effect(), boss, c.distance(), c.thickFog(), v);
            boolean removed = out.start() == FogDecision.OFF && out.end() == FogDecision.OFF;
            boolean want = option == -1 && offRemoves;
            check(name + ": option " + option + " -> " + (want ? "no fog" : "vanilla unchanged"), want ? removed : out.equals(v));
        }
    }

    public static void main(String[] args) {
        float rd = 24 * 16; // 384 blocks, RD 24 (setupFog gets max(renderDistance, 32))
        // atmospheric terrain fog: start = d - clamp(d/10, 4, 64), end = d, cylinder -> the ONLY thing -1 removes
        row("overworld terrain RD24", cam(Mode.TERRAIN, Fluid.NONE, Effect.NONE, 0, rd, false), false, 384 - 38.4F, 384, Shape.CYLINDER, true);
        row("overworld terrain RD2 (d=32)", cam(Mode.TERRAIN, Fluid.NONE, Effect.NONE, 0, 32, false), false, 28, 32, Shape.CYLINDER, true);
        row("overworld terrain RD48", cam(Mode.TERRAIN, Fluid.NONE, Effect.NONE, 0, 768, false), false, 704, 768, Shape.CYLINDER, true);
        // sky fog stays (Sodium Extra: disablesFog -> return before touching the sky)
        row("sky RD24", cam(Mode.SKY, Fluid.NONE, Effect.NONE, 0, rd, false), false, 0, 384, Shape.CYLINDER, false);
        // boss world fog: thick fog formula, start d*0.05, end min(d,192)*0.5, sphere; the End dragon fight stays foggy
        row("End dragon fight (boss bar fog)", cam(Mode.TERRAIN, Fluid.NONE, Effect.NONE, 0, rd, true), true, 19.2F, 96, Shape.SPHERE, false);
        row("End dragon fight sky", cam(Mode.SKY, Fluid.NONE, Effect.NONE, 0, rd, true), true, 19.2F, 96, Shape.SPHERE, false);
        // foggy dimension (Nether isFoggyAt) without a boss: the GL pack removes it, Iroh too
        row("Nether thick fog, no boss", cam(Mode.TERRAIN, Fluid.NONE, Effect.NONE, 0, rd, true), false, 19.2F, 96, Shape.SPHERE, true);
        row("Nether thick fog during a Wither fight", cam(Mode.TERRAIN, Fluid.NONE, Effect.NONE, 0, rd, true), true, 19.2F, 96, Shape.SPHERE, false);
        // effects: blindness lerp(min(1, ticks/20), d, 5): start f*0.25, end f; infinite -> 5
        row("blindness 10 ticks left", cam(Mode.TERRAIN, Fluid.NONE, Effect.BLINDNESS, 10, rd, false), false, (384 + 0.5F * (5 - 384)) * 0.25F, 384 + 0.5F * (5 - 384), Shape.SPHERE, false);
        row("blindness long", cam(Mode.TERRAIN, Fluid.NONE, Effect.BLINDNESS, 600, rd, false), false, 1.25F, 5, Shape.SPHERE, false);
        row("blindness infinite, sky", cam(Mode.SKY, Fluid.NONE, Effect.BLINDNESS, -1, rd, false), false, 0, 4, Shape.SPHERE, false);
        // darkness lerp(blend, d, 15): start f*0.75 (sky 0), end f
        row("darkness full", cam(Mode.TERRAIN, Fluid.NONE, Effect.DARKNESS, 1, rd, false), false, 11.25F, 15, Shape.SPHERE, false);
        row("darkness half", cam(Mode.TERRAIN, Fluid.NONE, Effect.DARKNESS, 0.5F, rd, false), false, (384 + 0.5F * (15 - 384)) * 0.75F, 384 + 0.5F * (15 - 384), Shape.SPHERE, false);
        // fluids
        row("lava", cam(Mode.TERRAIN, Fluid.LAVA, Effect.NONE, 0, rd, false), false, 0.25F, 1, Shape.SPHERE, false);
        row("lava + fire resistance", new Camera(Mode.TERRAIN, Fluid.LAVA, false, true, Effect.NONE, 0, rd, false, 1, false), false, 0, 5, Shape.SPHERE, false);
        row("lava spectator", new Camera(Mode.TERRAIN, Fluid.LAVA, true, false, Effect.NONE, 0, rd, false, 1, false), false, -8, 192, Shape.SPHERE, false);
        row("powder snow", cam(Mode.TERRAIN, Fluid.POWDER_SNOW, Effect.NONE, 0, rd, false), false, 0, 2, Shape.SPHERE, false);
        row("water, full water vision", cam(Mode.TERRAIN, Fluid.WATER, Effect.NONE, 0, rd, false), false, -8, 96, Shape.SPHERE, false);
        row("water, no water vision, swamp (closer fog)", new Camera(Mode.TERRAIN, Fluid.WATER, false, false, Effect.NONE, 0, rd, false, 0, true), false, -8, 96 * 0.25F * 0.85F, Shape.SPHERE, false);
        row("water at RD2 (end clamped to d, cylinder)", cam(Mode.TERRAIN, Fluid.WATER, Effect.NONE, 0, 32, false), false, -8, 32, Shape.CYLINDER, false);
        // precedence: lava beats blindness; blindness beats water
        row("lava + blindness", cam(Mode.TERRAIN, Fluid.LAVA, Effect.BLINDNESS, 600, rd, false), false, 0.25F, 1, Shape.SPHERE, false);
        row("water + blindness", cam(Mode.TERRAIN, Fluid.WATER, Effect.BLINDNESS, 600, rd, false), false, 1.25F, 5, Shape.SPHERE, false);

        // a mod set its own terrain fog earlier in setupFog (biome fog, custom fluid): kept with -1
        Fog modFog = new Fog(10, 60, Shape.SPHERE);
        check("mod fog in open air (start 10, end 60): option -1 keeps it",
                FogDecision.apply(-1, Mode.TERRAIN, Fluid.NONE, Effect.NONE, false, rd, false, modFog).equals(modFog));
        Fog modShape = new Fog(384 - 38.4F, 384, Shape.SPHERE);
        check("vanilla distances but another shape (a mod changed it): kept", !FogDecision.removesFog(-1, Mode.TERRAIN, Fluid.NONE, Effect.NONE, false, rd, false, modShape));
        // the option values that are not -1 never change anything (0 vanilla; fixed distances read as vanilla)
        Fog atm = FogDecision.vanilla(cam(Mode.TERRAIN, Fluid.NONE, Effect.NONE, 0, rd, false));
        check("option 8 (unverified fixed distance, reads as vanilla): unchanged", FogDecision.apply(8, Mode.TERRAIN, Fluid.NONE, Effect.NONE, false, rd, false, atm).equals(atm));
        check("removed fog keeps the shape", FogDecision.apply(-1, Mode.TERRAIN, Fluid.NONE, Effect.NONE, false, rd, false, atm).shape() == Shape.CYLINDER);

        System.out.println("# fogdecision " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
