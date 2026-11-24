package dev.elemore.iroh.shaderpack.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A fake device that records every call and checks synchronisation as it goes: a sampled/storage read of an image
 * written since the last barrier on it, or a write over an image read or written since its last barrier, is a
 * violation (a missing barrier on a real device). No Vulkan, no native code: the executor's ORDER and STATE are
 * validated against the plan from these records ({@link ExecValidator}).
 */
public final class RecordingBackend implements GpuBackend {

    public final List<String> calls = new ArrayList<>();
    public final List<String> violations = new ArrayList<>();
    public final List<Draw> draws = new ArrayList<>();
    public final List<Dispatch> dispatches = new ArrayList<>();
    public final List<int[]> mipCalls = new ArrayList<>();
    public final Set<Integer> cleared = new HashSet<>();
    public final List<ImageDesc> imageDescs = new ArrayList<>();
    private final List<String> pipelineIds = new ArrayList<>();
    private final Map<Integer, String> dirty = new HashMap<>(); // image -> "W" (written) or "R" (read) since last barrier
    private boolean inFrame;
    /**
     * V-7: model frames in flight (the in-game contract): a frame does not end the previous frame's hazards. What owned
     * images saw after the previous frame's last barrier stays pending until ANY barrier (VkPackBackend's barrier is one
     * full memory barrier); a conflicting access before it is a violation. Imported images are excluded (their owner
     * synchronises them between pack frames).
     */
    public boolean framesInFlight;
    private final Map<Integer, String> prevDirty = new HashMap<>();
    /** accesses since the last barrier on ANY image (W wins): what the next frame inherits */
    private final Map<Integer, String> tail = new HashMap<>();
    /** V-6: images created outside a frame; their creation clear (VkPackBackend zeroes every new image) is a write the
     *  next frame must barrier: in game the setup is recorded into the SAME command buffer as that frame */
    private final Set<Integer> createdOutside = new HashSet<>();
    public final java.util.Set<Integer> freed = new HashSet<>();
    public final java.util.Set<Integer> imported = new HashSet<>();
    /** frame counter and the frame each image was last used in (epoch rule: never freed while a frame using it is in flight) */
    public int frameNo;
    public final Map<Integer, Integer> lastUse = new HashMap<>();
    /** images whose mip chain matches level 0 (generateMips after the last write); a LINEAR (mipmapped) read of any
     *  other multi-level image would see stale levels (G1 FXAA outline artefact) */
    private final java.util.Set<Integer> mipsFresh = new HashSet<>();

    @Override
    public String name() {
        return "recording";
    }

    @Override
    public int createImage(ImageDesc d) {
        if (d.width() <= 0 || d.height() <= 0 || d.depth() <= 0)
            violations.add("image " + d.name() + " with empty extent");
        if (!TexelCodec.supported(d.vkFormat()))
            violations.add("image " + d.name() + " format " + d.vkFormat() + " unknown");
        imageDescs.add(d);
        calls.add("createImage " + d.name() + " " + d.vkFormat() + " " + d.width() + "x" + d.height() + "x" + d.depth() + " mips " + d.mips());
        int h = imageDescs.size() - 1;
        if (inFrame)
            { dirty.put(h, "W"); tail.put(h, "W"); } // V-6: the creation clear is a transfer write
        else
            createdOutside.add(h);
        return h;
    }

    @Override
    public ImageDesc desc(int image) {
        return imageDescs.get(image);
    }

    private void use(int image, String what) {
        if (freed.contains(image))
            violations.add(what + " uses " + n(image) + " after it was freed");
        lastUse.put(image, frameNo);
    }

    public final java.util.Set<Integer> freedPipelines = new HashSet<>();

    private String timing;

    @Override
    public void timeBegin(String pass) {
        if (timing != null)
            violations.add("timer " + pass + " begun inside " + timing);
        timing = pass;
        calls.add("time begin " + pass);
    }

    @Override
    public void timeEnd(String pass) {
        if (!pass.equals(timing))
            violations.add("timer end " + pass + " without its begin (open: " + timing + ")");
        timing = null;
        calls.add("time end " + pass);
    }

    @Override
    public void freePipeline(int pipeline) {
        if (!freedPipelines.add(pipeline))
            violations.add("double free of pipeline " + pipeline);
        calls.add("freePipeline " + pipeline);
    }

    public int livePipelines() {
        return pipelineIds.size() - freedPipelines.size();
    }

    @Override
    public void freeImage(int image) {
        if (freed.contains(image))
            violations.add("double free of " + n(image));
        freed.add(image);
        calls.add("freeImage " + n(image) + " (last used in frame " + lastUse.getOrDefault(image, -1) + ", now " + frameNo + ")");
    }

    @Override
    public int importImage(Object external, String name) {
        int h = createImage(new ImageDesc(name, "R8G8B8A8_UNORM", 8, 8, 1, 1, java.util.Set.of("COLOR_ATTACHMENT", "SAMPLED", "TRANSFER_SRC", "TRANSFER_DST")));
        imported.add(h);
        dirty.remove(h); // an imported image is not cleared by this backend
        createdOutside.remove(h);
        return h;
    }

    /** owned images created and not freed */
    public int live() {
        int n = 0;
        for (int i = 0; i < imageDescs.size(); i++)
            if (!freed.contains(i) && !imported.contains(i))
                n++;
        return n;
    }

    @Override
    public void blit(int src, int dst) {
        use(src, "blit");
        use(dst, "blit");
        read(src, "blit");
        write(dst, "blit");
        calls.add("blit " + n(src) + " -> " + n(dst));
    }

    private String n(int image) {
        return image >= 0 && image < imageDescs.size() ? imageDescs.get(image).name() : "#" + image;
    }

    @Override
    public void upload(int image, float[] rgba) {
        ImageDesc d = imageDescs.get(image);
        long texels = (long) d.width() * d.height() * d.depth();
        if (rgba.length != texels * 4)
            violations.add("upload " + d.name() + ": " + rgba.length / 4 + " texels for " + texels);
        write(image, "upload");
        calls.add("upload " + d.name());
    }

    @Override
    public int createBuffer(String name, long bytes) {
        calls.add("createBuffer " + name + " " + bytes);
        return calls.size();
    }

    @Override
    public int createPipeline(PipelineDesc d) {
        if (d.computeSpirv() == null && (d.vertexSpirv() == null || d.fragmentSpirv() == null))
            violations.add("pipeline " + d.id() + " without both raster stages");
        for (byte[] s : new byte[][]{d.vertexSpirv(), d.fragmentSpirv(), d.computeSpirv()})
            if (s != null && (s.length < 20 || (s[0] & 0xff) != 0x03 || (s[3] & 0xff) != 0x07))
                violations.add("pipeline " + d.id() + ": stage is not SPIR-V");
        pipelineIds.add(d.id());
        calls.add("createPipeline " + d.id() + " colour " + d.colourFormats() + " slots " + d.slots().size());
        return pipelineIds.size() - 1;
    }

    public String pipelineId(int p) {
        return pipelineIds.get(p);
    }

    @Override
    public void beginFrame() {
        frameNo++;
        inFrame = true;
        prevDirty.clear();
        if (framesInFlight)
            tail.forEach((h, k) -> {
                if (!imported.contains(h))
                    prevDirty.put(h, k);
            });
        tail.clear();
        dirty.clear();
        for (int h : createdOutside)
            { dirty.put(h, "W"); tail.put(h, "W"); }
        createdOutside.clear();
        calls.add("beginFrame");
    }

    @Override
    public void barrier(int image, String from, String to) {
        dirty.remove(image);
        prevDirty.clear(); // one full memory barrier covers every earlier access
        tail.clear();
        calls.add("barrier " + n(image) + " " + from + " -> " + to);
    }

    private void read(int image, String what) {
        use(image, what);
        if ("W".equals(dirty.get(image)))
            violations.add(what + " reads " + n(image) + " written since its last barrier");
        else if ("W".equals(prevDirty.get(image)))
            violations.add(what + " reads " + n(image) + " written by the previous frame after its last barrier (frames in flight)");
        dirty.putIfAbsent(image, "R");
        tail.putIfAbsent(image, "R");
    }

    private void write(int image, String what) {
        use(image, what);
        if (!what.equals("mips"))
            mipsFresh.remove(image);
        if (dirty.containsKey(image))
            violations.add(what + " writes " + n(image) + " used since its last barrier (" + dirty.get(image) + ")");
        else if (prevDirty.containsKey(image))
            violations.add(what + " writes " + n(image) + " used by the previous frame after its last barrier (" + prevDirty.get(image) + ", frames in flight)");
        dirty.put(image, "W");
        tail.put(image, "W");
    }

    @Override
    public void clear(int image, float[] rgba) {
        if (!inFrame)
            violations.add("clear outside a frame");
        write(image, "clear");
        cleared.add(image);
        calls.add("clear " + n(image));
    }

    @Override
    public void generateMips(int image) {
        if (imageDescs.get(image).mips() <= 1)
            violations.add("mips requested on " + n(image) + " which has one level");
        write(image, "mips");
        mipsFresh.add(image);
        mipCalls.add(new int[]{image, draws.size()});
        calls.add("mips " + n(image));
    }

    @Override
    public void copy(int src, int dst) {
        read(src, "copy");
        write(dst, "copy");
        calls.add("copy " + n(src) + " -> " + n(dst));
    }

    @Override
    public void draw(Draw d) {
        if (!inFrame)
            violations.add("draw outside a frame");
        Set<Integer> attached = new HashSet<>();
        for (int c : d.colour())
            attached.add(c);
        bindAccess(d.set0(), d.pass(), attached);
        for (Bind b : d.set0().values())
            if (b.image() >= 0) {
                GpuBackend.ImageDesc bd = imageDescs.get(b.image());
                if ("LINEAR".equals(b.sampler()) && bd != null && bd.mips() > 1 && !mipsFresh.contains(b.image()))
                    violations.add(d.pass() + " samples " + n(b.image()) + " with mipmaps whose levels are stale");
            }
        for (int c : d.colour()) {
            ImageDesc cd = imageDescs.get(c);
            if (!cd.usage().contains("COLOR_ATTACHMENT"))
                violations.add(d.pass() + " attaches " + cd.name() + " without COLOR_ATTACHMENT usage");
            write(c, d.pass());
        }
        if (d.viewport()[2] <= 0 || d.viewport()[3] <= 0)
            violations.add(d.pass() + " empty viewport");
        draws.add(d);
        calls.add("draw " + d.pass());
    }

    /**
     * One draw/dispatch's image accesses as a unit: an image bound both as storage and as a sampler in the same
     * program (Navidson's voxel / floodfill volumes) is one read-modify-write by that program, i.e. a write; the
     * executor's barrier before the draw covers it.
     */
    private void bindAccess(Map<Integer, Bind> set0, String pass, Set<Integer> attached) {
        Set<Integer> stored = new HashSet<>();
        for (Bind b : set0.values())
            if (b.image() >= 0 && "STORAGE".equals(b.sampler()))
                stored.add(b.image());
        Set<Integer> done = new HashSet<>();
        for (Bind b : set0.values()) {
            if (b.image() < 0 || !done.add(b.image()))
                continue;
            if (attached.contains(b.image()))
                violations.add(pass + " samples " + n(b.image()) + " while it is attached (feedback loop)");
            if (stored.contains(b.image()))
                write(b.image(), pass);
            else
                read(b.image(), pass);
        }
    }

    public final List<GeoDraw> geoDraws = new ArrayList<>();
    private int vertexBuffers;
    public final Set<Integer> freedBuffers = new HashSet<>();

    @Override
    public int createVertexBuffer(String name, java.nio.ByteBuffer data) {
        calls.add("createVertexBuffer " + name + " " + data.remaining());
        return vertexBuffers++;
    }

    @Override
    public void freeBuffer(int buffer) {
        if (!freedBuffers.add(buffer))
            violations.add("double free of buffer " + buffer);
        calls.add("freeBuffer " + buffer);
    }

    public int liveVertexBuffers() {
        return vertexBuffers - freedBuffers.size();
    }

    @Override
    public void drawGeometry(GeoDraw d) {
        if (!inFrame)
            violations.add("geometry outside a frame");
        Set<Integer> attached = new HashSet<>();
        for (int c : d.colour())
            attached.add(c);
        if (d.depth() >= 0)
            attached.add(d.depth());
        bindAccess(d.set0(), d.pass(), attached);
        for (int c : d.colour()) {
            ImageDesc cd = imageDescs.get(c);
            if (!cd.usage().contains("COLOR_ATTACHMENT"))
                violations.add(d.pass() + " attaches " + cd.name() + " without COLOR_ATTACHMENT usage");
            write(c, d.pass());
        }
        if (d.depth() >= 0) {
            ImageDesc dd = imageDescs.get(d.depth());
            if (dd == null || !dd.vkFormat().startsWith("D"))
                violations.add(d.pass() + " depth attachment " + (dd == null ? "?" : dd.name()) + " is not a depth format");
            write(d.depth(), d.pass());
        }
        for (GeoBatch gb : d.batches())
            if (gb.indirect() != null ? gb.indirect().vertexBuffer() == 0 || gb.indirect().indirectBuffer() == 0 || gb.indirect().indexBuffer() == 0
                    : gb.vertexBuffer() < 0 ? gb.externalBuffer() == 0 : freedBuffers.contains(gb.vertexBuffer()) || gb.vertexBuffer() >= vertexBuffers)
                violations.add(d.pass() + " draws from a freed or unknown vertex buffer " + gb.vertexBuffer());
        geoDraws.add(d);
        calls.add("geometry " + d.pass() + " batches " + d.batches().size());
    }

    @Override
    public void dispatch(Dispatch d) {
        bindAccess(d.set0(), d.pass(), Set.of());
        for (int g : d.groups())
            if (g <= 0)
                violations.add(d.pass() + " dispatch with " + g + " groups");
        dispatches.add(d);
        calls.add("dispatch " + d.pass());
    }

    @Override
    public void endFrame() {
        inFrame = false;
        calls.add("endFrame");
    }

    @Override
    public float[] readback(int image) {
        return null;
    }

    @Override
    public void close() {
    }
}
