package dev.elemore.iroh.gpu;

import dev.elemore.iroh.api.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.*;

import static org.lwjgl.vulkan.VK12.*;

/**
 * Iroh api 4 core (card V-M1): validation, owners, budgets, hazards and the indirect-count fallback, on top of a
 * {@link GpuDevice}. Everything a mod creates is a resource of its owner: accounted against the owner's share of the
 * device cap ({@code iroh.gpu.ownerShare}, default 0.40, a provisional call), refused gracefully (null + reason) when
 * over, and freed by {@link #freeOwner} / {@link #freeAll} (world unload, a failing hook, shutdown).
 * <p>
 * Hazards: each buffer remembers its last writes and the reads since; before a recorded use, one global barrier from
 * those stages to the use's stage is emitted when needed. Barriers cannot go inside a render pass, so draws read only
 * buffers declared at {@link #beginRendering} (barriered there). Render thread only; not thread-safe by design.
 */
public final class GpuCore {
    public static final int STORAGE = 1, INDIRECT = 2, INDEX = 4, VERTEX = 8;
    public static final int MAX_BUFFER_BYTES = 1 << 30; // 1 GiB per buffer (v1 chunked heaps use 256 MiB)
    public static final int MAX_GROUPS = 65535;
    public static final double OWNER_SHARE = Double.parseDouble(System.getProperty("iroh.gpu.ownerShare", "0.40"));

    private static volatile GpuCore instance;
    /**
     * INT-2 (RQ-INT1-a): the game side registers how to create the device here instead of creating it at renderer init;
     * the first {@link #get} (a mod's first api-4 call) runs it once. Nobody calls api 4 = no device objects, no
     * per-frame work. Cleared after its one run (success or failure).
     */
    private static volatile Runnable lazyInstaller;
    private static volatile String unavailableReason = "no device yet, or capability off";

    public static void setLazyInstaller(Runnable r) {
        lazyInstaller = r;
    }

    /** why {@link #get} throws (the lazy install failed, or no device) */
    public static void setUnavailableReason(String why) {
        unavailableReason = why;
    }

    public static GpuCore get() {
        GpuCore c = instance;
        if (c == null && lazyInstaller != null) {
            synchronized (GpuCore.class) {
                Runnable r = lazyInstaller;
                lazyInstaller = null;
                if (instance == null && r != null) r.run();
            }
            c = instance;
        }
        if (c == null) throw new UnsupportedOperationException("Iroh GPU api 4 is not available (" + unavailableReason + ")");
        return c;
    }

    public static GpuCore orNull() {
        return instance;
    }

    public static synchronized GpuCore install(GpuDevice device) {
        instance = new GpuCore(device);
        return instance;
    }

    public static synchronized void uninstall() {
        GpuCore c = instance;
        if (c != null) c.freeAll();
        instance = null;
    }

    public final GpuDevice device;
    private final double share;
    private final Map<String, Owner> owners = new LinkedHashMap<>();
    private String lastRefusal;
    private int screenW = 1, screenH = 1;
    /**
     * INT-2 phase-size rule (combination of api 4 with render scale, knowledge/api.md "api 4 and render scale"): a SCALED
     * target follows the size of the frame phase it is drawn in. While Iroh's world phase renders at render size
     * ({@link #setWorldPhase}), beginRendering / mainDepthCopy on a scaled target fit it to scale x render size; outside
     * the world phase to scale x window size. A refit recreates the image (contents lost, as on a window resize).
     * Fixed-size targets never change. Use one scaled target per phase: a target used in both phases is refit twice per
     * frame (counted in {@link #refits()}).
     */
    private boolean worldPhase;
    private int worldW = 1, worldH = 1;
    private long refits;

    public GpuCore(GpuDevice device) {
        this(device, OWNER_SHARE);
    }

    public GpuCore(GpuDevice device, double share) {
        this.device = device;
        this.share = share;
    }

    // ================================================================================================ owners

    static final class Owner {
        final String id;
        long bytes;
        final LinkedHashSet<Res> live = new LinkedHashSet<>();

        Owner(String id) {
            this.id = id;
        }
    }

    private Owner owner(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("owner (mod id) required");
        return owners.computeIfAbsent(id, Owner::new);
    }

    public long ownerShareBytes() {
        return (long) (device.capBytes() * share);
    }

    public long ownerBytes(String id) {
        Owner o = owners.get(id);
        return o == null ? 0 : o.bytes;
    }

    public int ownerResources(String id) {
        Owner o = owners.get(id);
        return o == null ? 0 : o.live.size();
    }

    public String lastRefusal() {
        return lastRefusal;
    }

    /** Frees everything {@code id} still holds. Returns how many resources. */
    public int freeOwner(String id) {
        Owner o = owners.get(id);
        if (o == null) return 0;
        int n = 0;
        for (Res r : new ArrayList<>(o.live)) {
            r.close();
            n++;
        }
        return n;
    }

    /** World unload / shutdown: every owner. */
    public int freeAll() {
        int n = 0;
        for (String id : new ArrayList<>(owners.keySet())) n += freeOwner(id);
        return n;
    }

    /** One line for the memStats report. */
    public String summary() {
        StringBuilder b = new StringBuilder("api4 gpu (" + device.name() + ", share " + (ownerShareBytes() >> 20) + " MiB/owner):");
        for (Owner o : owners.values())
            if (!o.live.isEmpty()) b.append(' ').append(o.id).append(' ').append(o.live.size()).append(" objects ").append(o.bytes >> 20).append(" MiB");
        return b.toString();
    }

    // ================================================================================================ resources

    abstract class Res implements GpuResource {
        final Owner owner;
        final String name;
        long bytes; // a resized target changes it
        boolean alive = true;

        Res(Owner owner, String name, long bytes) {
            this.owner = owner;
            this.name = name;
            this.bytes = bytes;
            owner.bytes += bytes;
            owner.live.add(this);
        }

        GpuCore core() { return GpuCore.this; }

        @Override public String owner() { return owner.id; }
        @Override public String name() { return name; }
        @Override public boolean alive() { return alive; }

        @Override
        public void close() {
            if (!alive) return;
            alive = false;
            owner.bytes -= bytes;
            owner.live.remove(this);
            release();
        }

        abstract void release();

        void checkAlive() {
            if (!alive) throw new IllegalStateException(name + " (" + owner.id + ") is closed");
        }
    }

    final class Buf extends Res implements GpuBuffer {
        final Object dev;
        final int usage;
        int writeStages, writeAccess, readStages;

        Buf(Owner o, String name, long bytes, int usage, Object dev) {
            super(o, name, bytes);
            this.usage = usage;
            this.dev = dev;
        }

        @Override public long bytes() { return bytes; }
        @Override public int usage() { return usage; }
        @Override void release() { device.destroyBuffer(dev); }
    }

    final class Compute extends Res implements ComputePipelineHandle {
        final Object dev;
        final int storage, push;

        Compute(Owner o, String name, Object dev, int storage, int push) {
            super(o, name, 0);
            this.dev = dev;
            this.storage = storage;
            this.push = push;
        }

        @Override public int storageBuffers() { return storage; }
        @Override public int pushBytes() { return push; }
        @Override void release() { device.destroyPipeline(dev); }
    }

    final class Indirect extends Res implements IndirectPipelineHandle {
        final Object dev;
        final int storage, textures, push;
        final IrohTargets.Format color, depth;

        Indirect(Owner o, String name, Object dev, int storage, int textures, int push, IrohTargets.Format color, IrohTargets.Format depth) {
            super(o, name, 0);
            this.dev = dev;
            this.storage = storage;
            this.textures = textures;
            this.push = push;
            this.color = color;
            this.depth = depth;
        }

        @Override public int storageBuffers() { return storage; }
        @Override public int sampledTextures() { return textures; }
        @Override public int pushBytes() { return push; }
        @Override public IrohTargets.Format colorFormat() { return color; }
        @Override public IrohTargets.Format depthFormat() { return depth; }
        @Override void release() { device.destroyPipeline(dev); }
    }

    final class Target extends Res implements RenderTargetHandle {
        Object dev;
        final IrohTargets.Format format;
        int width, height;
        final float scale;
        /** INT-2: last fitted to the world phase (render size); window resizes then leave it to the next fit */
        boolean worldSized;

        Target(Owner o, String name, IrohTargets.Format format, int w, int h, float scale, Object dev) {
            super(o, name, (long) w * h * format.bytesPerTexel);
            this.format = format;
            this.width = w;
            this.height = h;
            this.scale = scale;
            this.dev = dev;
            this.worldSized = scale > 0 && worldPhase;
        }

        @Override public IrohTargets.Format format() { return format; }
        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public float scale() { return scale; }
        @Override void release() { device.destroyImage(dev); }
    }

    private Buf buf(GpuBuffer b) {
        if (!(b instanceof Buf x) || x.core() != this) throw new IllegalArgumentException("not an Iroh api 4 buffer of this device");
        x.checkAlive();
        return x;
    }

    // ================================================================================================ buffers

    /** Null (with {@link #lastRefusal}) when the owner's share would be exceeded. */
    public GpuBuffer createBuffer(String ownerId, String name, long bytes, int usage) {
        Owner o = owner(ownerId);
        if (bytes <= 0 || bytes > MAX_BUFFER_BYTES || (bytes & 3) != 0)
            throw new IllegalArgumentException("buffer size " + bytes + ": must be > 0, a multiple of 4, at most " + MAX_BUFFER_BYTES);
        if (usage == 0 || (usage & ~(STORAGE | INDIRECT | INDEX | VERTEX)) != 0)
            throw new IllegalArgumentException("buffer usage " + usage + ": STORAGE | INDIRECT | INDEX | VERTEX");
        if (o.bytes + bytes > ownerShareBytes()) {
            lastRefusal = ownerId + ": " + name + " (" + (bytes >> 20) + " MiB) would exceed its share " + (ownerShareBytes() >> 20) + " MiB (holds "
                    + (o.bytes >> 20) + " MiB)";
            return null;
        }
        Object dev = device.createBuffer(bytes, usage);
        return new Buf(o, name, bytes, usage, dev);
    }

    public void upload(GpuBuffer target, long offset, ByteBuffer src) {
        Buf b = buf(target);
        int len = src == null ? -1 : src.remaining();
        if (len <= 0 || (len & 3) != 0 || offset < 0 || (offset & 3) != 0 || offset + len > b.bytes)
            throw new IllegalArgumentException("upload " + len + " bytes at " + offset + " into " + b.name + " (" + b.bytes + " bytes): sizes and offsets in 4-byte units, inside the buffer");
        if (!src.isDirect()) {
            ByteBuffer d = MemoryUtil.memAlloc(len);
            d.put(src.duplicate()).flip();
            use(b, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, true);
            flushBarrier();
            device.upload(b.dev, offset, d);
            MemoryUtil.memFree(d);
        } else {
            use(b, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, true);
            flushBarrier();
            device.upload(b.dev, offset, src);
        }
    }

    // ================================================================================================ hazards

    private int pendSrcStages, pendSrcAccess, pendDstStages, pendDstAccess;

    /** Notes one use of {@code b}; the barrier it needs is collected until {@link #flushBarrier}. */
    private void use(Buf b, int stage, int access, boolean write) {
        if (b.writeStages != 0) { // RAW / WAW
            pendSrcStages |= b.writeStages;
            pendSrcAccess |= b.writeAccess;
            pendDstStages |= stage;
            pendDstAccess |= access;
        }
        if (write && b.readStages != 0) { // WAR: execution dependency only
            pendSrcStages |= b.readStages;
            pendDstStages |= stage;
        }
        if (write) {
            b.writeStages = stage;
            b.writeAccess = access & (VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);
            b.readStages = 0;
        } else {
            b.readStages |= stage;
        }
    }

    private void flushBarrier() {
        if (pendSrcStages != 0) device.barrier(pendSrcStages, pendSrcAccess, pendDstStages, pendDstAccess);
        pendSrcStages = pendSrcAccess = pendDstStages = pendDstAccess = 0;
    }

    /** After a barrier the write is visible: clear the pending write for the buffers just used as readers. */
    private void settleReads(Buf[] bufs) {
        for (Buf b : bufs) if (b != null && b.writeStages != 0 && b.readStages != 0) b.writeStages = 0;
    }

    // ================================================================================================ compute

    public ComputePipelineHandle computePipeline(String ownerId, String name, String glsl450, int storageBuffers, int pushBytes) {
        Owner o = owner(ownerId);
        if (storageBuffers < 0 || storageBuffers > 16) throw new IllegalArgumentException("storage buffers 0..16");
        if (pushBytes < 0 || pushBytes > 128 || (pushBytes & 3) != 0) throw new IllegalArgumentException("push constants 0..128 bytes, multiple of 4");
        ByteBuffer spv = Glsl.compile(ownerId + ":" + name, glsl450, Glsl.COMPUTE);
        try {
            return new Compute(o, name, device.createComputePipeline(name, spv, storageBuffers, pushBytes), storageBuffers, pushBytes);
        } finally {
            MemoryUtil.memFree(spv);
        }
    }

    private Buf[] bindings(int expected, IrohCompute.Binding[] b, String what) {
        int n = b == null ? 0 : b.length;
        if (n != expected) throw new IllegalArgumentException(what + ": " + n + " bindings for " + expected + " storage buffers");
        Buf[] out = new Buf[n];
        for (int i = 0; i < n; i++) {
            if (b[i] == null) throw new IllegalArgumentException(what + ": binding " + i + " is null");
            out[i] = buf(b[i].buffer());
        }
        return out;
    }

    private void checkPush(int expected, ByteBuffer push) {
        int n = push == null ? 0 : push.remaining();
        if (n != expected) throw new IllegalArgumentException("push constants: " + n + " bytes for a pipeline declaring " + expected);
    }

    private void checkOutside(String what) {
        if (!device.canRecordOutsideMainPass())
            throw new IllegalStateException(what + " records outside a render pass: call it from FrameStage.BEFORE_MAIN_PASS (or outside beginRendering)");
    }

    public void dispatch(ComputePipelineHandle pipeline, IrohCompute.Binding[] b, ByteBuffer push, int x, int y, int z) {
        Compute p = compute(pipeline);
        Buf[] bufs = bindings(p.storage, b, p.name);
        checkPush(p.push, push);
        if (x < 1 || y < 1 || z < 1 || x > MAX_GROUPS || y > MAX_GROUPS || z > MAX_GROUPS)
            throw new IllegalArgumentException("dispatch " + x + "x" + y + "x" + z + ": each 1.." + MAX_GROUPS);
        checkOutside("dispatch");
        declare(b, bufs, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        flushBarrier();
        device.dispatch(p.dev, devs(bufs), push, x, y, z);
    }

    public void dispatchIndirect(ComputePipelineHandle pipeline, IrohCompute.Binding[] b, ByteBuffer push, GpuBuffer args, long offset) {
        Compute p = compute(pipeline);
        Buf[] bufs = bindings(p.storage, b, p.name);
        checkPush(p.push, push);
        Buf a = buf(args);
        if ((a.usage & INDIRECT) == 0) throw new IllegalArgumentException(a.name + " lacks INDIRECT usage");
        if (offset < 0 || (offset & 3) != 0 || offset + 12 > a.bytes) throw new IllegalArgumentException("indirect offset " + offset + " outside " + a.name);
        checkOutside("dispatchIndirect");
        use(a, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT, VK_ACCESS_INDIRECT_COMMAND_READ_BIT, false);
        declare(b, bufs, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        flushBarrier();
        device.dispatchIndirect(p.dev, devs(bufs), push, a.dev, offset);
    }

    private void declare(IrohCompute.Binding[] b, Buf[] bufs, int stage) {
        for (int i = 0; i < bufs.length; i++) {
            IrohCompute.Access acc = b[i].access();
            boolean write = acc != IrohCompute.Access.READ;
            int access = (acc != IrohCompute.Access.WRITE ? VK_ACCESS_SHADER_READ_BIT : 0) | (write ? VK_ACCESS_SHADER_WRITE_BIT : 0);
            use(bufs[i], stage, access, write);
        }
    }

    private Compute compute(ComputePipelineHandle h) {
        if (!(h instanceof Compute c) || c.core() != this) throw new IllegalArgumentException("not an api 4 compute pipeline of this device");
        c.checkAlive();
        return c;
    }

    private static Object[] devs(Buf[] bufs) {
        Object[] o = new Object[bufs.length];
        for (int i = 0; i < bufs.length; i++) o[i] = bufs[i].dev;
        return o;
    }

    // ================================================================================================ targets

    public RenderTargetHandle createTarget(String ownerId, String name, IrohTargets.Format format, int width, int height, float scale) {
        Owner o = owner(ownerId);
        if (format == null) throw new IllegalArgumentException("format required");
        if (scale > 0) {
            if (scale > 2) throw new IllegalArgumentException("scale 0..2");
            width = Math.max(1, Math.round((worldPhase ? worldW : screenW) * scale)); // INT-2: size of the phase it is made in
            height = Math.max(1, Math.round((worldPhase ? worldH : screenH) * scale));
        }
        if (width < 1 || height < 1 || width > 16384 || height > 16384) throw new IllegalArgumentException("target size " + width + "x" + height);
        long bytes = (long) width * height * format.bytesPerTexel;
        if (o.bytes + bytes > ownerShareBytes()) {
            lastRefusal = ownerId + ": target " + name + " would exceed its share";
            return null;
        }
        return new Target(o, name, format, width, height, scale, device.createImage(name, width, height, format.vkFormat, format.depth));
    }

    /** Window resize: screen-scaled targets are recreated (old images freed after the GPU is done). Contents are lost. */
    public void resize(int w, int h) {
        screenW = Math.max(1, w);
        screenH = Math.max(1, h);
        for (Owner o : owners.values())
            for (Res r : o.live)
                if (r instanceof Target t && t.scale > 0 && !t.worldSized)
                    refit(t, Math.max(1, Math.round(screenW * t.scale)), Math.max(1, Math.round(screenH * t.scale)));
    }

    /** INT-2: Iroh's world phase opens (render size w x h) or closes. Render thread. */
    public void setWorldPhase(boolean active, int w, int h) {
        worldPhase = active;
        if (active) {
            worldW = Math.max(1, w);
            worldH = Math.max(1, h);
        }
    }

    public boolean worldPhase() {
        return worldPhase;
    }

    /** how many times a scaled target was recreated to follow its phase (diagnostics, tests) */
    public long refits() {
        return refits;
    }

    /** INT-2 phase-size rule: a scaled target takes scale x the CURRENT phase size before it is drawn into. */
    public void fitPhase(RenderTargetHandle h) {
        Target t = target(h);
        if (t.scale <= 0) return;
        int pw = worldPhase ? worldW : screenW, ph = worldPhase ? worldH : screenH;
        t.worldSized = worldPhase;
        refit(t, Math.max(1, Math.round(pw * t.scale)), Math.max(1, Math.round(ph * t.scale)));
    }

    private void refit(Target t, int nw, int nh) {
        if (nw == t.width && nh == t.height) return;
        device.destroyImage(t.dev);
        t.owner.bytes -= t.bytes;
        t.dev = device.createImage(t.name, nw, nh, t.format.vkFormat, t.format.depth);
        t.width = nw;
        t.height = nh;
        t.bytes = (long) nw * nh * t.format.bytesPerTexel;
        t.owner.bytes += t.bytes;
        refits++;
    }

    Target target(RenderTargetHandle h) {
        if (!(h instanceof Target t) || t.core() != this) throw new IllegalArgumentException("not an api 4 render target of this device");
        t.checkAlive();
        return t;
    }

    /** The device's object behind a buffer (offline readback in tests: HeadlessGpuDevice.readBuffer). */
    public Object nativeBuffer(GpuBuffer b) {
        return buf(b).dev;
    }

    /** The device's object behind a target (in-game helpers such as MainDepth). */
    public Object nativeImage(RenderTargetHandle t) {
        return target(t).dev;
    }

    public ByteBuffer readback(RenderTargetHandle t) {
        return device.readback(target(t).dev);
    }

    // ================================================================================================ indirect draws

    public IndirectPipelineHandle indirectPipeline(String ownerId, String name, String vertexGlsl, String fragmentGlsl, int storageBuffers,
                                                   int sampledTextures, int pushBytes, IrohTargets.Format color, IrohTargets.Format depth,
                                                   int depthCompare, boolean depthWrite, boolean blend) {
        Owner o = owner(ownerId);
        if (color == null && depth == null) throw new IllegalArgumentException("a colour or a depth format");
        if (color != null && color.depth) throw new IllegalArgumentException("colour format " + color + " is a depth format");
        if (depth != null && !depth.depth) throw new IllegalArgumentException("depth format " + depth + " is not a depth format");
        if (storageBuffers < 0 || storageBuffers > 16 || sampledTextures < 0 || sampledTextures > 8) throw new IllegalArgumentException("bindings");
        if (pushBytes < 0 || pushBytes > 128 || (pushBytes & 3) != 0) throw new IllegalArgumentException("push constants 0..128, multiple of 4");
        ByteBuffer vs = Glsl.compile(ownerId + ":" + name + ".vert", vertexGlsl, Glsl.VERTEX);
        ByteBuffer fs = Glsl.compile(ownerId + ":" + name + ".frag", fragmentGlsl, Glsl.FRAGMENT);
        try {
            Object dev = device.createDrawPipeline(name, vs, fs, storageBuffers, sampledTextures, pushBytes,
                    color == null ? 0 : color.vkFormat, depth == null ? 0 : depth.vkFormat, depthCompare, depthWrite, blend);
            return new Indirect(o, name, dev, storageBuffers, sampledTextures, pushBytes, color, depth);
        } finally {
            MemoryUtil.memFree(vs);
            MemoryUtil.memFree(fs);
        }
    }

    private Target passColor, passDepth;
    private final Set<Buf> passReads = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> passPrepared = new HashSet<>();

    /** What the next pass's indirect draws need prepared (a fallback without drawIndirectCount, and the barriers). */
    public void prepareIndirectCount(GpuBuffer commands, long commandOffset, GpuBuffer count, long countOffset, int maxDraws, int stride) {
        Buf c = buf(commands), n = buf(count);
        checkIndirectRange(c, commandOffset, n, countOffset, maxDraws, stride);
        checkOutside("prepareIndirectCount");
        if (!device.supportsDrawIndirectCount()) {
            // fallback: Iroh's own compute sets instanceCount = 0 for commands at or after the count, then the pass draws
            // maxDraws commands with vkCmdDrawIndexedIndirect (same image, no CPU readback)
            use(c, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, true);
            use(n, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT, false);
            flushBarrier();
            zeroTail(c, commandOffset, n, countOffset, maxDraws, stride);
        }
        passPrepared.add(key(c, commandOffset, n, countOffset, maxDraws, stride));
    }

    private Object zeroTailPipe;

    private void zeroTail(Buf c, long off, Buf n, long countOff, int maxDraws, int stride) {
        if (zeroTailPipe == null) {
            String src = """
                    #version 450
                    layout(local_size_x = 64) in;
                    layout(std430, set = 0, binding = 0) buffer Cmds { uint cmds[]; };
                    layout(std430, set = 0, binding = 1) readonly buffer Count { uint counts[]; };
                    layout(push_constant) uniform P { uint first; uint countWord; uint maxDraws; uint strideWords; } p;
                    void main() {
                        uint i = gl_GlobalInvocationID.x;
                        if (i >= p.maxDraws) return;
                        if (i >= counts[p.countWord]) cmds[p.first + i * p.strideWords + 1u] = 0u; // instanceCount
                    }
                    """;
            ByteBuffer spv = Glsl.compile("iroh:zeroTail", src, Glsl.COMPUTE);
            try {
                zeroTailPipe = device.createComputePipeline("iroh zero tail", spv, 2, 16);
            } finally {
                MemoryUtil.memFree(spv);
            }
        }
        ByteBuffer push = MemoryUtil.memAlloc(16);
        try {
            push.putInt((int) (off >> 2)).putInt((int) (countOff >> 2)).putInt(maxDraws).putInt(stride >> 2).flip();
            device.dispatch(zeroTailPipe, new Object[]{c.dev, n.dev}, push, (maxDraws + 63) / 64, 1, 1);
        } finally {
            MemoryUtil.memFree(push);
        }
        c.writeStages = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        c.writeAccess = VK_ACCESS_SHADER_WRITE_BIT;
        c.readStages = 0;
    }

    private static String key(Buf c, long off, Buf n, long countOff, int max, int stride) {
        return System.identityHashCode(c) + ":" + off + ":" + System.identityHashCode(n) + ":" + countOff + ":" + max + ":" + stride;
    }

    private static void checkIndirectRange(Buf c, long off, Buf n, long countOff, int maxDraws, int stride) {
        if ((c.usage & INDIRECT) == 0 || (n.usage & INDIRECT) == 0) throw new IllegalArgumentException("command and count buffers need INDIRECT usage");
        if (maxDraws < 1) throw new IllegalArgumentException("maxDraws >= 1");
        if (stride < 20 || (stride & 3) != 0) throw new IllegalArgumentException("stride " + stride + ": at least 20 (VkDrawIndexedIndirectCommand), multiple of 4");
        if (off < 0 || (off & 3) != 0 || off + (long) (maxDraws - 1) * stride + 20 > c.bytes)
            throw new IllegalArgumentException("commands [" + off + ", +" + maxDraws + " x " + stride + "] outside " + c.name + " (" + c.bytes + " bytes)");
        if (countOff < 0 || (countOff & 3) != 0 || countOff + 4 > n.bytes) throw new IllegalArgumentException("count offset " + countOff + " outside " + n.name);
    }

    /**
     * Starts drawing into {@code color} / {@code depth} (either may be null). {@code reads}: every buffer the pass's draws
     * read (storage, index, commands, counts): their barriers go here, before the pass.
     */
    public void beginRendering(RenderTargetHandle color, RenderTargetHandle depth, boolean clear, float[] clearRgba, float clearDepth, GpuBuffer... reads) {
        Target c = color == null ? null : target(color), d = depth == null ? null : target(depth);
        if (c == null && d == null) throw new IllegalArgumentException("a colour or a depth target");
        if (c != null) fitPhase(c); // INT-2 phase-size rule
        if (d != null) fitPhase(d);
        if (c != null && c.format.depth) throw new IllegalArgumentException(c.name + " is a depth target");
        if (d != null && !d.format.depth) throw new IllegalArgumentException(d.name + " is not a depth target");
        if (c != null && d != null && (c.width != d.width || c.height != d.height)) throw new IllegalArgumentException("colour and depth sizes differ");
        checkOutside("beginRendering");
        passReads.clear();
        for (GpuBuffer r : reads) {
            Buf b = buf(r);
            int stage = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT
                    | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT;
            use(b, stage, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_INDEX_READ_BIT, false);
            passReads.add(b);
        }
        // the targets themselves: earlier writes (a previous pass / a compute) become visible
        device.barrier(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
        flushBarrier();
        settleReads(passReads.toArray(new Buf[0]));
        device.beginRendering(c == null ? null : c.dev, d == null ? null : d.dev, clearRgba == null ? new float[4] : clearRgba, clearDepth, clear);
        passColor = c;
        passDepth = d;
    }

    public void endRendering() {
        if (passColor == null && passDepth == null) throw new IllegalStateException("endRendering without beginRendering");
        device.endRendering();
        passColor = passDepth = null;
        passReads.clear();
        passPrepared.clear();
    }

    public void multiDrawIndirectCount(IndirectPipelineHandle pipeline, IrohCompute.Binding[] storage, RenderTargetHandle[] textures, ByteBuffer push,
                                       GpuBuffer index, boolean index32, GpuBuffer commands, long commandOffset, GpuBuffer count, long countOffset,
                                       int maxDraws, int stride) {
        if (!(pipeline instanceof Indirect p) || p.core() != this) throw new IllegalArgumentException("not an api 4 indirect pipeline of this device");
        p.checkAlive();
        if (passColor == null && passDepth == null) throw new IllegalStateException("multiDrawIndirectCount outside beginRendering/endRendering");
        if ((passColor == null ? null : passColor.format) != p.color || (passDepth == null ? null : passDepth.format) != p.depth)
            throw new IllegalArgumentException(p.name + " was made for " + p.color + "/" + p.depth + ", the pass has "
                    + (passColor == null ? null : passColor.format) + "/" + (passDepth == null ? null : passDepth.format));
        Buf[] bufs = bindings(p.storage, storage, p.name);
        int nt = textures == null ? 0 : textures.length;
        if (nt != p.textures) throw new IllegalArgumentException(p.name + ": " + nt + " textures for " + p.textures);
        Object[] tex = new Object[nt];
        for (int i = 0; i < nt; i++) {
            Target t = target(textures[i]);
            if (t == passColor || t == passDepth) throw new IllegalArgumentException("a texture is the pass's own attachment");
            tex[i] = t.dev;
        }
        checkPush(p.push, push);
        Buf idx = buf(index), c = buf(commands), n = buf(count);
        if ((idx.usage & INDEX) == 0) throw new IllegalArgumentException(idx.name + " lacks INDEX usage");
        checkIndirectRange(c, commandOffset, n, countOffset, maxDraws, stride);
        for (Buf b : bufs) requireDeclared(b);
        requireDeclared(idx);
        requireDeclared(c);
        requireDeclared(n);
        for (int i = 0; i < bufs.length; i++)
            if (storage[i].access() != IrohCompute.Access.READ) throw new IllegalArgumentException("draws read storage buffers only (binding " + i + ")");
        String k = key(c, commandOffset, n, countOffset, maxDraws, stride);
        if (!passPrepared.contains(k)) throw new IllegalStateException("call prepareIndirectCount(...) with the same arguments before beginRendering");
        int indexType = index32 ? VK_INDEX_TYPE_UINT32 : VK_INDEX_TYPE_UINT16;
        if (device.supportsDrawIndirectCount())
            device.drawIndexedIndirectCount(p.dev, devs(bufs), tex, push, idx.dev, indexType, c.dev, commandOffset, n.dev, countOffset, maxDraws, stride);
        else
            device.drawIndexedIndirect(p.dev, devs(bufs), tex, push, idx.dev, indexType, c.dev, commandOffset, maxDraws, stride);
    }

    private void requireDeclared(Buf b) {
        if (!passReads.contains(b)) throw new IllegalStateException(b.name + " is read by a draw but was not declared at beginRendering");
    }

    /** Offline / tests: total live resources over all owners. */
    public int liveResources() {
        int n = 0;
        for (Owner o : owners.values()) n += o.live.size();
        return n;
    }
}
