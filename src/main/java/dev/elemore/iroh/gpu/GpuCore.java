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
    /** {@link GpuDevice.External} keys of Iroh's own textures (api 4.1). */
    public static final String BLOCK_ATLAS = "iroh:block_atlas", LIGHTMAP = "iroh:lightmap";

    private static volatile GpuCore instance;

    public static GpuCore get() {
        GpuCore c = instance;
        if (c == null) throw new UnsupportedOperationException("Iroh GPU api 4 is not available (no device yet, or capability off)");
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
        final int storage, push, textures;

        Compute(Owner o, String name, Object dev, int storage, int textures, int push) {
            super(o, name, 0);
            this.dev = dev;
            this.storage = storage;
            this.textures = textures;
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
        /** Written as an attachment / copy since the last barrier that covers sampling. */
        boolean written;

        Target(Owner o, String name, IrohTargets.Format format, int w, int h, float scale, Object dev) {
            super(o, name, (long) w * h * format.bytesPerTexel);
            this.format = format;
            this.width = w;
            this.height = h;
            this.scale = scale;
            this.dev = dev;
        }

        @Override public IrohTargets.Format format() { return format; }
        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public float scale() { return scale; }
        @Override void release() { device.destroyImage(dev); }
    }

    /** A static texture (api 4.1, offline stand-ins). */
    final class Tex extends Res implements GpuTexture {
        final Object dev;
        final int width, height;

        Tex(Owner o, String name, long bytes, int w, int h, Object dev) {
            super(o, name, bytes);
            this.width = w;
            this.height = h;
            this.dev = dev;
        }

        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override void release() { device.destroyImage(dev); }
    }

    /** One of Iroh's own textures, resolved by the device when bound (never freed by mods). */
    final class Ext implements GpuTexture {
        final GpuDevice.External key;

        Ext(String key) {
            this.key = new GpuDevice.External(key);
        }

        GpuCore core() { return GpuCore.this; }

        @Override public String owner() { return "iroh"; }
        @Override public String name() { return key.key(); }
        @Override public boolean alive() { return true; }
        @Override public void close() {}
        @Override public int width() { return 0; }
        @Override public int height() { return 0; }
    }

    /** The game's main colour / depth target (api 4.1 tokens; offline: stand-ins). */
    final class MainToken implements RenderTargetHandle {
        final boolean depth;

        MainToken(boolean depth) {
            this.depth = depth;
        }

        @Override public IrohTargets.Format format() { return depth ? IrohTargets.Format.DEPTH32F : IrohTargets.Format.RGBA8; }
        @Override public int width() { return screenW; }
        @Override public int height() { return screenH; }
        @Override public float scale() { return 1; }
        @Override public String owner() { return "iroh"; }
        @Override public String name() { return depth ? "main depth" : "main colour"; }
        @Override public boolean alive() { return true; }
        @Override public void close() {}
    }

    private final Map<String, Ext> externals = new HashMap<>();
    private final MainToken mainColor = new MainToken(false), mainDepth = new MainToken(true);

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
        return computePipeline(ownerId, name, glsl450, storageBuffers, 0, pushBytes);
    }

    /** api 4.1: storage buffers 0..n-1, then {@code sampledTextures} combined image samplers at n.. */
    public ComputePipelineHandle computePipeline(String ownerId, String name, String glsl450, int storageBuffers, int sampledTextures, int pushBytes) {
        Owner o = owner(ownerId);
        if (storageBuffers < 0 || storageBuffers > 16) throw new IllegalArgumentException("storage buffers 0..16");
        if (sampledTextures < 0 || sampledTextures > 8) throw new IllegalArgumentException("sampled textures 0..8");
        if (pushBytes < 0 || pushBytes > 128 || (pushBytes & 3) != 0) throw new IllegalArgumentException("push constants 0..128 bytes, multiple of 4");
        ByteBuffer spv = Glsl.compile(ownerId + ":" + name, glsl450, Glsl.COMPUTE);
        try {
            return new Compute(o, name, device.createComputePipeline(name, spv, storageBuffers, sampledTextures, pushBytes), storageBuffers,
                    sampledTextures, pushBytes);
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
        dispatch(pipeline, b, null, push, x, y, z);
    }

    public void dispatch(ComputePipelineHandle pipeline, IrohCompute.Binding[] b, GpuTexture[] textures, ByteBuffer push, int x, int y, int z) {
        Compute p = compute(pipeline);
        Object[] tex = textures(p.name, p.textures, textures, null, null);
        Buf[] bufs = bindings(p.storage, b, p.name);
        checkPush(p.push, push);
        if (x < 1 || y < 1 || z < 1 || x > MAX_GROUPS || y > MAX_GROUPS || z > MAX_GROUPS)
            throw new IllegalArgumentException("dispatch " + x + "x" + y + "x" + z + ": each 1.." + MAX_GROUPS);
        checkOutside("dispatch");
        declare(b, bufs, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        sampleTargets(textures, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        flushBarrier();
        device.dispatch(p.dev, devs(bufs), tex, push, x, y, z);
    }

    public void dispatchIndirect(ComputePipelineHandle pipeline, IrohCompute.Binding[] b, ByteBuffer push, GpuBuffer args, long offset) {
        dispatchIndirect(pipeline, b, null, push, args, offset);
    }

    public void dispatchIndirect(ComputePipelineHandle pipeline, IrohCompute.Binding[] b, GpuTexture[] textures, ByteBuffer push, GpuBuffer args, long offset) {
        Compute p = compute(pipeline);
        Object[] tex = textures(p.name, p.textures, textures, null, null);
        Buf[] bufs = bindings(p.storage, b, p.name);
        checkPush(p.push, push);
        Buf a = buf(args);
        if ((a.usage & INDIRECT) == 0) throw new IllegalArgumentException(a.name + " lacks INDIRECT usage");
        if (offset < 0 || (offset & 3) != 0 || offset + 12 > a.bytes) throw new IllegalArgumentException("indirect offset " + offset + " outside " + a.name);
        checkOutside("dispatchIndirect");
        use(a, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT, VK_ACCESS_INDIRECT_COMMAND_READ_BIT, false);
        declare(b, bufs, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        sampleTargets(textures, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        flushBarrier();
        device.dispatchIndirect(p.dev, devs(bufs), tex, push, a.dev, offset);
    }

    /** Device objects of sampled textures, checked: count, liveness, this device, not an attachment of the pass. */
    private Object[] textures(String what, int expected, GpuTexture[] t, Target color, Target depth) {
        int n = t == null ? 0 : t.length;
        if (n != expected) throw new IllegalArgumentException(what + ": " + n + " textures for " + expected);
        Object[] out = new Object[n];
        for (int i = 0; i < n; i++) {
            GpuTexture x = t[i];
            if (x == null) throw new IllegalArgumentException(what + ": texture " + i + " is null");
            if (x instanceof Target tg && tg.core() == this) {
                tg.checkAlive();
                if (tg == color || tg == depth) throw new IllegalArgumentException(what + ": texture " + i + " is the pass's own attachment");
                out[i] = tg.dev;
            } else if (x instanceof Tex tx && tx.core() == this) {
                tx.checkAlive();
                out[i] = tx.dev;
            } else if (x instanceof Ext e && e.core() == this) {
                out[i] = e.key;
            } else throw new IllegalArgumentException(what + ": texture " + i + " is not an api 4 texture of this device"
                    + (x instanceof MainToken ? " (the main target cannot be sampled: copy it with mainDepthCopy)" : ""));
        }
        return out;
    }

    private boolean targetsWritten;

    /** A target written as an attachment / by a copy and now sampled: one barrier from those writes to {@code stage}. */
    private void sampleTargets(GpuTexture[] t, int stage) {
        if (t == null || !targetsWritten) return;
        for (GpuTexture x : t)
            if (x instanceof Target tg && tg.written) {
                device.barrier(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT, stage, VK_ACCESS_SHADER_READ_BIT);
                clearWritten();
                return;
            }
    }

    private void clearWritten() {
        for (Owner o : owners.values()) for (Res r : o.live) if (r instanceof Target tg) tg.written = false;
        targetsWritten = false;
    }

    /**
     * Makes every write recorded so far visible to everything after (one global barrier, only when something is pending).
     * Iroh calls it after FrameStage.BEFORE_MAIN_PASS and at the end of {@link #runOutsideMainPass}, so main-pass draws
     * (which cannot place barriers) see what mods wrote before.
     */
    public void settleAll() {
        boolean pending = targetsWritten;
        for (Owner o : owners.values())
            for (Res r : o.live)
                if (r instanceof Buf b && b.writeStages != 0) {
                    pending = true;
                    b.writeStages = 0;
                    b.writeAccess = 0;
                }
        if (pending && device.canRecordOutsideMainPass())
            device.barrier(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
        clearWritten();
    }

    private int outside;

    public void runOutsideMainPass(Runnable work) {
        device.runOutsideMainPass(() -> {
            outside++;
            try {
                work.run();
            } finally {
                outside--;
                settleAll();
            }
        });
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
            width = Math.max(1, Math.round(screenW * scale));
            height = Math.max(1, Math.round(screenH * scale));
        }
        if (width < 1 || height < 1 || width > 16384 || height > 16384) throw new IllegalArgumentException("target size " + width + "x" + height);
        long bytes = (long) width * height * format.bytesPerTexel;
        if (o.bytes + bytes > ownerShareBytes()) {
            lastRefusal = ownerId + ": target " + name + " (" + (bytes >> 20) + " MiB) would exceed its share " + (ownerShareBytes() >> 20)
                    + " MiB (holds " + (o.bytes >> 20) + " MiB)";
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
                if (r instanceof Target t && t.scale > 0) {
                    int nw = Math.max(1, Math.round(screenW * t.scale)), nh = Math.max(1, Math.round(screenH * t.scale));
                    if (nw == t.width && nh == t.height) continue;
                    device.destroyImage(t.dev);
                    o.bytes -= t.bytes;
                    t.dev = device.createImage(t.name, nw, nh, t.format.vkFormat, t.format.depth);
                    t.width = nw;
                    t.height = nh;
                    t.bytes = (long) nw * nh * t.format.bytesPerTexel;
                    o.bytes += t.bytes;
                }
    }

    Target target(RenderTargetHandle h) {
        if (h instanceof MainToken) throw new IllegalArgumentException("the main target only works as a beginRendering attachment");
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

    // ================================================================================================ api 4.1 textures / main

    public GpuTexture externalTexture(String key) {
        return externals.computeIfAbsent(key, Ext::new);
    }

    /** Offline: the image a key resolves to (the test bed's block atlas / lightmap). */
    public void standIn(String key, GpuTexture texture) {
        Object[] d = textures("standIn", 1, new GpuTexture[]{texture}, null, null);
        if (d[0] instanceof GpuDevice.External) throw new IllegalArgumentException("a stand-in must be a real texture");
        device.setStandIn(key, d[0]);
    }

    public GpuTexture createTexture(String ownerId, String name, int w, int h, int levels, ByteBuffer texels, boolean linear) {
        Owner o = owner(ownerId);
        if (w < 1 || h < 1 || w > 16384 || h > 16384 || levels < 1 || levels > 32 - Integer.numberOfLeadingZeros(Math.max(w, h)))
            throw new IllegalArgumentException("texture " + w + "x" + h + " with " + levels + " levels");
        long bytes = 0;
        for (int l = 0; l < levels; l++) bytes += (long) Math.max(1, w >> l) * Math.max(1, h >> l) * 4;
        if (texels == null || texels.remaining() < bytes || !texels.isDirect())
            throw new IllegalArgumentException("texture " + name + ": a direct buffer of " + bytes + " bytes (all levels)");
        if (o.bytes + bytes > ownerShareBytes()) {
            lastRefusal = ownerId + ": texture " + name + " would exceed its share";
            return null;
        }
        return new Tex(o, name, bytes, w, h, device.createTexture(name, w, h, levels, texels, linear));
    }

    /** Offline: targets standing in for the game's main colour (RGBA8) and depth (DEPTH32F). */
    public void standInMain(RenderTargetHandle color, RenderTargetHandle depth) {
        Target c = target(color), d = target(depth);
        if (c.format != IrohTargets.Format.RGBA8 || d.format != IrohTargets.Format.DEPTH32F) throw new IllegalArgumentException("RGBA8 + DEPTH32F");
        device.setMainStandIn(c.dev, d.dev);
    }

    public RenderTargetHandle mainColor() {
        return mainColor;
    }

    public RenderTargetHandle mainDepth() {
        return mainDepth;
    }

    public void mainDepthCopy(RenderTargetHandle target) {
        Target t = target(target);
        if (t.format != IrohTargets.Format.DEPTH32F) throw new IllegalArgumentException("mainDepthCopy into a DEPTH32F target");
        checkOutside("mainDepthCopy");
        device.copyMainDepth(t.dev);
    }

    public void copyTarget(RenderTargetHandle src, RenderTargetHandle dst) {
        Target a = target(src), b = target(dst);
        if (a == b || a.format != b.format || a.width != b.width || a.height != b.height)
            throw new IllegalArgumentException("copy needs two different targets of equal size and format");
        checkOutside("copy");
        device.copyImage(a.dev, b.dev);
    }

    public void uploadTarget(RenderTargetHandle target, ByteBuffer texels) {
        Target t = target(target);
        if (texels == null || !texels.isDirect() || texels.remaining() < (long) t.width * t.height * 4)
            throw new IllegalArgumentException("upload into " + t.name + ": a direct buffer of " + ((long) t.width * t.height * 4) + " bytes");
        checkOutside("upload");
        device.uploadImage(t.dev, texels);
    }

    public int framesInFlight() {
        return device.framesInFlight();
    }

    public int frameSlot() {
        return device.frameSlot();
    }

    // ================================================================================================ api 4.1 profiling

    private record Marker(String key, int begin, int end) {}

    private final List<List<Marker>> markers = new ArrayList<>();
    private final Map<String, Integer> openMarks = new HashMap<>();
    private final Map<String, double[]> passMs = new LinkedHashMap<>(); // key -> {ema, last, frames, sum}
    private int queries;
    private boolean queriesReset, queriesFailed;

    /** Start of a frame: the slot's previous queries are finished (fence waited); fold them into the per-pass times. */
    public void frameStart() {
        int slot = device.frameSlot();
        while (markers.size() <= slot) markers.add(new ArrayList<>());
        List<Marker> m = markers.get(slot);
        if (!m.isEmpty()) {
            int max = 0;
            for (Marker k : m) max = Math.max(max, Math.max(k.begin, k.end) + 1);
            long[] t = device.readTimestamps(slot, max);
            if (t != null)
                for (Marker k : m) {
                    if (k.end < 0) continue;
                    double ms = (t[k.end] - t[k.begin]) / 1e6;
                    double[] v = passMs.computeIfAbsent(k.key, x -> new double[4]);
                    v[0] = v[2] == 0 ? ms : v[0] * 0.9 + ms * 0.1;
                    v[1] = ms;
                    v[2]++;
                    v[3] += ms;
                }
            m.clear();
        }
        queries = 0;
        queriesReset = queriesFailed = false;
        openMarks.clear();
    }

    /** GPU time of {@code pass} of {@code owner} from here to {@link #profileEnd}; nothing where timestamps are unsupported. */
    public void profileBegin(String ownerId, String pass) {
        if (queriesFailed || device.timestampCapacity() == 0) return;
        if (!queriesReset) {
            if (!device.resetTimestamps()) {
                queriesFailed = true;
                return;
            }
            queriesReset = true;
        }
        if (queries + 2 > device.timestampCapacity()) return;
        String key = owner(ownerId).id + " " + pass;
        int i = queries++;
        device.writeTimestamp(i);
        openMarks.put(ownerId, i);
        int slot = device.frameSlot();
        while (markers.size() <= slot) markers.add(new ArrayList<>());
        markers.get(slot).add(new Marker(key, i, -1));
    }

    public void profileEnd(String ownerId) {
        Integer b = openMarks.remove(ownerId);
        if (b == null) return;
        int i = queries++;
        device.writeTimestamp(i);
        List<Marker> m = markers.get(device.frameSlot());
        for (int k = m.size() - 1; k >= 0; k--)
            if (m.get(k).begin == b) {
                m.set(k, new Marker(m.get(k).key, b, i));
                break;
            }
    }

    /** Smoothed ms of a pass (0 when never measured). */
    public double profileMs(String ownerId, String pass) {
        double[] v = passMs.get(ownerId + " " + pass);
        return v == null ? 0 : v[0];
    }

    /** Mean ms of a pass over every frame measured so far, and how many frames. */
    public double[] profileMean(String ownerId, String pass) {
        double[] v = passMs.get(ownerId + " " + pass);
        return v == null ? new double[]{0, 0} : new double[]{v[3] / Math.max(1, v[2]), v[2]};
    }

    /** "vista mesh=0.210 vista cull=0.050 ..." (smoothed ms). */
    public String profileLine() {
        StringBuilder b = new StringBuilder();
        for (var e : passMs.entrySet()) {
            if (b.length() > 0) b.append(' ');
            b.append(e.getKey().replace(' ', ':')).append('=').append(String.format(Locale.ROOT, "%.3f", e.getValue()[0]));
        }
        return b.toString();
    }

    // ================================================================================================ indirect draws

    public IndirectPipelineHandle indirectPipeline(String ownerId, String name, String vertexGlsl, String fragmentGlsl, int storageBuffers,
                                                   int sampledTextures, int pushBytes, IrohTargets.Format color, IrohTargets.Format depth,
                                                   int depthCompare, boolean depthWrite, boolean blend) {
        return indirectPipeline(ownerId, name, vertexGlsl, fragmentGlsl, storageBuffers, sampledTextures, pushBytes, color, depth, depthCompare,
                depthWrite, blend ? IrohTargets.Blend.ALPHA : IrohTargets.Blend.NONE);
    }

    public IndirectPipelineHandle indirectPipeline(String ownerId, String name, String vertexGlsl, String fragmentGlsl, int storageBuffers,
                                                   int sampledTextures, int pushBytes, IrohTargets.Format color, IrohTargets.Format depth,
                                                   int depthCompare, boolean depthWrite, IrohTargets.Blend blend) {
        Owner o = owner(ownerId);
        if (blend == null) throw new IllegalArgumentException("blend required");
        if (color == null && depth == null) throw new IllegalArgumentException("a colour or a depth format");
        if (color != null && color.depth) throw new IllegalArgumentException("colour format " + color + " is a depth format");
        if (depth != null && !depth.depth) throw new IllegalArgumentException("depth format " + depth + " is not a depth format");
        if (storageBuffers < 0 || storageBuffers > 16 || sampledTextures < 0 || sampledTextures > 8) throw new IllegalArgumentException("bindings");
        if (pushBytes < 0 || pushBytes > 128 || (pushBytes & 3) != 0) throw new IllegalArgumentException("push constants 0..128, multiple of 4");
        ByteBuffer vs = Glsl.compile(ownerId + ":" + name + ".vert", vertexGlsl, Glsl.VERTEX);
        ByteBuffer fs = Glsl.compile(ownerId + ":" + name + ".frag", fragmentGlsl, Glsl.FRAGMENT);
        try {
            Object dev = device.createDrawPipeline(name, vs, fs, storageBuffers, sampledTextures, pushBytes,
                    color == null ? 0 : color.vkFormat, depth == null ? 0 : depth.vkFormat, depthCompare, depthWrite, blend.ordinal());
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
                zeroTailPipe = device.createComputePipeline("iroh zero tail", spv, 2, 0, 16);
            } finally {
                MemoryUtil.memFree(spv);
            }
        }
        ByteBuffer push = MemoryUtil.memAlloc(16);
        try {
            push.putInt((int) (off >> 2)).putInt((int) (countOff >> 2)).putInt(maxDraws).putInt(stride >> 2).flip();
            device.dispatch(zeroTailPipe, new Object[]{c.dev, n.dev}, null, push, (maxDraws + 63) / 64, 1, 1);
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
        if (color instanceof MainToken || depth instanceof MainToken) {
            beginMain(color, depth, clear, reads);
            return;
        }
        Target c = color == null ? null : target(color), d = depth == null ? null : target(depth);
        if (c == null && d == null) throw new IllegalArgumentException("a colour or a depth target");
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
        if (c != null) c.written = true;
        if (d != null) d.written = true;
        targetsWritten = true;
    }

    private boolean inMain;

    /** Joins the main pass: no barriers possible, so every declared read must already be settled. */
    private void beginMain(RenderTargetHandle color, RenderTargetHandle depth, boolean clear, GpuBuffer[] reads) {
        if (color != mainColor || depth != mainDepth) throw new IllegalArgumentException("the main target: mainColor() with mainDepth()");
        if (clear) throw new IllegalArgumentException("the main target is never cleared by a mod");
        if (inMain || passColor != null || passDepth != null) throw new IllegalStateException("a pass is already open");
        if (device.mainColorFormat() != IrohTargets.Format.RGBA8.vkFormat || device.mainDepthFormat() != IrohTargets.Format.DEPTH32F.vkFormat)
            throw new UnsupportedOperationException("main target formats " + device.mainColorFormat() + "/" + device.mainDepthFormat() + " are not RGBA8/D32F");
        passReads.clear();
        for (GpuBuffer r : reads) {
            Buf b = buf(r);
            // an upload is fine: uploads land before the frame's GPU work (game: Iroh's upload submit; offline: emulated)
            boolean uploadOnly = b.writeStages == VK_PIPELINE_STAGE_TRANSFER_BIT && b.writeAccess == VK_ACCESS_TRANSFER_WRITE_BIT;
            if (b.writeStages != 0 && !uploadOnly)
                throw new IllegalStateException(b.name + " was written after the last settle: write it in BEFORE_MAIN_PASS or runOutsideMainPass");
            b.readStages |= VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT;
            passReads.add(b);
        }
        if (targetsWritten) throw new IllegalStateException("a target was written after the last settle: render it inside runOutsideMainPass");
        device.beginMainRendering();
        inMain = true;
    }

    public void endRendering() {
        if (inMain) {
            device.endMainRendering();
            inMain = false;
        } else {
            if (passColor == null && passDepth == null) throw new IllegalStateException("endRendering without beginRendering");
            device.endRendering();
        }
        passColor = passDepth = null;
        passReads.clear();
        passPrepared.clear();
    }

    private IrohTargets.Format passColorFormat() {
        return inMain ? IrohTargets.Format.RGBA8 : passColor == null ? null : passColor.format;
    }

    private IrohTargets.Format passDepthFormat() {
        return inMain ? IrohTargets.Format.DEPTH32F : passDepth == null ? null : passDepth.format;
    }

    private Indirect drawPipe(IndirectPipelineHandle pipeline, String what) {
        if (!(pipeline instanceof Indirect p) || p.core() != this) throw new IllegalArgumentException("not an api 4 indirect pipeline of this device");
        p.checkAlive();
        if (!inMain && passColor == null && passDepth == null) throw new IllegalStateException(what + " outside beginRendering/endRendering");
        if (passColorFormat() != p.color || passDepthFormat() != p.depth)
            throw new IllegalArgumentException(p.name + " was made for " + p.color + "/" + p.depth + ", the pass has " + passColorFormat() + "/" + passDepthFormat());
        return p;
    }

    /** api 4.1: a non-indexed draw (full-screen pass: 3 vertices, 1 instance) inside a pass. */
    public void draw(IndirectPipelineHandle pipeline, IrohCompute.Binding[] storage, GpuTexture[] textures, ByteBuffer push, int vertexCount, int instanceCount) {
        Indirect p = drawPipe(pipeline, "draw");
        Buf[] bufs = bindings(p.storage, storage, p.name);
        Object[] tex = textures(p.name, p.textures, textures, passColor, passDepth);
        if (inMain) for (GpuTexture t : textures == null ? new GpuTexture[0] : textures)
            if (t instanceof Target tg && tg.written) throw new IllegalStateException(tg.name + " written after the last settle");
        checkPush(p.push, push);
        if (vertexCount < 1 || instanceCount < 1) throw new IllegalArgumentException("vertexCount and instanceCount >= 1");
        for (int i = 0; i < bufs.length; i++) {
            requireDeclared(bufs[i]);
            if (storage[i].access() != IrohCompute.Access.READ) throw new IllegalArgumentException("draws read storage buffers only (binding " + i + ")");
        }
        device.draw(p.dev, devs(bufs), tex, push, vertexCount, instanceCount);
    }

    public void multiDrawIndirectCount(IndirectPipelineHandle pipeline, IrohCompute.Binding[] storage, GpuTexture[] textures, ByteBuffer push,
                                       GpuBuffer index, boolean index32, GpuBuffer commands, long commandOffset, GpuBuffer count, long countOffset,
                                       int maxDraws, int stride) {
        Indirect p = drawPipe(pipeline, "multiDrawIndirectCount");
        Buf[] bufs = bindings(p.storage, storage, p.name);
        Object[] tex = textures(p.name, p.textures, textures, passColor, passDepth);
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
