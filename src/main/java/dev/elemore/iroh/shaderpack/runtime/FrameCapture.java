package dev.elemore.iroh.shaderpack.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.elemore.iroh.shaderpack.Json;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The {@code .irohframe} capture format: a directory holding {@code frame.json} and one raw file per image.
 * <pre>
 * frame.json {
 *   "schema": "iroh.frame.v1", "pack": "Navidson", "folder": "world0", "width": W, "height": H,
 *   "startStage": "deferred", "frameSeconds": 0.016,
 *   "source": "synthetic" | "game", "note": "...",
 *   "uniforms": { "gbufferModelView": [16 doubles, column-major], "sunPosition": [3], ... },
 *   "images": { "colortex0": {"file": "colortex0.f32", "w": W, "h": H, "d": 1}, "depthtex0": {...}, ... }
 * }
 * &lt;name&gt;.f32: little-endian float32 RGBA, 4 per texel, rows in GL order (row 0 = bottom), slices after rows.
 * </pre>
 * Images are what the pack's earlier passes would have written (gbuffer colour targets as their programs encode them,
 * depth as GL window depth, shadow depth in the shadow projection). Bounded: a capture over {@link #MAX_BYTES} is
 * refused on read and on write; one frame per capture.
 */
public final class FrameCapture implements PackFrameInputs {

    public static final long MAX_BYTES = 512L << 20;

    public final String pack, folder, source, note;
    private final int width, height;
    private final String startStage;
    private final double frameSeconds;
    private final Map<String, double[]> uniforms;
    private final Map<String, float[]> images;
    private final Map<String, int[]> extents;

    public FrameCapture(String pack, String folder, String source, String note, int width, int height, String startStage,
                        double frameSeconds, Map<String, double[]> uniforms, Map<String, float[]> images, Map<String, int[]> extents) {
        this.pack = pack;
        this.folder = folder;
        this.source = source;
        this.note = note;
        this.width = width;
        this.height = height;
        this.startStage = startStage;
        this.frameSeconds = frameSeconds;
        this.uniforms = new TreeMap<>(uniforms);
        this.images = new TreeMap<>(images);
        this.extents = new TreeMap<>(extents);
    }

    public static FrameCapture of(PackFrameInputs in, String pack, String folder, String source, String note) {
        Map<String, float[]> im = new LinkedHashMap<>();
        Map<String, int[]> ex = new LinkedHashMap<>();
        for (String n : in.suppliedImages()) {
            im.put(n, in.image(n));
            ex.put(n, in.extent(n));
        }
        return new FrameCapture(pack, folder, source, note, in.width(), in.height(), in.startStage(), in.frameSeconds(),
                in.uniforms(), im, ex);
    }

    public void write(Path dir) throws IOException {
        long total = 0;
        for (float[] f : images.values())
            total += 4L * f.length;
        if (total > MAX_BYTES)
            throw new IOException("capture of " + (total >> 20) + " MiB exceeds " + (MAX_BYTES >> 20) + " MiB");
        Files.createDirectories(dir);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", "iroh.frame.v1");
        m.put("pack", pack);
        m.put("folder", folder);
        m.put("width", width);
        m.put("height", height);
        m.put("startStage", startStage);
        m.put("frameSeconds", frameSeconds);
        m.put("source", source);
        m.put("note", note);
        Map<String, Object> u = new LinkedHashMap<>();
        uniforms.forEach((k, v) -> {
            List<Double> l = new ArrayList<>();
            for (double x : v)
                l.add(x);
            u.put(k, l);
        });
        m.put("uniforms", u);
        Map<String, Object> ims = new LinkedHashMap<>();
        for (Map.Entry<String, float[]> e : images.entrySet()) {
            int[] x = extents.get(e.getKey());
            String file = e.getKey() + ".f32";
            ims.put(e.getKey(), Json.obj("file", file, "w", x[0], "h", x[1], "d", x[2]));
            ByteBuffer b = ByteBuffer.allocate(e.getValue().length * 4).order(ByteOrder.LITTLE_ENDIAN);
            b.asFloatBuffer().put(e.getValue());
            try (FileChannel ch = FileChannel.open(dir.resolve(file), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.write(b);
            }
        }
        m.put("images", ims);
        Files.writeString(dir.resolve("frame.json"), Json.write(m), StandardCharsets.UTF_8);
    }

    public static FrameCapture read(Path dir) throws IOException {
        JsonObject j = JsonParser.parseString(Files.readString(dir.resolve("frame.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        if (!"iroh.frame.v1".equals(j.get("schema").getAsString()))
            throw new IOException("not an iroh.frame.v1 capture");
        Map<String, double[]> u = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("uniforms").entrySet()) {
            JsonArray a = e.getValue().getAsJsonArray();
            double[] v = new double[a.size()];
            for (int i = 0; i < v.length; i++)
                v[i] = a.get(i).getAsDouble();
            u.put(e.getKey(), v);
        }
        Map<String, float[]> im = new LinkedHashMap<>();
        Map<String, int[]> ex = new LinkedHashMap<>();
        long total = 0;
        for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("images").entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            String file = o.get("file").getAsString();
            if (file.contains("/") || file.contains(".."))
                throw new IOException("bad image file name " + file);
            int w = o.get("w").getAsInt(), h = o.get("h").getAsInt(), d = o.get("d").getAsInt();
            long bytes = 16L * w * h * d;
            total += bytes;
            if (total > MAX_BYTES)
                throw new IOException("capture exceeds " + (MAX_BYTES >> 20) + " MiB");
            byte[] raw = Files.readAllBytes(dir.resolve(file));
            if (raw.length != bytes)
                throw new IOException(file + ": " + raw.length + " bytes, expected " + bytes);
            float[] f = new float[(int) (bytes / 4)];
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(f);
            im.put(e.getKey(), f);
            ex.put(e.getKey(), new int[]{w, h, d});
        }
        return new FrameCapture(j.get("pack").getAsString(), j.get("folder").getAsString(), j.get("source").getAsString(),
                j.get("note").getAsString(), j.get("width").getAsInt(), j.get("height").getAsInt(),
                j.get("startStage").getAsString(), j.get("frameSeconds").getAsDouble(), u, im, ex);
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public Map<String, double[]> uniforms() {
        return uniforms;
    }

    @Override
    public Set<String> suppliedImages() {
        return new LinkedHashSet<>(images.keySet());
    }

    @Override
    public float[] image(String name) {
        return images.get(name);
    }

    @Override
    public int[] extent(String name) {
        return extents.get(name);
    }

    @Override
    public String startStage() {
        return startStage;
    }

    @Override
    public double frameSeconds() {
        return frameSeconds;
    }
}
