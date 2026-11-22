package dev.elemore.iroh.rttest;

import dev.elemore.iroh.rt.denoise.*;
import dev.elemore.iroh.rt.denoise.nrd.*;

import java.util.Map;

/** Pure denoiser-interface parts + (when libiroh_nrd is present) checks of the instance description against NRD. Last line "# denoise PASS n/n". */
public final class DenoiseTest {
    static int pass, total;

    static void check(boolean ok, String what) {
        total++;
        if (ok) pass++;
        else System.out.println("FAIL " + what);
    }

    public static void main(String[] a) {
        // slot sets: inputs / outputs classification
        for (Slot s : Slot.values()) {
            boolean in = s.name().startsWith("IN_"), out = s.name().startsWith("OUT_");
            check(s.isInput() == in && s.isOutput() == out, "slot direction " + s);
        }
        check(Slot.IN_MV.ordinal() == 0 && Slot.OUT_VALIDATION.ordinal() == 29 && Slot.PERMANENT_POOL.ordinal() == 31 && Slot.IN_ALBEDO.ordinal() == 32, "slot ordinals follow nrd::ResourceType");
        for (DenoiserType t : DenoiserType.values()) {
            check(!t.noisyInputs().isEmpty() && !t.outputs().isEmpty(), "type has inputs and outputs " + t);
            check(t.noisyInputs().stream().allMatch(Slot::isInput) && t.outputs().stream().allMatch(Slot::isOutput), "type slots in the right direction " + t);
        }
        check(DenoiserType.SIGMA_SHADOW.guides().size() == 2 && DenoiserType.RELAX_DIFFUSE.guides().size() == 3, "guide sets");
        // settings name mapping
        float[] kv = NrdSettings.toKeyValues(0, Map.of("maxAccumulatedFrames", 20f));
        check(kv.length == 2 && kv[0] == 3 && kv[1] == 20f, "REBLUR key mapping");
        kv = NrdSettings.toKeyValues(2, Map.of("lightDirY", 1f));
        check(kv.length == 2 && kv[0] == 1 && kv[1] == 1f, "SIGMA key mapping");
        boolean threw = false;
        try { NrdSettings.toKeyValues(1, Map.of("nope", 1f)); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "unknown setting rejected");
        // formats
        check(NrdFormats.bytesPerPixel(27) == 8 && NrdFormats.bytesPerPixel(17) == 2 && NrdFormats.bytesPerPixel(40) == 4 && NrdFormats.bytesPerPixel(0) == 1, "format sizes");
        check(NrdFormats.name(40).equals("R10_G10_B10_A2_UNORM"), "format names");
        // encoders used by the offline scene
        float[] y = NrdScene.ycocg(0.3f, 0.6f, 0.1f), back = NrdScene.fromYcocg(y[0], y[1], y[2]);
        check(Math.abs(back[0] - 0.3f) < 1e-5 && Math.abs(back[1] - 0.6f) < 1e-5 && Math.abs(back[2] - 0.1f) < 1e-5, "YCoCg round trip");
        if (NrdLoader.load()) {
            System.out.println("native: " + NrdNative.version());
            int[] lib = NrdNative.libraryDesc();
            check(lib[4] == 4 && lib[5] == 18 && lib[0] == 0 && lib[1] == 20 && lib[2] == 2 && lib[3] == 3, "library version 4.18 and SPIR-V binding offsets 0/20/2/3");
            long h = NrdNative.create(new int[]{DenoiserType.RELAX_DIFFUSE_SPECULAR.nrdValue, DenoiserType.SIGMA_SHADOW.nrdValue});
            NrdInstanceDesc d = NrdInstanceDesc.parse(NrdNative.instanceDesc(h));
            check(d.pipelines.length == 22 && d.permanent.length == 11 && d.transient_.length == 13 && d.samplers.length == 2, "RELAX_DS + SIGMA description counts (22 pipelines, 11+13 textures)");
            check(d.resSpace == 0 && d.cbSpace == 1 && d.cbMaxSize == 752, "register spaces 0/1, constant buffer 752 B");
            check(d.poolBytes(d.permanent, 1707, 960) + d.poolBytes(d.transient_, 1707, 960) > 100_000_000L, "pool memory plausible at 1707x960");
            for (int i = 0; i < d.pipelines.length; i++) check(NrdNative.spirv(h, i).capacity() == d.pipelines[i].spirvSize(), "embedded SPIR-V size pipeline " + i);
            NrdNative.destroy(h);
            boolean bad = false;
            try { NrdNative.create(new int[]{99}); } catch (NrdException e) { bad = e.getMessage().contains("nrd::CreateInstance"); }
            check(bad, "invalid denoiser id throws NrdException naming the call");
        } else {
            System.out.println("NRD library not loaded (" + NrdLoader.unavailableReason() + "): library checks skipped");
        }
        System.out.println("# denoise " + (pass == total ? "PASS " : "FAIL ") + pass + "/" + total);
        if (pass != total) System.exit(1);
    }
}
