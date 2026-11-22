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
        return imageDescs.size() - 1;
    }

    @Override
    public ImageDesc desc(int image) {
        return imageDescs.get(image);
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
        inFrame = true;
        dirty.clear();
        calls.add("beginFrame");
    }

    @Override
    public void barrier(int image, String from, String to) {
        dirty.remove(image);
        calls.add("barrier " + n(image) + " " + from + " -> " + to);
    }

    private void read(int image, String what) {
        if ("W".equals(dirty.get(image)))
            violations.add(what + " reads " + n(image) + " written since its last barrier");
        dirty.putIfAbsent(image, "R");
    }

    private void write(int image, String what) {
        if (dirty.containsKey(image))
            violations.add(what + " writes " + n(image) + " used since its last barrier (" + dirty.get(image) + ")");
        dirty.put(image, "W");
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
        for (Bind b : d.set0().values())
            if (b.image() >= 0) {
                if (attached.contains(b.image()))
                    violations.add(d.pass() + " samples " + n(b.image()) + " while it is attached (feedback loop)");
                read(b.image(), d.pass());
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

    @Override
    public void dispatch(Dispatch d) {
        for (Bind b : d.set0().values())
            if (b.image() >= 0) {
                if ("STORAGE".equals(b.sampler()))
                    write(b.image(), d.pass());
                else
                    read(b.image(), d.pass());
            }
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
