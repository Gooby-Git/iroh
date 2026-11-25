import dev.elemore.iroh.lod.nat.LodNativeLoader;
import dev.elemore.iroh.natives.NativeLib;
import dev.elemore.iroh.rt.denoise.nrd.NrdLoader;
import dev.elemore.iroh.upscale.fsr.NativeLoader;

/** Iroh W-1: runs Iroh's REAL loaders (NativeLib search order + the Java/native struct-size sanity check) on a (Windows) JVM.
 *  usage: java [-Diroh.fsr.lib=..] [-Diroh.nrd.lib=..] [-Diroh.vendor.dir=..] -cp <classes>[;<dir with natives/>] LoaderSmoke fsr|nrd */
public class LoaderSmoke {
    public static void main(String[] a) {
        System.out.println("os.name=" + System.getProperty("os.name") + " platform=" + NativeLib.platform() + " file=" + NativeLib.fileName("iroh_fsr"));
        if (a[0].equals("lod")) {   // VL-1: the real loader + ABI check; a public build must report no seed
            boolean ok = LodNativeLoader.load();
            System.out.println("lod load=" + ok + " reason=[" + LodNativeLoader.unavailableReason() + "] locate=" + LodNativeLoader.locate() + " hasSeed=" + LodNativeLoader.hasSeed());
            System.exit(ok && !LodNativeLoader.hasSeed() ? 0 : 1);
        }
        boolean fsr = a[0].equals("fsr");
        System.out.println("locate: " + (fsr ? NativeLoader.locate() : NrdLoader.locate()));
        boolean ok = fsr ? NativeLoader.load() : NrdLoader.load();
        System.out.println(a[0] + " load=" + ok + " reason=[" + (fsr ? NativeLoader.unavailableReason() : NrdLoader.unavailableReason()) + "] locate=" + (fsr ? NativeLoader.locate() : NrdLoader.locate()));
        System.exit(ok ? 0 : 1);
    }
}
