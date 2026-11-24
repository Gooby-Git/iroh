package dev.elemore.iroh.shaderpack.runtime;

import dev.elemore.iroh.shaderpack.FrontEnd;
import dev.elemore.iroh.shaderpack.PackSource;
import dev.elemore.iroh.shaderpack.StandardMacros;
import dev.elemore.iroh.shaderpack.graph.PackData;
import dev.elemore.iroh.shaderpack.graph.PackPlan;
import dev.elemore.iroh.render.PassResume;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * PACK-FULL (td-c6e4f8, RQ-T2-a): the in-game pack FULL world pass on REAL Vulkan (lavapipe + validation core + sync,
 * 2 frames in flight, the GAME device feature set), with window resizes, a dimension change, a pack reload and the
 * shutdown, plus a live-object leak counter. T-2 saw in game (I-7 2b-v12, I-9 3b-f5/f7): vertex-stage voxel_img
 * pipelines without vertexPipelineStoresAndAtomics (06341), barriers / clears / buffer->image copies inside Iroh's
 * render pass after each resize, pack descriptors naming a renderer texture in TRANSFER_DST (04150, 09600, submit
 * layout), 62-227 objects leaked at vkDestroyDevice. No harness ran WorldPass on a device before (worldPassTest uses
 * the recording backend, which knows nothing of Iroh's render pass); this one does:
 * <ul>
 *   <li>Iroh's main pass = a real VkRenderPass over the scene colour + depth, begun / ended by the {@link WorldPass.Host}
 *       exactly as WorldPassGame does (the world pass ends it, resumes it after its attachments).</li>
 *   <li>Substituted draws = WorldPass.onBind + beforeDraw, then the renderer's draw (vkCmdDraw, zero vertex buffer).</li>
 *   <li>A renderer texture uploaded earlier in the frame (left in TRANSFER_DST, as VulkanImage after an in-frame upload)
 *       is the draw texture of the entity draw.</li>
 *   <li>Sizes 160x90 -> 200x120 -> 128x72 -> 160x90 (executor rebuild + new WorldPass + warm() with Iroh's pass bound,
 *       the game's order), world-1 and back, reload (shared objects destroyed, pack re-enabled), shutdown.</li>
 * </ul>
 * Leak counter: {@link VkPackBackend#liveCounts} at 160x90 after the first build must equal the counts after every later
 * return to 160x90 (resize, dimension, reload); after disable + forget + scene frees every per-pack count is 0; the
 * device is then destroyed with validation on: no "leaked" object report.
 * Modes: new (default) = the fixed code, must be clean; old = the pre-fix behaviour through the negative-control flags
 * (WorldPass bind order + no sample preparation, executor keeps its SSBOs, no store-feature check on the pre-fix device
 * feature set): every class above must show. {@code ./gradlew packFullVkRepro [-Pmode=old|new|both]}; last line
 * {@code # packfullvk PASS|FAIL ...}.
 */
public final class PackFullVkRepro {

    static final List<String> NEW_ENTITY = List.of("Position", "Color", "UV0", "UV1", "UV2", "Normal");
    static final List<String> POS = List.of("Position");
    static final int FRAMES_PER_STEP = 2;
    /** -Dpfv.noDraw=true: no renderer draw after substituted binds (bisecting lavapipe / GPU-AV problems) */
    static final boolean NO_DRAW = Boolean.getBoolean("pfv.noDraw");
    /**
     * -Pvalidate=gpuav (useLavapipe sets ti.vkval): GPU-AV's instrumented shaders SIGSEGV inside lavapipe's JIT (si_addr
     * 0x4, libvulkan_lvp thread) on the GAME feature set, with or without the renderer draws, and run clean on ALL
     * features (2026-11-24; same lavapipe/GPU-AV crash class as int-4c's fsrVelocityReplay / rtReplay c..i). The GPU-AV
     * pass therefore uses ALL; the GAME set is checked by the core + sync run.
     */
    static final boolean GPUAV = "gpuav".equals(System.getProperty("ti.vkval"));

    record Result(boolean old, Map<String, Integer> classes, List<String> samples, List<String> leakNotes, int knownBlit, boolean leakAtDestroy,
                  int zeroAfterDisable, int exceptions) {}

    public static void main(String[] args) throws Exception {
        String mode = System.getProperty("pfv.mode", "new");
        Path pack = Paths.get(System.getProperty("user.home"), ".local/share/atlauncher/instances/Vulkan/shaderpacks/Navidson");
        if (!Files.isDirectory(pack)) {
            System.out.println("# packfullvk SKIP (no Navidson pack on this machine)");
            return;
        }
        Path opt = pack.resolveSibling("Navidson.txt");
        String saved = Files.isRegularFile(opt) ? Files.readString(opt, StandardCharsets.UTF_8) : null;
        Path dump = Paths.get("build/packFullVkRepro/Navidson");
        if (Files.isDirectory(dump))
            try (var st = Files.walk(dump)) {
                for (Path q : st.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.delete(q);
            }
        FrontEnd.run(PackSource.open(pack), new FrontEnd.Config(StandardMacros.Env.defaults(), saved, true, dump));
        PackData data = PackData.load(dump);
        PackPlan plan = PackPlan.build(data);

        String wiring = shutdownWiring();
        System.out.println("# packfullvk-wiring " + (wiring == null ? "Vulkan.cleanUp releases the pack and the profiler pool" : "FAIL " + wiring));
        List<Result> rs = new ArrayList<>();
        if (!mode.equals("new"))
            rs.add(run(data, plan, true));
        if (!mode.equals("old"))
            rs.add(run(data, plan, false));
        boolean pass = wiring == null;
        for (Result r : rs) {
            String tag = r.old() ? "old" : "new";
            System.out.println("# packfullvk-" + tag + " validation " + r.classes() + (r.knownBlit() > 0 ? " (+" + r.knownBlit() + " known RQ-V6-a B10G11R11 blit-dst)" : "")
                    + " leak-at-destroy " + r.leakAtDestroy() + " nonzero-after-disable " + r.zeroAfterDisable() + " exceptions " + r.exceptions());
            r.leakNotes().forEach(x -> System.out.println("    leak counter: " + x));
            Files.write(Paths.get("build/packFullVkRepro/messages-" + tag + ".txt"), r.samples(), StandardCharsets.UTF_8);
            r.samples().stream().limit(r.old() ? 6 : 12).forEach(x -> System.out.println("    " + (x.length() > 300 ? x.substring(0, 300) : x).replace('\n', ' ')));
            int total = r.classes().values().stream().mapToInt(Integer::intValue).sum();
            if (r.old()) {
                // each fixed class must reproduce on the pre-fix behaviour
                boolean all = r.classes().getOrDefault("vertex-stores-06341", 0) > 0 && r.classes().getOrDefault("inside-render-pass", 0) > 0
                        && r.classes().getOrDefault("transfer-layout-descriptor", 0) > 0
                        && r.leakNotes().stream().anyMatch(x -> x.startsWith("ssbo cycle: storageBuffers")) && r.leakAtDestroy();
                System.out.println("# packfullvk-old " + (all ? "reproduced every class" : "MISSING a class (negative control blind)"));
                pass &= all;
            } else {
                boolean clean = total == 0 && !r.leakAtDestroy() && r.zeroAfterDisable() == 0 && r.leakNotes().isEmpty() && r.exceptions() == 0;
                pass &= clean;
            }
        }
        System.out.println("# packfullvk " + (pass ? "PASS" : "FAIL") + " (" + mode + ")");
        System.exit(pass ? 0 : 1);
    }

    /** Iroh's main render pass over the scene colour + depth (LOAD/STORE, GENERAL in and out), on the backend's command buffer */
    static final class Iroh implements WorldPass.Host {
        final HeadlessVkBackend gpu;
        long renderPass, framebuffer;
        int w, h;
        boolean bound;
        int begins, ends;
        final dev.elemore.iroh.render.CmdState cmdState = new dev.elemore.iroh.render.CmdState(new dev.elemore.iroh.render.CmdState.Backend() {
            public void bindPipeline(long h) {}
            public void depthBias(float c, float s) {}
            public void lineWidth(float w) {}
        });

        Iroh(HeadlessVkBackend gpu) {
            this.gpu = gpu;
        }

        void target(VkPackBackend.Img colour, VkPackBackend.Img depth, int w, int h) {
            destroy();
            this.w = w;
            this.h = h;
            try (MemoryStack s = stackPush()) {
                VkAttachmentDescription.Buffer att = VkAttachmentDescription.calloc(2, s);
                att.get(0).format(colour.format).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK_IMAGE_LAYOUT_GENERAL).finalLayout(VK_IMAGE_LAYOUT_GENERAL);
                att.get(1).format(depth.format).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK_IMAGE_LAYOUT_GENERAL).finalLayout(VK_IMAGE_LAYOUT_GENERAL);
                VkSubpassDescription.Buffer sub = VkSubpassDescription.calloc(1, s).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1)
                        .pColorAttachments(VkAttachmentReference.calloc(1, s).attachment(0).layout(VK_IMAGE_LAYOUT_GENERAL))
                        .pDepthStencilAttachment(VkAttachmentReference.calloc(s).attachment(1).layout(VK_IMAGE_LAYOUT_GENERAL));
                // the pack's work between Iroh's pass instances is ordered by the pack's own barriers; the pass orders
                // itself against earlier attachment writes like VulkanMod's main pass (external dependencies)
                VkSubpassDependency.Buffer dep = VkSubpassDependency.calloc(2, s);
                dep.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0).srcStageMask(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT).dstStageMask(VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT)
                        .srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
                dep.get(1).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL).srcStageMask(VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT).dstStageMask(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT)
                        .srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
                LongBuffer lb = s.mallocLong(1);
                check(vkCreateRenderPass(gpu.device(), VkRenderPassCreateInfo.calloc(s).sType$Default().pAttachments(att).pSubpasses(sub).pDependencies(dep), null, lb), "iroh pass");
                renderPass = lb.get(0);
                check(vkCreateFramebuffer(gpu.device(), VkFramebufferCreateInfo.calloc(s).sType$Default().renderPass(renderPass)
                        .pAttachments(s.longs(colour.viewAll, depth.viewAll)).width(w).height(h).layers(1), null, lb), "iroh fb");
                framebuffer = lb.get(0);
            }
        }

        void destroy() {
            if (renderPass != 0) {
                gpu.submitAndDrain(); // no frame in flight uses them
                vkDestroyFramebuffer(gpu.device(), framebuffer, null);
                vkDestroyRenderPass(gpu.device(), renderPass, null);
            }
            renderPass = framebuffer = 0;
        }

        void begin() {
            if (bound)
                throw new IllegalStateException("iroh pass already bound");
            try (MemoryStack s = stackPush()) {
                VkRenderPassBeginInfo bi = VkRenderPassBeginInfo.calloc(s).sType$Default().renderPass(renderPass).framebuffer(framebuffer);
                bi.renderArea().extent().width(w).height(h);
                vkCmdBeginRenderPass(gpu.cmd(), bi, VK_SUBPASS_CONTENTS_INLINE);
            }
            bound = true;
            begins++;
        }

        void end() {
            if (!bound)
                throw new IllegalStateException("iroh pass not bound");
            vkCmdEndRenderPass(gpu.cmd());
            bound = false;
            ends++;
        }

        public boolean irohPassBound() { return bound; }
        public void endIrohPass() { end(); }
        public void resumeIrohPass() { begin(); }
        public void invalidateBoundState() { }
        public void reapplyDynamicState() { }

        /** VulkanImage.endPassRunResume: the world pass closes first (SubstitutionHook), then PassResume */
        void outside(WorldPass wp, Runnable r) {
            if (wp != null)
                wp.closeForForeign();
            PassResume.run(new PassResume.Hooks() {
                public boolean passBound() { return bound; }
                public void endPass() { end(); }
                public void resumePass() { begin(); }
                public void reapplyViewportAndScissor() { }
            }, cmdState, r);
        }
    }

    static void check(int r, String what) {
        if (r != VK_SUCCESS)
            throw new IllegalStateException(what + ": " + r);
    }

    /** the game's inputs: no batches at START, the terrain lists from BEFORE_ENTITIES on (WorldPassTest.Phased) */
    static final class Phased implements PackFrameInputs {
        final SyntheticFullScene s;
        List<Geometry> geo = List.of();
        long version;
        Phased(SyntheticFullScene s) { this.s = s; }
        public int width() { return s.width(); }
        public int height() { return s.height(); }
        public Map<String, double[]> uniforms() { return s.uniforms(); }
        public Set<String> suppliedImages() { return s.suppliedImages(); }
        public float[] image(String name) { return s.image(name); }
        public int[] extent(String name) { return s.extent(name); }
        public String startStage() { return s.startStage(); }
        public double frameSeconds() { return s.frameSeconds(); }
        public List<Geometry> geometry() { return geo; }
        public long geometryVersion() { return version; }
        public Set<String> geometryTextures() { return s.geometryTextures(); }
        public Map<String, Integer> geometryImages() { return s.geometryImages(); }
        public int depthTo() { return s.depthTo(); }
        public Map<String, Integer> copySources() { return s.copySources(); }
        public Map<String, float[]> clearValues() { return s.clearValues(); }
    }

    /** one renderer image (owner) + its import into the pack backend, layout tracked like VulkanImage */
    static final class Rimg {
        int owner, handle;
        final int[] layout = {VK_IMAGE_LAYOUT_GENERAL};
        VkPackBackend.ImportedLayout lay;
        GpuBackend.ImageDesc desc;

        /** IrohVkBackend.importImage: one more import of the same renderer image (WorldPass imports draw textures itself) */
        int importAgain(HeadlessVkBackend gpu) {
            VkPackBackend.Img im = gpu.img(owner);
            return gpu.importRaw(desc, im.image, im.viewAll, im.format, lay);
        }
    }

    static void layoutBarrier(HeadlessVkBackend gpu, VkPackBackend.Img im, int from, int to) {
        try (MemoryStack s = stackPush()) {
            VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, s).sType$Default().oldLayout(from).newLayout(to)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(im.image)
                    .srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            b.subresourceRange().aspectMask(im.aspect).baseMipLevel(0).levelCount(VK_REMAINING_MIP_LEVELS).baseArrayLayer(0).layerCount(1);
            vkCmdPipelineBarrier(gpu.cmd(), VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, b);
        }
    }

    static Rimg rimg(HeadlessVkBackend gpu, String name, String fmt, int w, int h, Set<String> usage) {
        Rimg r = new Rimg();
        r.owner = gpu.createImage(new GpuBackend.ImageDesc(name + " (owner)", fmt, w, h, 1, 1, usage));
        gpu.barrier(r.owner, "TRANSFER_WRITE", "ANY");
        VkPackBackend.Img im = gpu.img(r.owner);
        r.desc = new GpuBackend.ImageDesc(name, fmt, w, h, 1, 1, Set.of("TRANSFER_SRC", "TRANSFER_DST"));
        r.lay = new VkPackBackend.ImportedLayout() {
            public int get() { return r.layout[0]; }
            public void set(int l) { r.layout[0] = l; }
            public void toSampled() { // VulkanImage.readOnlyLayout, frame-buffer branch (the caller is outside every pass)
                layoutBarrier(gpu, im, r.layout[0], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                r.layout[0] = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            }
        };
        r.handle = gpu.importRaw(r.desc, im.image, im.viewAll, im.format, r.lay);
        return r;
    }

    static void free(HeadlessVkBackend gpu, Rimg r) {
        if (r == null)
            return;
        gpu.freeImage(r.handle);
        gpu.freeImage(r.owner);
    }

    /** the whole run: one device, every step; returns the validation classes and the leak notes */
    /** -Pneg=bind,sample,ssbo,store,begin,shutdown: in mode new, turn on only these negative controls (which fix removes which errors) */
    static final Set<String> NEG = Set.of(System.getProperty("pfv.neg", "").split(","));

    static boolean neg(boolean old, String k) {
        return old || NEG.contains(k);
    }

    static Result run(PackData data, PackPlan plan, boolean old) {
        WorldPass.oldBindOrderForTest = neg(old, "bind");
        WorldPass.noSamplePrepForTest = neg(old, "sample");
        FrameExecutor.keepStorageBuffersForTest = neg(old, "ssbo");
        VkPackBackend.skipStoreCheckForTest = neg(old, "store");
        HeadlessVkBackend gpu = new HeadlessVkBackend(false, 1L << 30, true, 2, neg(old, "store") ? HeadlessVkBackend.Features.GAME_NO_PACK
                : HeadlessVkBackend.Features.valueOf(System.getProperty("pfv.features", GPUAV ? "ALL" : "GAME")));
        List<String> leakNotes = new ArrayList<>();
        int exceptions = 0;
        int nonZero = 0;
        Run st = new Run(gpu, data, plan);
        st.oldBeginOrder = neg(old, "begin");
        try {
            st.enable();
            st.steps("world0", 160, 90, 3);
            Map<String, Integer> base = gpu.liveCounts();
            st.steps("world0", 200, 120, FRAMES_PER_STEP);
            st.steps("world0", 128, 72, FRAMES_PER_STEP);
            st.steps("world0", 160, 90, 3);
            compare(leakNotes, "after resizes 200x120, 128x72, back to 160x90", base, gpu.liveCounts());
            st.steps("world-1", 160, 90, FRAMES_PER_STEP);
            st.steps("world0", 160, 90, 3);
            compare(leakNotes, "after world-1 and back", base, gpu.liveCounts());
            st.reload();
            st.steps("world0", 160, 90, 3);
            compare(leakNotes, "after a pack reload", base, gpu.liveCounts());
            // SSBOs: Navidson's active options declare none; a one-pass SSBO pack (bufferObject.2) rebuilt at three sizes on the same
            // device: every executor close must free its storage buffers (they leaked per rebuild until the backend closed)
            ssboCycle(gpu, leakNotes);
            // shutdown. new: what Vulkan.cleanUp -> PackRuntime.shutdown does (disable: world forgotten, executor closed;
            // IrohVkBackend.close = destroyShared), scene images freed, then every count must be 0. old: nothing released
            // the pack at shutdown (the in-game state before PACK-FULL).
            gpu.releaseOnClose = false;
            if (!neg(old, "shutdown")) {
                st.disable();
                gpu.destroyShared();
                gpu.submitAndDrain();
                Map<String, Integer> after = gpu.liveCounts();
                for (int v : after.values())
                    if (v != 0)
                        nonZero++;
                if (nonZero > 0)
                    leakNotes.add("live after disable + release " + after);
            }
        } catch (Throwable t) {
            exceptions++;
            gpu.validationErrors.add("exception: " + t);
            t.printStackTrace(System.out);
        } finally {
            WorldPass.oldBindOrderForTest = WorldPass.noSamplePrepForTest = false;
            FrameExecutor.keepStorageBuffersForTest = false;
            VkPackBackend.skipStoreCheckForTest = false;
        }
        try {
            st.iroh.destroy();
            gpu.close();
        } catch (Throwable t) {
            exceptions++;
            gpu.validationErrors.add("exception at close: " + t);
        }
        Map<String, Integer> classes = new LinkedHashMap<>();
        List<String> samples = new ArrayList<>();
        int known = 0;
        boolean leak = false;
        synchronized (gpu.validationErrors) {
            for (String m : gpu.validationErrors) {
                if (m.contains("vkCmdBlitImage") && m.contains("B10G11R11")) { // RQ-V6-a (lavapipe; fixed by V6AB on int-4c)
                    known++;
                    continue;
                }
                String c = m.contains("NonWritable-06341") || m.contains("vertexPipelineStoresAndAtomics") ? "vertex-stores-06341"
                        : m.contains("inside an active") || m.contains("during subpass") || m.contains("renderpass") ? "inside-render-pass"
                        : m.contains("TRANSFER_DST_OPTIMAL") || m.contains("04150") || m.contains("09600") || m.contains("00344") ? "transfer-layout-descriptor"
                        : m.contains("leaked") || m.contains("has not been destroyed") ? "leak"
                        : m.contains(" hazard detected") ? "sync-hazard"
                        : m.startsWith("exception") ? "exception" : "other";
                if (c.equals("leak"))
                    leak = true;
                classes.merge(c, 1, Integer::sum);
                samples.add(c + ": " + m);
            }
        }
        return new Result(old, classes, samples, leakNotes, known, leak, nonZero, exceptions);
    }

    /** the game wiring the harness cannot run: Vulkan.cleanUp calls PackRuntime.shutdown and FrameProfiler.shutdown */
    static String shutdownWiring() throws Exception {
        try (var in = PackFullVkRepro.class.getClassLoader().getResourceAsStream("net/vulkanmod/vulkan/Vulkan.class")) {
            String b = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            boolean pack = b.contains("dev/elemore/iroh/shaderpack/runtime/PackRuntime"), prof = b.contains("dev/elemore/iroh/apiimpl/FrameProfiler");
            return pack && prof && b.contains("shutdown") ? null : "Vulkan.cleanUp does not call PackRuntime.shutdown / FrameProfiler.shutdown";
        }
    }

    static void ssboCycle(HeadlessVkBackend gpu, List<String> notes) throws Exception {
        // a one-pass compute pack with an SSBO (Navidson's active options declare none; t05's SSBO pass is shadowcomp,
        // which the synthetic post frame does not run). Written under build/ (src/test/packs is scanned by shaderHarness).
        Path src = Paths.get("build/packFullVkRepro/ssbo-pack");
        Files.createDirectories(src.resolve("shaders"));
        Files.writeString(src.resolve("shaders/shaders.properties"), "bufferObject.2 = 4096\n");
        Files.writeString(src.resolve("shaders/composite.csh"), "#version 430\nlayout(local_size_x = 16, local_size_y = 16) in;\n"
                + "const vec2 workGroupsRender = vec2(1.0, 1.0);\nlayout(rgba8) uniform image2D colorimg0;\n"
                + "layout(std430, binding = 2) buffer Lights { vec4 lights[]; } lightBuf;\n"
                + "void main() { ivec2 p = ivec2(gl_GlobalInvocationID.xy); imageStore(colorimg0, p, vec4(1.0) + lightBuf.lights[0]); }\n");
        Path dump = Paths.get("build/packFullVkRepro/ssbo-dump");
        FrontEnd.run(PackSource.open(src), new FrontEnd.Config(StandardMacros.Env.defaults(), null, true, dump));
        PackData d = PackData.load(dump);
        PackPlan p = PackPlan.build(d);
        String folder = p.frames.containsKey("<root>") ? "<root>" : p.frames.keySet().iterator().next();
        int before = gpu.liveCounts().get("storageBuffers");
        int[][] sizes = {{64, 36}, {80, 45}, {64, 36}};
        int peak = 0;
        for (int[] sz : sizes) {
            FrameExecutor ex = new FrameExecutor(d, p, folder, gpu, FrameExecutor.cap(1L << 30));
            ex.tracing = false;
            SyntheticScene sc = new SyntheticScene(d, p.frames.get(folder), p.resources.get(folder), sz[0], sz[1]);
            ex.frame(sc);
            peak = Math.max(peak, gpu.liveCounts().get("storageBuffers") - before);
            ex.close();
        }
        gpu.submitAndDrain();
        int after = gpu.liveCounts().get("storageBuffers") - before;
        if (peak == 0)
            notes.add("ssbo cycle: the SSBO pack created no storage buffer (harness blind)");
        if (after != 0)
            notes.add("ssbo cycle: storageBuffers +" + after + " after 3 executor rebuilds (SSBO pack, peak +" + peak + ")");
    }

    static void compare(List<String> notes, String when, Map<String, Integer> base, Map<String, Integer> now) {
        for (Map.Entry<String, Integer> e : base.entrySet()) {
            int n = now.getOrDefault(e.getKey(), 0);
            if (n > e.getValue())
                notes.add(when + ": " + e.getKey() + " " + e.getValue() + " -> " + n);
        }
    }

    /** the game side (PackRuntimeImpl + WorldPassGame + Iroh's renderer) for one device */
    static final class Run {
        final HeadlessVkBackend gpu;
        final PackData data;
        final PackPlan plan;
        final Iroh iroh;
        PackLifecycle lc;
        WorldPass world;
        Rimg colour, depth, drawTex;
        int sw = -1, sh = -1;
        VkPackBackend.HostBuf vbo;
        final Map<String, SyntheticFullScene> scenes = new LinkedHashMap<>();
        final Object skyKey = new Object(), guiKey = new Object(), entKey = new Object(), entTrKey = new Object();
        int frame;
        boolean oldBeginOrder;

        Run(HeadlessVkBackend gpu, PackData data, PackPlan plan) {
            this.gpu = gpu;
            this.data = data;
            this.plan = plan;
            this.iroh = new Iroh(gpu);
        }

        void enable() {
            lc = new PackLifecycle(gpu, (n, b) -> true);
            lc.tracing = false;
            lc.expectGeometry = true;
            lc.enable(data, plan);
        }

        void disable() {
            if (world != null) {
                world.abort();
                world.forgetTextures();
            }
            world = null;
            lc.disable();
            free(gpu, colour);
            free(gpu, depth);
            free(gpu, drawTex);
            colour = depth = drawTex = null;
            sw = sh = -1;
            if (vbo != null)
                gpu.freeHostBufferAfterFrame(vbo);
            vbo = null;
        }

        /** PackRuntime.reload: everything of the pack freed (game: a new IrohVkBackend; here the shared objects are destroyed) */
        void reload() {
            disable();
            gpu.submitAndDrain();
            gpu.destroyShared();
            gpu.initQuad();
            enable();
        }

        /** the renderer's scene target at w x h (a resize: new images, re-imported) + Iroh's pass over them */
        void scene(int w, int h) {
            if (w == sw && h == sh)
                return;
            free(gpu, colour);
            free(gpu, depth);
            String c0 = "R8G8B8A8_UNORM";
            for (dev.elemore.iroh.shaderpack.graph.ResourcePlan.Image im : plan.resources.get("world0").images)
                if (im.name.equals("colortex0"))
                    c0 = im.vkFormat; // copies, not blits, on lavapipe where it can (RQ-V6-a)
            colour = rimg(gpu, "scene colour", c0, w, h, Set.of("COLOR_ATTACHMENT", "SAMPLED", "TRANSFER_SRC", "TRANSFER_DST"));
            depth = rimg(gpu, "scene depth", "D32_SFLOAT", w, h, Set.of("DEPTH", "SAMPLED", "TRANSFER_SRC", "TRANSFER_DST"));
            iroh.target(gpu.img(colour.owner), gpu.img(depth.owner), w, h);
            sw = w;
            sh = h;
            if (drawTex == null)
                drawTex = rimg(gpu, "draw texture", "R8G8B8A8_UNORM", 16, 16, Set.of("SAMPLED", "TRANSFER_SRC", "TRANSFER_DST"));
            if (vbo == null) {
                vbo = gpu.hostBuffer(4096, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
                vbo.mapped().put(0, new byte[4096]);
            }
        }

        SyntheticFullScene sceneFor(String folder, int w, int h) {
            return scenes.computeIfAbsent(folder + w + "x" + h, k -> new SyntheticFullScene(data, plan.frames.get(folder), plan.resources.get(folder), w, h));
        }

        void steps(String folder, int w, int h, int frames) {
            scene(w, h);
            for (int i = 0; i < frames; i++)
                frame(folder, w, h);
        }

        /** the renderer's in-frame upload of a texture (VulkanImage: endPassRunResume, TRANSFER_DST after it) */
        void uploadDrawTexture() {
            iroh.outside(world, () -> {
                VkPackBackend.Img im = gpu.img(drawTex.owner);
                layoutBarrier(gpu, im, drawTex.layout[0], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                try (MemoryStack s = stackPush()) {
                    VkImageSubresourceRange.Buffer r = VkImageSubresourceRange.calloc(1, s);
                    r.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                    vkCmdClearColorImage(gpu.cmd(), im.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                            VkClearColorValue.calloc(s).float32(0, 1).float32(1, frame % 2).float32(2, 0).float32(3, 1), r);
                }
                drawTex.layout[0] = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            });
        }

        /** the renderer's draw after a successful substituted bind */
        void draw() {
            if (NO_DRAW)
                return;
            try (MemoryStack s = stackPush()) {
                vkCmdBindVertexBuffers(gpu.cmd(), 0, s.longs(vbo.buffer()), s.longs(0));
                vkCmdDraw(gpu.cmd(), 3, 1, 0, 0);
            }
        }

        boolean bind(Object key, String shader, List<String> el, boolean depthWrite, boolean blend) {
            return world.onBind(key, shader, el, WorldPass.TRIANGLE_LIST, true, depthWrite, false, blend, 6, 7, 1, 7, true);
        }

        void frame(String folder, int w, int h) {
            frame++;
            SyntheticFullScene sc = sceneFor(folder, w, h);
            Phased in = new Phased(sc);
            iroh.begin(); // the frame's main pass (Iroh: first RenderSystem.clear)
            // WorldRenderEvents.START (PackRuntimeImpl.worldBegin): lc.begin -> executor (rebuilt on a size / folder change),
            // world pass (re)created, segment 0. Fixed game order: all inside one endPassRunResume; old: lc.begin with
            // Iroh's pass bound (the in-game order before PACK-FULL)
            FrameExecutor[] exh = {null};
            boolean[] freshH = {false};
            Runnable start = () -> {
                FrameExecutor e = lc.begin(in, folder, colour.handle, Set.of());
                if (e == null)
                    throw new IllegalStateException("lifecycle refused: " + lc.failure);
                exh[0] = e;
                freshH[0] = world == null || world.executor() != e;
                if (freshH[0]) {
                    if (world != null)
                        world.forgetTextures();
                    world = new WorldPass(e, gpu, iroh, new Source(), Set.of(), true);
                }
                e.rasterOver = colour.handle;
            };
            if (oldBeginOrder) {
                start.run();
                iroh.outside(world, () -> lc.segment(() -> world.begin(in)));
            } else
                iroh.outside(world, () -> {
                    start.run();
                    lc.segment(() -> world.begin(in));
                });
            FrameExecutor ex = exh[0];
            boolean fresh = freshH[0];
            WorldPass wp = world;
            if (fresh)
                wp.warm(); // the game: outside endPassRunResume, Iroh's pass bound
            // sky substituted, then a raster draw (Iroh's pass resumed)
            if (bind(skyKey, "position", POS, false, false) && wp.beforeDraw())
                draw();
            bind(guiKey, "rendertype_gui", POS, false, false);
            iroh.outside(wp, () -> lc.segment(() -> {
                wp.afterSky();
                ex.clearExternal(colour.handle, new float[4]);
            }));
            in.geo = sc.geometry();
            in.version++;
            iroh.outside(wp, () -> lc.segment(() -> {
                wp.opaque(in);
                ex.blitOut("depthtex0", depth.handle);
            }));
            // a renderer texture uploaded this frame, then an entity drawn with it (substituted)
            uploadDrawTexture();
            if (bind(entKey, "rendertype_entity_cutout_no_cull", NEW_ENTITY, true, false) && wp.beforeDraw())
                draw();
            iroh.outside(wp, () -> lc.segment(() -> wp.translucent(in)));
            if (bind(entTrKey, "rendertype_entity_translucent", NEW_ENTITY, true, true) && wp.beforeDraw())
                draw();
            // END: composite .. present (frameEnd submits on the headless backend: Iroh's pass stays ended)
            wp.closeForForeign();
            boolean wasBound = iroh.bound;
            if (wasBound)
                iroh.end();
            lc.segment(() -> wp.end(in));
            if (lc.state() != PackLifecycle.State.ACTIVE)
                throw new IllegalStateException("lifecycle " + lc.state() + ": " + lc.failure);
            if (wp.failure() != null)
                throw new IllegalStateException("world pass failed: " + wp.failure());
            if (!ex.problems.isEmpty())
                throw new IllegalStateException("executor problems: " + ex.problems);
        }

        /** WorldPassGame's DrawSource: matrices, the draw texture (imported once) */
        final class Source implements WorldPass.DrawSource {
            public void modelView(double[] out) { for (int i = 0; i < 16; i++) out[i] = i % 5 == 0 ? 1 : 0; }
            public void projection(double[] out) { for (int i = 0; i < 16; i++) out[i] = i % 5 == 0 ? 1 : 0; out[14] = -0.1; }
            public Object texture() { return drawTex; }
            public long textureKey(Object t) { return ((Rimg) t).handle; }
            public int importTexture(Object t) { return ((Rimg) t).importAgain(gpu); }
            public void ids(Map<String, double[]> out) { out.get("entityId")[0] = 7; }
        }
    }
}
