package dev.elemore.iroh.natives;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.function.IntSupplier;

/**
 * Loads one optional native library, never throwing: failure means "unavailable" with a reason string for the UI.
 * Search order: -D&lt;sysProp&gt;=path (tests, dev) -> the jar ({@code /natives/<os>-<arch>/<file>}) -> the vendor folder
 * ({@code -Diroh.vendor.dir}, default {@code config/iroh/vendor}) for components that are not bundled for licence reasons
 * (NVIDIA RTX SDKs: NRD, DLSS). A jar copy is extracted to a content-hash named file under -Diroh.natives.cache
 * (default ${java.io.tmpdir}/iroh-natives) before System.load.
 */
public final class NativeLib {
    private final String sysProp, baseName;
    private final IntSupplier sanityCheck;     // returns 0 when the loaded library matches the Java side, else an error code printed in the reason
    private boolean tried, loaded;
    private String reason = "not tried";
    private String source = "";

    public NativeLib(String sysProp, String baseName, IntSupplier sanityCheck) {
        this.sysProp = sysProp;
        this.baseName = baseName;
        this.sanityCheck = sanityCheck;
    }

    public static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "");
        String o = os.contains("win") ? "windows" : os.contains("linux") ? "linux" : null;
        if (o == null) throw new IllegalStateException("no native build for OS " + os);
        return o + "-" + (arch.equals("amd64") || arch.equals("x86_64") ? "x64" : arch);
    }

    public static String fileName(String baseName) {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? baseName + ".dll" : "lib" + baseName + ".so";
    }

    public synchronized boolean load() {
        if (tried) return loaded;
        tried = true;
        try {
            String explicit = sysProp == null ? null : System.getProperty(sysProp);
            String file = fileName(baseName);
            if (explicit != null) {
                System.load(Path.of(explicit).toAbsolutePath().toString());
                source = explicit;
            } else {
                String res = "/natives/" + platform() + "/" + file;
                byte[] data;
                try (InputStream in = NativeLib.class.getResourceAsStream(res)) {
                    data = in == null ? null : in.readAllBytes();
                }
                if (data != null) {
                    String hash = hex(MessageDigest.getInstance("SHA-256").digest(data)).substring(0, 16);
                    Path dir = Path.of(System.getProperty("iroh.natives.cache", System.getProperty("java.io.tmpdir") + "/iroh-natives"));
                    Files.createDirectories(dir);
                    Path out = dir.resolve(hash + "-" + file);
                    if (!Files.exists(out) || Files.size(out) != data.length) {
                        Path tmp = Files.createTempFile(dir, "nat", ".tmp");
                        Files.write(tmp, data);
                        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    }
                    System.load(out.toAbsolutePath().toString());
                    source = "jar:" + res;
                } else {
                    Path vendor = Path.of(System.getProperty("iroh.vendor.dir", "config/iroh/vendor"), file);
                    if (!Files.isRegularFile(vendor))
                        throw new IllegalStateException(file + " is not in this jar (" + res + ") and not in the vendor folder " + vendor.toAbsolutePath()
                                + " (optional component: see docs/P6-PLAN.md)");
                    System.load(vendor.toAbsolutePath().toString());
                    source = vendor.toString();
                }
            }
            int bad = sanityCheck == null ? 0 : sanityCheck.getAsInt();
            if (bad != 0) throw new IllegalStateException("native/Java version mismatch (code " + bad + ")");
            loaded = true;
            reason = "";
        } catch (Throwable t) {
            loaded = false;
            reason = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        return loaded;
    }

    public synchronized String unavailableReason() {
        return loaded ? "" : reason;
    }

    public synchronized String source() {
        return source;
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
