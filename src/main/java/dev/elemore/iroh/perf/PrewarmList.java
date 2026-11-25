package dev.elemore.iroh.perf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * P2-11 (L8): the pre-warm list, the graphics pipeline states first seen during play. Pure text codec (no Vulkan, no game),
 * stored through DiskCache (SHA-256 prefix: a torn write reads as absent). A list is accepted only when its header equals the
 * current one: Iroh build, driver id (vendor, device, driver version, pipeline-cache UUID) and a fingerprint of the mod set
 * and resource packs (shaders can change with either). A mismatch discards the list; recording then starts it afresh.
 * <pre>
 * #iroh-prewarm 1
 * build=...   driver=...   pack=...   count=N
 * P TAB name TAB assemblyRaster TAB blend TAB depth TAB logicOp TAB colorMask TAB colorFormat TAB depthFormat TAB passKind
 * end
 * </pre>
 */
public final class PrewarmList {
    public static final int VERSION = 1;
    public static final int MAX_ENTRIES = 4096;
    public static final int MAX_BYTES = 1 << 20;

    private PrewarmList() {}

    /**
     * one pipeline state; the formats and {@code passKind} (the subpass dependency the renderer's pass was built with, RenderPass.dependencyKind) stand for
     * the render pass: pipelines work in any render pass COMPATIBLE with theirs, and that means same attachment formats AND same subpass dependencies
     */
    public record Entry(String pipeline, int assembly, int blend, int depth, int logicOp, int colorMask, int colorFormat, int depthFormat, int passKind) {
        public boolean valid() {
            return pipeline != null && !pipeline.isEmpty() && pipeline.indexOf('\t') < 0 && pipeline.indexOf('\n') < 0 && pipeline.indexOf('\r') < 0;
        }
    }

    public record Header(String build, String driver, String pack) {
        boolean valid() {
            return ok(build) && ok(driver) && ok(pack);
        }

        private static boolean ok(String s) {
            return s != null && !s.isEmpty() && s.indexOf('\n') < 0 && s.indexOf('\r') < 0;
        }
    }

    public record Loaded(boolean accepted, String reason, List<Entry> entries) {}

    public static byte[] encode(Header h, Set<Entry> entries) {
        if (!h.valid()) throw new IllegalArgumentException("bad header");
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.valid() && out.size() < MAX_ENTRIES) out.add(e);
        }
        StringBuilder b = new StringBuilder(64 + out.size() * 64);
        b.append("#iroh-prewarm ").append(VERSION).append('\n')
                .append("build=").append(h.build()).append('\n')
                .append("driver=").append(h.driver()).append('\n')
                .append("pack=").append(h.pack()).append('\n')
                .append("count=").append(out.size()).append('\n');
        for (Entry e : out) {
            b.append("P\t").append(e.pipeline()).append('\t').append(e.assembly()).append('\t').append(e.blend()).append('\t').append(e.depth())
                    .append('\t').append(e.logicOp()).append('\t').append(e.colorMask()).append('\t').append(e.colorFormat()).append('\t')
                    .append(e.depthFormat()).append('\t').append(e.passKind()).append('\n');
        }
        b.append("end\n");
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Never throws: a list that is not exactly this build's, driver's and pack's is rejected with the reason, entries empty. */
    public static Loaded decode(byte[] data, Header expected) {
        if (data == null) return reject("no list");
        if (data.length > MAX_BYTES) return reject("too large");
        try {
            String[] lines = new String(data, StandardCharsets.UTF_8).split("\n", -1);
            if (lines.length < 6 || !lines[0].equals("#iroh-prewarm " + VERSION)) return reject("format version");
            String build = val(lines[1], "build="), driver = val(lines[2], "driver="), pack = val(lines[3], "pack=");
            if (build == null || driver == null || pack == null) return reject("header");
            if (!build.equals(expected.build())) return reject("Iroh build changed");
            if (!driver.equals(expected.driver())) return reject("driver changed");
            if (!pack.equals(expected.pack())) return reject("mods / resource packs changed");
            String c = val(lines[4], "count=");
            if (c == null) return reject("header");
            int count = Integer.parseInt(c);
            if (count < 0 || count > MAX_ENTRIES || lines.length != 5 + count + 2 || !lines[5 + count].equals("end") || !lines[6 + count].isEmpty())
                return reject("truncated or inconsistent");
            Set<Entry> seen = new LinkedHashSet<>();
            for (int i = 0; i < count; i++) {
                String[] f = lines[5 + i].split("\t", -1);
                if (f.length != 10 || !f[0].equals("P")) return reject("bad entry " + i);
                Entry e = new Entry(f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4]), Integer.parseInt(f[5]),
                        Integer.parseInt(f[6]), Integer.parseInt(f[7]), Integer.parseInt(f[8]), Integer.parseInt(f[9]));
                if (!e.valid()) return reject("bad entry " + i);
                seen.add(e);
            }
            return new Loaded(true, "ok", new ArrayList<>(seen));
        } catch (RuntimeException e) {
            return reject("unreadable: " + e.getMessage());
        }
    }

    /** driver id for the header: anything that makes a cached pipeline invalid or the shaders compile differently */
    public static String driverId(int vendor, int device, int driverVersion, int apiVersion, String cacheUuid) {
        return String.format("%08x:%08x:%08x:%08x:%s", vendor, device, driverVersion, apiVersion, cacheUuid);
    }

    /** order-independent fingerprint of a set of strings (mods, packs): SHA-256 hex of the sorted, newline-joined list */
    public static String fingerprint(java.util.Collection<String> parts) {
        java.util.List<String> sorted = new ArrayList<>(parts);
        java.util.Collections.sort(sorted);
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            for (String s : sorted) md.update((s + "\n").getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String val(String line, String key) {
        return line.startsWith(key) ? line.substring(key.length()) : null;
    }

    private static Loaded reject(String why) {
        return new Loaded(false, why, List.of());
    }
}
