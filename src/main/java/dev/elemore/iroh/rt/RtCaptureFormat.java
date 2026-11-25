package dev.elemore.iroh.rt;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RT-1: the on-disk world capture (Minecraft-free: the game writes it, the offline replay and the synthetic round trip use the
 * same code). A capture is a directory of files that ALL end in {@value #EXT} (one .gitignore line and one reuse_gate check
 * keep them out of git; they hold Mojang textures, so they live in ~/.cache/iroh-captures only). Little endian, version 1:
 * <ul>
 * <li>{@code meta.ircap}: UTF-8 {@code key=value} lines (what, where, when; free text, read back as a map).
 * <li>{@code frame.ircap}: "IRCF", int version, {@link RtFrameData#BYTES} bytes of frame data (camera, jitter, sun), view
 *     matrix and projection matrix (2 x 16 floats, column-major).
 * <li>{@code sections.ircap}: "IRCS", int version, int vertexSize (16), int count; then per section: int sx, sy, sz,
 *     int quads[3] (opaque, cutout, translucent), then the three buckets' vertices (quads x 4 x vertexSize bytes each) as
 *     they sat in the raster arena (compressed terrain vertex: position SNORM x3 first, then colour / uv / light).
 * <li>{@code atlas.ircap}: "IRCA", int version, int width, int height, int levels; then per level int w, int h and
 *     w x h x 4 bytes RGBA8 (all mips).
 * <li>{@code lights.ircap} (card RT-3a) and {@code entities.ircap} (card RT-5): reserved names, absent in version 1.
 * </ul>
 * The total of all files is capped at {@value #CAP_MIB} MiB; the writer refuses further sections past the cap and the
 * caller records {@code truncated=true}.
 */
public final class RtCaptureFormat {
    private RtCaptureFormat() {}

    public static final String EXT = ".ircap";
    public static final int VERSION = 1, CAP_MIB = 512;
    public static final long CAP_BYTES = (long) CAP_MIB << 20;
    private static final int MAGIC_FRAME = 0x46435249, MAGIC_SECTIONS = 0x53435249, MAGIC_ATLAS = 0x41435249;   // "IRCF", "IRCS", "IRCA"

    /** One captured section: position and the three buckets' vertex bytes (null = empty bucket). */
    public record Section(int sx, int sy, int sz, int[] quads, ByteBuffer[] buckets) {
        public int totalQuads() { return quads[0] + quads[1] + quads[2]; }
    }

    /** The block atlas: RGBA8 levels, level 0 first. */
    public record Atlas(int width, int height, List<ByteBuffer> levels) {}

    /** A capture read back: everything in memory (the replay runs on lavapipe at 320x180, so this is fine up to the cap). */
    public record Capture(Map<String, String> meta, ByteBuffer frame, float[] view, float[] proj, int vertexSize, List<Section> sections, Atlas atlas) {
        public long quads() {
            long q = 0;
            for (Section s : sections) q += s.totalQuads();
            return q;
        }
    }

    /** Streams a capture to {@code dir}; call {@link #close} to patch the section count and write meta last. */
    public static final class Writer implements Closeable {
        private final Path dir;
        private final RandomAccessFile secFile;
        private final FileChannel sec;
        private int count;
        private long bytes, quads;
        private final int vertexSize;
        private boolean closed;
        private final Map<String, String> meta = new LinkedHashMap<>();

        public Writer(Path dir, int vertexSize) throws IOException {
            this.dir = dir;
            this.vertexSize = vertexSize;
            Files.createDirectories(dir);
            secFile = new RandomAccessFile(dir.resolve("sections" + EXT).toFile(), "rw");
            secFile.setLength(0);
            sec = secFile.getChannel();
            ByteBuffer h = le(16).putInt(MAGIC_SECTIONS).putInt(VERSION).putInt(vertexSize).putInt(0).flip();
            sec.write(h);
            bytes = 16;
        }

        public long bytesWritten() { return bytes; }

        public int sections() { return count; }

        public void meta(String k, Object v) { meta.put(k, String.valueOf(v)); }

        /** Bytes a section would add. */
        public long sizeOf(int[] quads) {
            return 24L + (long) (quads[0] + quads[1] + quads[2]) * 4 * vertexSize;
        }

        /** Appends a section unless it would pass the cap (then false and nothing is written). */
        public boolean section(Section s) throws IOException {
            long add = sizeOf(s.quads());
            if (bytes + add > CAP_BYTES) return false;
            ByteBuffer h = le(24).putInt(s.sx()).putInt(s.sy()).putInt(s.sz()).putInt(s.quads()[0]).putInt(s.quads()[1]).putInt(s.quads()[2]).flip();
            sec.write(h);
            for (int b = 0; b < 3; b++) {
                long want = (long) s.quads()[b] * 4 * vertexSize;
                if (want == 0) continue;
                ByteBuffer d = s.buckets()[b].duplicate();
                if (d.remaining() != want) throw new IOException("bucket " + b + " holds " + d.remaining() + " B, expected " + want);
                while (d.hasRemaining()) sec.write(d);
            }
            bytes += add;
            quads += s.totalQuads();
            count++;
            return true;
        }

        public void frame(RtFrameData fd, float[] view, float[] proj) throws IOException {
            ByteBuffer raw = le(RtFrameData.BYTES);
            fd.write(raw);
            raw.flip();
            frame(raw, view, proj);
        }

        /** Same, from the already written {@link RtFrameData#BYTES} bytes (the replay re-emits a read capture unchanged). */
        public void frame(ByteBuffer fd, float[] view, float[] proj) throws IOException {
            ByteBuffer b = le(8 + RtFrameData.BYTES + 128).putInt(MAGIC_FRAME).putInt(VERSION);
            b.put(fd.duplicate());
            for (float f : view) b.putFloat(f);
            for (float f : proj) b.putFloat(f);
            Files.write(dir.resolve("frame" + EXT), java.util.Arrays.copyOf(b.array(), b.position()));
            bytes += b.position();
        }

        public void atlas(Atlas a) throws IOException {
            long total = 20;
            for (ByteBuffer l : a.levels()) total += 8 + l.remaining();
            if (bytes + total > CAP_BYTES) throw new IOException("atlas does not fit the " + CAP_MIB + " MiB cap");
            try (FileChannel c = FileChannel.open(dir.resolve("atlas" + EXT), java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                c.write(le(20).putInt(MAGIC_ATLAS).putInt(VERSION).putInt(a.width()).putInt(a.height()).putInt(a.levels().size()).flip());
                int w = a.width(), h = a.height();
                for (ByteBuffer l : a.levels()) {
                    c.write(le(8).putInt(w).putInt(h).flip());
                    ByteBuffer d = l.duplicate();
                    while (d.hasRemaining()) c.write(d);
                    w = Math.max(1, w >> 1);
                    h = Math.max(1, h >> 1);
                }
            }
            bytes += total;
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                sec.write(le(4).putInt(count).flip(), 12);   // patch the count
            } finally {
                secFile.close();
            }
            meta.put("format", "IRCAP");
            meta.put("version", String.valueOf(VERSION));
            meta.put("vertexSize", String.valueOf(vertexSize));
            meta.put("sections", String.valueOf(count));
            meta.put("quads", String.valueOf(quads));
            meta.put("bytes", String.valueOf(bytes));
            meta.put("capMiB", String.valueOf(CAP_MIB));
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : meta.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue().replace('\n', ' ')).append('\n');
            Files.writeString(dir.resolve("meta" + EXT), sb.toString(), StandardCharsets.UTF_8);
        }
    }

    public static Capture read(Path dir) throws IOException {
        Map<String, String> meta = new LinkedHashMap<>();
        for (String line : Files.readAllLines(dir.resolve("meta" + EXT), StandardCharsets.UTF_8)) {
            int i = line.indexOf('=');
            if (i > 0) meta.put(line.substring(0, i), line.substring(i + 1));
        }
        if (!"IRCAP".equals(meta.get("format")) || !String.valueOf(VERSION).equals(meta.get("version")))
            throw new IOException("not a version-" + VERSION + " IRCAP capture: " + dir);
        ByteBuffer f = le(Files.readAllBytes(dir.resolve("frame" + EXT)));
        if (f.getInt() != MAGIC_FRAME || f.getInt() != VERSION) throw new IOException("bad frame.ircap");
        byte[] fd = new byte[RtFrameData.BYTES];
        f.get(fd);
        float[] view = new float[16], proj = new float[16];
        for (int i = 0; i < 16; i++) view[i] = f.getFloat();
        for (int i = 0; i < 16; i++) proj[i] = f.getFloat();
        List<Section> secs = new ArrayList<>();
        int vs;
        try (FileChannel c = FileChannel.open(dir.resolve("sections" + EXT))) {
            ByteBuffer all = c.map(FileChannel.MapMode.READ_ONLY, 0, c.size()).order(ByteOrder.LITTLE_ENDIAN);
            if (all.getInt() != MAGIC_SECTIONS || all.getInt() != VERSION) throw new IOException("bad sections.ircap");
            vs = all.getInt();
            int n = all.getInt();
            for (int i = 0; i < n; i++) {
                int sx = all.getInt(), sy = all.getInt(), sz = all.getInt();
                int[] q = {all.getInt(), all.getInt(), all.getInt()};
                ByteBuffer[] b = new ByteBuffer[3];
                for (int k = 0; k < 3; k++) {
                    int len = q[k] * 4 * vs;
                    if (len == 0) continue;
                    ByteBuffer slice = all.slice(all.position(), len).order(ByteOrder.LITTLE_ENDIAN);
                    ByteBuffer copy = ByteBuffer.allocateDirect(len).order(ByteOrder.LITTLE_ENDIAN);
                    copy.put(slice).flip();
                    b[k] = copy;
                    all.position(all.position() + len);
                }
                secs.add(new Section(sx, sy, sz, q, b));
            }
        }
        Atlas atlas = null;
        Path ap = dir.resolve("atlas" + EXT);
        if (Files.exists(ap)) {
            ByteBuffer a = le(Files.readAllBytes(ap));
            if (a.getInt() != MAGIC_ATLAS || a.getInt() != VERSION) throw new IOException("bad atlas.ircap");
            int w = a.getInt(), h = a.getInt(), n = a.getInt();
            List<ByteBuffer> lv = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int lw = a.getInt(), lh = a.getInt(), len = lw * lh * 4;
                ByteBuffer d = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
                d.put(a.slice(a.position(), len)).flip();
                a.position(a.position() + len);
                lv.add(d);
            }
            atlas = new Atlas(w, h, lv);
        }
        return new Capture(meta, ByteBuffer.wrap(fd).order(ByteOrder.LITTLE_ENDIAN), view, proj, vs, secs, atlas);
    }

    private static ByteBuffer le(int n) {
        return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static ByteBuffer le(byte[] b) {
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    }
}
