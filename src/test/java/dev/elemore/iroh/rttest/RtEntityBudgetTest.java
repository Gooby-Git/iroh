package dev.elemore.iroh.rttest;

import dev.elemore.iroh.rt.RtEntityBudget;
import dev.elemore.iroh.rt.RtEntityBudget.Candidate;
import dev.elemore.iroh.rt.vk.RtAccel;

import java.util.ArrayList;
import java.util.List;

import static dev.elemore.iroh.rttest.RtSeamTest.check;

/** P5-3: the entity admission rule (no device): radius, nearest first, caps, refit slots, hysteresis, motion history. */
final class RtEntityBudgetTest {
    static void run() {
        RtEntityBudget b = new RtEntityBudget(new RtEntityBudget.Limits(48, 3, 14, 1, 30));
        List<Candidate> c = new ArrayList<>(List.of(new Candidate(1, 60, 6, true), new Candidate(2, 5, 6, true), new Candidate(3, 10, 6, true),
                new Candidate(4, 12, 6, true), new Candidate(5, 3, 40, false), new Candidate(6, 4, 40, false)));
        RtEntityBudget.Selection s = b.select(c);
        check("entity budget: beyond the radius is raster-only (" + s.excludedByRadius + " == 1)", s.excludedByRadius == 1);
        check("entity budget: nearest rigid first within the part cap (ids " + ids(s.rigid) + " == [2, 3])", ids(s.rigid).equals(List.of(2, 3)));
        check("entity budget: one refit slot for free-form entities (ids " + ids(s.refit) + " == [5])", ids(s.refit).equals(List.of(5)));
        check("entity budget: instances = rigid parts + refit entities (" + s.instances() + " == 13)", s.instances() == 13);
        // hysteresis: entity 3 at 10 vs a new entity 7 at 9.5: 3 keeps its slot (competes at 8.5)
        c.set(3, new Candidate(7, 9.5, 6, true));
        RtEntityBudget.Selection s2 = b.select(c);
        check("entity budget: hysteresis keeps an admitted entity against a slightly nearer newcomer (ids " + ids(s2.rigid) + " == [2, 3])", ids(s2.rigid).equals(List.of(2, 3)));
        check("entity budget: refit rebuild every 30 frames, refit otherwise", b.rebuildRefit(0) && !b.rebuildRefit(1) && b.rebuildRefit(30));
        RtEntityBudget.Motion m = new RtEntityBudget.Motion();
        float[][] p0 = {{1}}, p1 = {{2}};
        boolean first = m.previous(9, p0) == p0;
        m.endFrame();
        boolean second = m.previous(9, p1) == p0;
        m.endFrame();
        m.endFrame();
        check("entity motion: first sighting has prev = cur, next frame prev = last pose, gone entities forgotten", first && second && m.tracked() == 0);
        check("entity masks: entity instances are SOLID|ENTITY, the player's own body SELF only (shadow/reflection rays only)",
                (RtAccel.MASK_ENTITY & RtAccel.MASK_SOLID) == 0 && (RtAccel.MASK_SELF & (RtAccel.MASK_SOLID | RtAccel.MASK_ENTITY | RtAccel.MASK_TRANSLUCENT)) == 0);
    }

    static List<Integer> ids(List<Candidate> l) {
        List<Integer> r = new ArrayList<>();
        for (Candidate x : l) r.add(x.id());
        return r;
    }
}
