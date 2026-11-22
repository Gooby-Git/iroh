package dev.elemore.iroh.upscale.fsr;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;

/**
 * Loads libiroh_fsr from the jar ({@code /natives/<os>-<arch>/}) or from -Diroh.fsr.lib=path (tests, dev). The library is
 * extracted to a content-hash named file under the cache dir (-Diroh.fsr.cache, default ${java.io.tmpdir}/iroh-natives) and
 * System.load'ed. Never throws: failure means "unavailable" with a reason string the UI shows.
 */
public final class NativeLoader {
    private static boolean tried, loaded;
    private static String reason = "not tried";

    private NativeLoader() {}

    public static synchronized boolean load() {
        if (tried) return loaded;
        tried = true;
        try {
            String explicit = System.getProperty("iroh.fsr.lib");
            if (explicit != null) {
                System.load(Path.of(explicit).toAbsolutePath().toString());
            } else {
                String os = System.getProperty("os.name", "").toLowerCase();
                String arch = System.getProperty("os.arch", "");
                String plat = (os.contains("win") ? "windows" : os.contains("linux") ? "linux" : null) + "-" +
                        (arch.equals("amd64") || arch.equals("x86_64") ? "x64" : arch);
                if (plat.startsWith("null")) throw new IllegalStateException("no FSR build for OS " + os);
                String file = os.contains("win") ? "iroh_fsr.dll" : "libiroh_fsr.so";
                String res = "/natives/" + plat + "/" + file;
                byte[] data;
                try (InputStream in = NativeLoader.class.getResourceAsStream(res)) {
                    if (in == null) throw new IllegalStateException("this jar has no " + res + " (built without iroh-native; run iroh-native/build-fsr.sh)");
                    data = in.readAllBytes();
                }
                String hash = hex(MessageDigest.getInstance("SHA-256").digest(data)).substring(0, 16);
                Path dir = Path.of(System.getProperty("iroh.fsr.cache", System.getProperty("java.io.tmpdir") + "/iroh-natives"));
                Files.createDirectories(dir);
                Path out = dir.resolve(hash + "-" + file);
                if (!Files.exists(out) || Files.size(out) != data.length) {
                    Path tmp = Files.createTempFile(dir, "fsr", ".tmp");
                    Files.write(tmp, data);
                    Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }
                System.load(out.toAbsolutePath().toString());
            }
            int sz = FsrNative.structSize();
            if (sz != FsrDispatchStruct.SIZE) throw new IllegalStateException("native struct size " + sz + " != Java " + FsrDispatchStruct.SIZE);
            loaded = true;
            reason = "";
        } catch (Throwable t) {
            loaded = false;
            reason = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        return loaded;
    }

    /** Empty when loaded, else why FSR is unavailable. */
    public static synchronized String unavailableReason() {
        return loaded ? "" : reason;
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
