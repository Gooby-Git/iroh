package dev.elemore.iroh.shaderpack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Stage (i) of the pack front end: a shader pack's file system, read the same way from a folder or a zip.
 * <p>
 * Paths are relative to the pack's {@code shaders/} folder, use '/', never start with '/', and never leave
 * {@code shaders/} (a path that normalises outside it is refused). Lookup is exact first; when that misses, a
 * case-insensitive match is taken and recorded in {@link #caseNotes()} (packs written on Windows reference
 * {@code /lib/Common.glsl} for {@code common.glsl}; OptiFine on Windows reads them, Linux would not).
 * A pack is untrusted content: files are bounded in size ({@link #MAX_FILE}) and count ({@link #MAX_FILES}).
 * Everything is read into memory once; the source is immutable afterwards and safe to share between threads.
 */
public final class PackSource {

    public static final int MAX_FILE = 8 << 20;
    public static final int MAX_FILES = 20000;

    private final String name;
    private final String origin;
    private final Map<String, byte[]> files;          // exact path -> bytes (TreeMap: sorted, deterministic)
    private final Map<String, String> lowerIndex;     // lower-case path -> exact path (first in sort order)
    private final List<String> caseNotes = Collections.synchronizedList(new ArrayList<>());

    private PackSource(String name, String origin, Map<String, byte[]> files) {
        this.name = name;
        this.origin = origin;
        this.files = files;
        this.lowerIndex = new HashMap<>();
        for (String p : files.keySet())
            lowerIndex.putIfAbsent(p.toLowerCase(Locale.ROOT), p);
    }

    /** Opens a pack folder (containing {@code shaders/}) or a zip (containing {@code shaders/}, possibly one folder deep). */
    public static PackSource open(Path path) throws IOException {
        String fn = path.getFileName().toString();
        if (Files.isDirectory(path)) {
            Path root = path.resolve("shaders");
            if (!Files.isDirectory(root))
                throw new IOException("no shaders/ folder in " + path);
            Map<String, byte[]> files = new TreeMap<>();
            try (Stream<Path> s = Files.walk(root)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (!Files.isRegularFile(p))
                        continue;
                    if (files.size() >= MAX_FILES)
                        throw new IOException("pack has more than " + MAX_FILES + " files");
                    if (Files.size(p) > MAX_FILE)
                        continue;
                    files.put(root.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
                }
            }
            return new PackSource(fn, path.toString(), files);
        }
        if (fn.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            Map<String, byte[]> files = new TreeMap<>();
            try (ZipFile zip = new ZipFile(path.toFile())) {
                // the shaders/ folder may sit at the root or one folder down (zips made from a folder)
                String prefix = null;
                for (ZipEntry e : Collections.list(zip.entries())) {
                    String n = e.getName().replace('\\', '/');
                    int i = n.indexOf("shaders/");
                    if (i >= 0 && (i == 0 || (n.lastIndexOf('/', i - 2) < 0 && n.charAt(i - 1) == '/'))) {
                        String p = n.substring(0, i + "shaders/".length());
                        if (prefix == null || p.length() < prefix.length())
                            prefix = p;
                    }
                }
                if (prefix == null)
                    throw new IOException("no shaders/ folder in " + path);
                for (ZipEntry e : Collections.list(zip.entries())) {
                    String n = e.getName().replace('\\', '/');
                    if (e.isDirectory() || !n.startsWith(prefix))
                        continue;
                    if (files.size() >= MAX_FILES)
                        throw new IOException("pack has more than " + MAX_FILES + " files");
                    if (e.getSize() > MAX_FILE)
                        continue;
                    try (InputStream in = zip.getInputStream(e)) {
                        byte[] b = in.readNBytes(MAX_FILE + 1);
                        if (b.length > MAX_FILE)
                            continue;
                        files.put(n.substring(prefix.length()), b);
                    }
                }
            }
            return new PackSource(fn, path.toString(), files);
        }
        throw new IOException("not a pack folder or zip: " + path);
    }

    /** For self-made test packs held in memory. */
    public static PackSource of(String name, Map<String, String> textFiles) {
        Map<String, byte[]> files = new TreeMap<>();
        textFiles.forEach((k, v) -> files.put(k, v.getBytes(StandardCharsets.UTF_8)));
        return new PackSource(name, "memory", files);
    }

    public String name() {
        return name;
    }

    public String origin() {
        return origin;
    }

    /** All file paths, sorted. */
    public List<String> paths() {
        return new ArrayList<>(files.keySet());
    }

    public List<String> caseNotes() {
        synchronized (caseNotes) {
            return new ArrayList<>(caseNotes);
        }
    }

    /**
     * Normalises a pack path: '\' to '/', "." and ".." resolved against {@code base} (a folder inside shaders/,
     * "" for the root). A leading '/' means "from shaders/". Returns null when the path leaves shaders/.
     */
    public static String normalise(String base, String path) {
        path = path.replace('\\', '/');
        List<String> parts = new ArrayList<>();
        if (!path.startsWith("/") && base != null && !base.isEmpty())
            for (String s : base.split("/"))
                if (!s.isEmpty())
                    parts.add(s);
        for (String s : path.split("/")) {
            if (s.isEmpty() || s.equals("."))
                continue;
            if (s.equals("..")) {
                if (parts.isEmpty())
                    return null;
                parts.remove(parts.size() - 1);
            } else {
                parts.add(s);
            }
        }
        return String.join("/", parts);
    }

    /** @return the exact stored path for {@code path} (case-insensitive fallback), or null */
    public String resolve(String path) {
        if (path == null)
            return null;
        if (files.containsKey(path))
            return path;
        String exact = lowerIndex.get(path.toLowerCase(Locale.ROOT));
        if (exact != null) {
            String note = "case mismatch: '" + path + "' read as '" + exact + "'";
            synchronized (caseNotes) {
                if (!caseNotes.contains(note))
                    caseNotes.add(note);
            }
        }
        return exact;
    }

    public boolean exists(String path) {
        return resolve(path) != null;
    }

    /** True when some file lives under {@code dir/}. */
    public boolean isDirectory(String dir) {
        String p = dir.endsWith("/") ? dir : dir + "/";
        String lp = p.toLowerCase(Locale.ROOT);
        for (String k : files.keySet())
            if (k.startsWith(p) || k.toLowerCase(Locale.ROOT).startsWith(lp))
                return true;
        return false;
    }

    public byte[] bytes(String path) {
        String r = resolve(path);
        return r == null ? null : files.get(r);
    }

    /** Text with CRLF / CR normalised to LF, BOM removed; null if missing. */
    public String text(String path) {
        byte[] b = bytes(path);
        if (b == null)
            return null;
        String s = new String(b, StandardCharsets.UTF_8);
        if (s.startsWith("﻿"))
            s = s.substring(1);
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }
}
