package dev.elemore.iroh.chunktest;

import net.vulkanmod.render.chunk.cull.QuadFacing;

import java.util.SplittableRandom;

/**
 * rc10 (td-242adb): the per-facing bucket predicate never drops a face that can be front-facing. For every facing and
 * face plane in a section (including planes 0.002 inside a block, like powder snow's inward faces), and cameras inside
 * blocks, on face planes and one unit either side: if a face of that facing at that plane faces the camera, its bucket
 * is drawn. {@code ./gradlew facingMaskTest}; last line "# facingmask PASS n/n".
 */
public final class FacingMaskTest {
    public static void main(String[] args) {
        int pass = 0, fail = 0;
        SplittableRandom r = new SplittableRandom(11);
        int secX = 0, secY = -64, secZ = 0, dropped = 0, cases = 0;
        double[] specials = {0.0, 0.002, 0.5, 0.998, 1.0, 3.5, 3.998, 4.0, 15.998, 16.0};
        for (int k = 0; k < 400_000; k++) {
            double cx = secX + pick(r, specials), cy = secY + pick(r, specials), cz = secZ + pick(r, specials);
            int mask = QuadFacing.facingMask(cx, cy, cz, secX, secY, secZ);
            // a face plane somewhere in the section, at a block boundary or 0.002 inside a block
            int axis = r.nextInt(3);
            double plane = r.nextInt(17) + (r.nextBoolean() ? 0 : (r.nextBoolean() ? 0.002 : -0.002));
            plane = Math.max(0, Math.min(16, plane));
            double c = axis == 0 ? cx - secX : axis == 1 ? cy - secY : cz - secZ;
            boolean posFront = c > plane, negFront = c < plane; // strictly in front of the face plane
            QuadFacing pos = axis == 0 ? QuadFacing.X_POS : axis == 1 ? QuadFacing.Y_POS : QuadFacing.Z_POS;
            QuadFacing neg = axis == 0 ? QuadFacing.X_NEG : axis == 1 ? QuadFacing.Y_NEG : QuadFacing.Z_NEG;
            cases++;
            if (posFront && (mask & 1 << pos.ordinal()) == 0) dropped++;
            if (negFront && (mask & 1 << neg.ordinal()) == 0) dropped++;
        }
        boolean ok = dropped == 0;
        System.out.println((ok ? "ok   " : "FAIL ") + cases + " camera/face-plane cases (inside blocks, on planes, +-1 unit, 0.002 inside faces): " + dropped + " front faces dropped");
        if (ok) pass++; else fail++;
        // camera in a neighbouring section: the far side's buckets may be skipped, but never the near side's
        int m = QuadFacing.facingMask(secX - 1.5, secY + 8, secZ + 8, secX, secY, secZ);
        boolean nearOk = (m & 1 << QuadFacing.X_NEG.ordinal()) != 0 && (m & 1 << QuadFacing.X_POS.ordinal()) == 0;
        System.out.println((nearOk ? "ok   " : "FAIL ") + "camera west of the section: X_NEG drawn, X_POS skipped");
        if (nearOk) pass++; else fail++;
        System.out.println("# facingmask " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }

    private static double pick(SplittableRandom r, double[] specials) {
        double base = r.nextInt(3) == 0 ? specials[r.nextInt(specials.length)] : r.nextDouble() * 18 - 1;
        return base + (r.nextInt(4) == 0 ? (r.nextBoolean() ? 1 : -1) : 0);
    }
}
