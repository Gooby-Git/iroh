// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.data;

@FunctionalInterface
public interface ClassTable {
    /** {@link BlockClass} of a block-state id. Unknown ids must answer {@link BlockClass#SOLID}. */
    int classOf(int block);
}
