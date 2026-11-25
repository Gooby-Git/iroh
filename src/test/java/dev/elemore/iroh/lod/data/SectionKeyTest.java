// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SectionKeyTest {
    @Test
    void packRoundTripsIncludingNegativesAndExtremes() {
        int[][] cases = {{0, 0, 0, 0}, {3, -1, 31, -1}, {1, 937500, 27, -937500}, {2, -67108864, 0, 67108863}, {4, 67108863, 63, -67108864}, {0, 12, 63, -7}};
        for (int[] c : cases) {
            SectionKey k = new SectionKey(c[0], c[1], c[2], c[3]);
            assertEquals(k, SectionKey.unpack(k.pack()), k.toString());
        }
    }

    @Test
    void legacyKeysFromOlderServersStillDecode() {
        int[][] cases = {{0, 0, 0, 0}, {3, -1, 31, -1}, {1, 937500, 27, -937500}, {2, -134217728, 0, 134217727}};
        for (int[] c : cases) {
            long v = (long) c[0] << 62 | (long) (c[2] & 63) << 56 | (c[1] & 0xFFFFFFFL) << 28 | c[3] & 0xFFFFFFFL;
            assertEquals(new SectionKey(c[0], c[1], c[2], c[3]), SectionKey.unpack(v, 2));
        }
    }
}
