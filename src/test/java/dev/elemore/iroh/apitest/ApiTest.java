package dev.elemore.iroh.apitest;

import dev.elemore.iroh.apiimpl.LeafRule;

import java.util.Random;
import java.util.SplittableRandom;

/**
 * Headless checks for the P1-6b api internals that need no game: the leaf-culling rule against the behaviour read from
 * Cull Fewer Leaves 1.1.2's bytecode (transcribed as an independent oracle below), and the per-position random against
 * java.util.Random. {@code ./gradlew apiTest}; last line "# api PASS n/n".
 */
public final class ApiTest {
    private static int pass, fail;

    public static void main(String[] args) {
        // 1. seededFloat == new Random(seed).nextFloat(), including negative and huge seeds (BlockPos.asLong values)
        SplittableRandom r = new SplittableRandom(1234);
        int bad = 0;
        for (int i = 0; i < 200_000; i++) {
            long seed = i < 1000 ? i - 500 : r.nextLong();
            if (LeafRule.seededFloat(seed) != new Random(seed).nextFloat())
                bad++;
        }
        check("seededFloat matches java.util.Random over 200000 seeds", bad == 0);

        // 2. rule == oracle for every leaf pattern, depth 1..4, random values around the threshold
        float[] rands = {0f, 0.1f, 0.19999f, 0.2f, 0.20001f, 0.5f, 0.99999f};
        float[] rejections = {0f, 0.2f, 1f};
        int cases = 0;
        bad = 0;
        for (int depth = 1; depth <= 4; depth++) {
            for (int mask = 0; mask < (1 << depth); mask++) {
                final int m = mask;
                for (float rand : rands) {
                    for (float rej : rejections) {
                        cases++;
                        boolean got = LeafRule.hide(depth, i -> (m & (1 << (i - 1))) != 0, rand, rej);
                        if (got != oracle(depth, m, rand, rej))
                            bad++;
                    }
                }
            }
        }
        check("leaf rule matches the Cull Fewer Leaves behaviour in " + cases + " cases", bad == 0);

        // 3. the pack's settings: depth 2, 20%: an outer face is never hidden, a fully covered one always
        check("outer face kept", !LeafRule.hide(2, i -> false, 0f, 0.2f));
        check("covered face hidden", LeafRule.hide(2, i -> true, 0.99f, 0.2f));
        check("partly covered, rand 0.1 <= 0.2: hidden", LeafRule.hide(2, i -> i == 1, 0.1f, 0.2f));
        check("partly covered, rand 0.3 > 0.2: kept", !LeafRule.hide(2, i -> i == 1, 0.3f, 0.2f));

        System.out.println("# api " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        if (fail != 0)
            System.exit(1);
    }

    /** Cull Fewer Leaves 1.1.2 shouldCullSide, as observed in its bytecode: AND over all depths, then the random step. */
    private static boolean oracle(int depth, int mask, float rand, float rejection) {
        boolean all = true, firstMissing = false;
        for (int i = 1; i <= depth; i++) {
            all &= (mask & (1 << (i - 1))) != 0;
            if (!all && i == 1)
                firstMissing = true;
        }
        if (!firstMissing && !all && rand <= rejection)
            all = true;
        return all;
    }

    private static void check(String what, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }
}
