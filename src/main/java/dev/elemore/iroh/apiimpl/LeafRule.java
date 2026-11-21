package dev.elemore.iroh.apiimpl;

import java.util.function.IntPredicate;

/**
 * Iroh internal (P1-6b): the leaf-culling rule as a pure function (no Minecraft state), so {@code ./gradlew apiTest}
 * can check it headless. Described in {@link LeafCulling}.
 */
public final class LeafRule {
    private LeafRule() {
    }

    /**
     * @param depth           how many blocks out of the face to look (1..4)
     * @param leafAt          whether the block {@code i} steps out of the face (1-based) counts as leaves
     * @param positionRandom  this block position's fixed random number in [0, 1)
     * @param rejection       share of partly covered faces to hide anyway, 0..1
     * @return true to hide the face
     */
    public static boolean hide(int depth, IntPredicate leafAt, float positionRandom, float rejection) {
        for (int i = 1; i <= depth; i++) {
            if (!leafAt.test(i))
                return i > 1 && positionRandom <= rejection;
        }
        return true;
    }

    /** {@code new java.util.Random(seed).nextFloat()} per the JDK's documented algorithm, without allocating. */
    public static float seededFloat(long seed) {
        long s = (seed ^ 0x5DEECE66DL) & ((1L << 48) - 1);
        s = (s * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
        return (int) (s >>> 24) / (float) (1 << 24);
    }
}
