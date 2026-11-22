package dev.elemore.iroh.packtest;

import dev.elemore.iroh.shaderpack.runtime.Substitution;
import dev.elemore.iroh.shaderpack.runtime.VanillaLayout;

import java.util.List;
import java.util.Set;

/** P3-10: program substitution decisions (pure). {@code ./gradlew substitutionTest}; last line {@code # substitution PASS n/n}. */
public final class SubstitutionTest {
    static int pass, total;

    static void check(String what, boolean ok) {
        total++;
        if (ok) pass++;
        else System.out.println("FAIL " + what);
    }

    public static void main(String[] args) {
        List<String> newEntity = List.of("Position", "Color", "UV0", "UV1", "UV2", "Normal");
        List<String> particle = List.of("Position", "UV0", "Color", "UV2");
        check("entity solid -> entities", "entities".equals(Substitution.classFor("rendertype_entity_solid", newEntity)));
        check("entity translucent -> entities_translucent", "entities_translucent".equals(Substitution.classFor("rendertype_entity_translucent", newEntity)));
        check("lines -> outline", "outline".equals(Substitution.classFor("rendertype_lines", List.of("Position", "Color", "Normal"))));
        check("crumbling -> damaged_block", "damaged_block".equals(Substitution.classFor("rendertype_crumbling", newEntity)));
        check("beacon", "beacon_beam".equals(Substitution.classFor("rendertype_beacon_beam", newEntity)));
        check("particle", "particles_opaque".equals(Substitution.classFor("particle", particle)));
        check("sky disc", "sky_basic".equals(Substitution.classFor("position", List.of("Position"))));
        check("terrain is never substituted per draw", Substitution.classFor("rendertype_solid", null) == null);
        check("unknown mod shader with entity format -> entities", "entities".equals(Substitution.classFor("somemod:fancy_mob", newEntity)));
        check("unknown mod shader with particle format -> textured lit", "particles_opaque".equals(Substitution.classFor("somemod:sparks", particle)));
        check("unknown mod shader position-only -> unmapped (raster)", Substitution.classFor("somemod:thing", List.of("Position")) == null);
        check("GUI stays raster", Substitution.rasterOnly("rendertype_gui", true, Set.of()));
        check("outside the world pass stays raster", Substitution.rasterOnly("rendertype_entity_solid", false, Set.of()));
        check("iroh.pack.raster.mods forces a namespace raster", Substitution.rasterOnly("alexscaves:radiation", true, Set.of("alexscaves")));
        check("other mods not forced", !Substitution.rasterOnly("tempest:clouds", true, Set.of("alexscaves")));
        String lay = Substitution.layout(newEntity);
        check("NEW_ENTITY layout spec", "vanilla:Position,Color,UV0,UV1,UV2,Normal".equals(lay));
        check("NEW_ENTITY stride 36", VanillaLayout.of(lay).stride == 36);
        check("particle stride 28", VanillaLayout.of(Substitution.layout(particle)).stride == 28);
        check("unplaceable element -> null", Substitution.layout(List.of("Position", "ModTangent")) == null);
        check("observe off by default", !Substitution.OBSERVE);
        Substitution.observe("rendertype_entity_solid", newEntity, true, Set.of());
        Substitution.observe("rendertype_entity_solid", newEntity, true, Set.of());
        Substitution.observe("rendertype_gui", List.of("Position", "Color"), true, Set.of());
        String rep = Substitution.report();
        check("observe report: " + rep.replace('\n', '|'), rep.contains("rendertype_entity_solid -> entities [vanilla:Position,Color,UV0,UV1,UV2,Normal] x2")
                && rep.contains("rendertype_gui -> raster"));
        System.out.println("# substitution " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }
}
