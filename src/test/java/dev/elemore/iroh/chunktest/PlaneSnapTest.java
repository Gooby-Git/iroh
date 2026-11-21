package dev.elemore.iroh.chunktest;

import net.vulkanmod.render.vertex.PlaneSnap;

/**
 * rc10 (td-242adb): terrain position quantisation keeps sub-step planes on their side of block planes. Powder snow's
 * inward faces (0.000125 inside each side), synthetic insets 0.0001..0.001 on both sides of every block plane in a
 * section, stacked pairs, exactness for on-grid coordinates (1/16 model steps, every 1/2048 step), monotonicity and the
 * one-step bound. {@code ./gradlew planeSnapTest}; last line "# planesnap PASS n/n".
 */
public final class PlaneSnapTest {
    static int pass, fail;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    public static void main(String[] args) {
        final float step = 1f / 2048f;
        // powder snow: inward faces at b + 0.000125 and b + 1 - 0.000125 for every block b in the section
        boolean powder = true;
        for (int b = -1; b <= 16; b++) {
            float lo = PlaneSnap.snap(b + 0.000125f), hi = PlaneSnap.snap(b + 1 - 0.000125f);
            powder &= lo > b && lo < b + 1 && hi < b + 1 && hi > b;
            powder &= PlaneSnap.snap(b) == b && PlaneSnap.snap(b + 1) == b + 1;
        }
        check("powder snow inward faces stay strictly inside their block, outward faces exactly on the block planes", powder);

        // synthetic insets on both sides of every plane
        int wrongSide = 0, tooFar = 0, n = 0;
        for (int b = -2; b <= 18; b++)
            for (int k = 1; k <= 10; k++) {
                float d = k * 0.0001f;
                for (float v : new float[]{b + d, b - d}) {
                    float q = PlaneSnap.snap(v);
                    n++;
                    if (Math.signum(q - b) != Math.signum(v - b)) wrongSide++;
                    if (Math.abs(q - v) > step * 1.0001f) tooFar++;
                }
            }
        check(n + " insets 0.0001..0.001 on both sides of every block plane: 0 on the wrong side (" + wrongSide + "), within one step (" + tooFar + ")",
                wrongSide == 0 && tooFar == 0);

        // on-grid exactness
        int moved = 0;
        for (int i = -4 * 2048; i <= 20 * 2048; i++) {
            float v = i / 2048f;
            if (PlaneSnap.snap(v) != v) moved++;
        }
        for (int i = -16 * 4; i <= 16 * 20; i++)
            if (PlaneSnap.snap(i / 16f) != i / 16f) moved++;
        check("every on-grid coordinate (1/2048 steps, 1/16 model units) unchanged (" + moved + " moved)", moved == 0);

        // stacked pairs and monotonicity
        int inverted = 0;
        java.util.SplittableRandom r = new java.util.SplittableRandom(5);
        for (int i = 0; i < 1_000_000; i++) {
            float a = (float) (r.nextDouble() * 20 - 2), c = a + (float) (r.nextDouble() * 0.002);
            if (PlaneSnap.snap(a) > PlaneSnap.snap(c)) inverted++;
        }
        float p1 = PlaneSnap.snap(1 - 0.0001f), p2 = PlaneSnap.snap(1 - 0.0004f), p0 = PlaneSnap.snap(1f);
        check("stacked insets below a plane stay below it and in order (" + p2 + " <= " + p1 + " < " + p0 + "); 1e6 random pairs never inverted ("
                + inverted + ")", p2 <= p1 && p1 < p0 && inverted == 0);

        System.out.println("# planesnap " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
