package dev.elemore.iroh.shaderpack.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;

/**
 * V-7 (review 698ba7c finding 1): pack frames with MORE THAN ONE FRAME IN FLIGHT, on lavapipe with the validation layer
 * and synchronization validation (CPU only). Real code: Navidson world0 through FrontEnd/PackPlan, FrameExecutor and
 * VkPackBackend on {@link HeadlessVkBackend} in its in-game mode ({@code inFlight = 2}: one command buffer per frame
 * slot, prepare's setup in the first frame's buffer, submit without waiting, the slot fence waited only when the slot
 * records again: IrohVkBackend on VulkanMod's Renderer). Sync validation checks each submission against earlier
 * submissions on the queue that are not known complete, so a pack access in frame N+1 with no barrier against frame
 * N's last access of the same image is reported at vkQueueSubmit.
 * <p>
 * Cases (each 5 frames at 160x90):
 * <ul>
 *   <li>synthetic: SyntheticScene's supplied images uploaded each frame (packThreadRepro's inputs), no present</li>
 *   <li>post: the game's default post mode: scene colour/depth IMPORTED (layout tracked like VulkanImage), blitted in,
 *       flat clears, output blitted back into the scene colour (iroh.pack.present on)</li>
 *   <li>post-nopresent: the same with iroh.pack.present off (the end-of-frame alt -> main copies are the last commands)</li>
 * </ul>
 * Modes: old = FrameExecutor forgets every access at the end of a frame (pre-V-7, {@link FrameExecutor#forgetAtFrameEndForTest});
 * new = V-7. Serial (inFlight 1, HeadlessVkBackend's submit-and-wait) is run once for the old mode to show why
 * packThreadRepro could not see this. Barriers per frame are counted at the GpuBackend interface.
 * {@code ./gradlew packFlightRepro [-Pmode=old|new|both]}; last line "# packflight PASS|FAIL ..." (PASS = old > 0 hazards
 * with frames in flight, new 0 in every case, no other validation error).
 */
public final class PackFlightRepro {

    static final int W = 160, H = 90, FRAMES = 5;
    static final boolean TRACE = Boolean.getBoolean("pf.trace");

    record Result(String kase, int inFlight, boolean old, int hazards, int otherErrors, int known, int[] barriers, List<String> samples) {
        String line() {
            return "# packflight-" + (old ? "old" : "new") + " " + kase + " inFlight " + inFlight + " sync-hazards " + hazards
                    + " other-validation " + otherErrors + (known > 0 ? " (+" + known + " known RQ-V6-a blit-dst)" : "") + " barriers/frame "
                    + java.util.Arrays.toString(barriers);
        }
    }

    record Pack(String name, dev.elemore.iroh.shaderpack.graph.PackData data, dev.elemore.iroh.shaderpack.graph.PackPlan plan, String folder) {}

    /**
     * a minimal pack of a common real-pack shape: colortex0 never cleared and ping-ponged by composite, so the end of
     * every frame copies colortex0 alt -> main, and the next frame's first write of colortex0 (the game's scene blit in
     * post mode, the supplied upload offline) lands on main with nothing in between when the copy is the frame's last
     * command (iroh.pack.present off). Written under build/ (not src/test/packs: the shader harness scans those).
     */
    static Pack flightPack() throws Exception {
        java.nio.file.Path root = java.nio.file.Paths.get("build/packFlightRepro/flight");
        java.nio.file.Path sh = root.resolve("shaders");
        java.nio.file.Files.createDirectories(sh);
        String vsh = "#version 430 compatibility\nout vec2 uv;\nvoid main() { gl_Position = ftransform(); uv = gl_MultiTexCoord0.xy; }\n";
        java.nio.file.Files.writeString(sh.resolve("composite.vsh"), vsh);
        java.nio.file.Files.writeString(sh.resolve("final.vsh"), vsh);
        java.nio.file.Files.writeString(sh.resolve("composite.fsh"), "#version 430 compatibility\nconst bool colortex0Clear = false;\nin vec2 uv;\n"
                + "uniform sampler2D colortex0;\n/* RENDERTARGETS: 0 */\nlayout(location = 0) out vec4 outA;\n"
                + "void main() { outA = texture(colortex0, uv) * 0.5 + vec4(0.25); }\n");
        java.nio.file.Files.writeString(sh.resolve("final.fsh"), "#version 430 compatibility\nin vec2 uv;\nuniform sampler2D colortex0;\n"
                + "void main() { gl_FragColor = texture(colortex0, uv); }\n");
        java.nio.file.Path dump = java.nio.file.Paths.get("build/packFlightRepro/_dump-flight");
        dev.elemore.iroh.shaderpack.FrontEnd.PackResult fr = dev.elemore.iroh.shaderpack.FrontEnd.run(dev.elemore.iroh.shaderpack.PackSource.open(root),
                new dev.elemore.iroh.shaderpack.FrontEnd.Config(dev.elemore.iroh.shaderpack.StandardMacros.Env.defaults(), null, true, dump));
        if (!fr.packErrors.isEmpty())
            throw new IllegalStateException("front end refused the flight pack: " + fr.packErrors);
        var data = dev.elemore.iroh.shaderpack.graph.PackData.load(dump);
        var plan = dev.elemore.iroh.shaderpack.graph.PackPlan.build(data);
        return new Pack("flight", data, plan, plan.frames.keySet().iterator().next());
    }

    public static void main(String[] args) throws Exception {
        String mode = System.getProperty("pf.mode", "both");
        PackThreadRepro.load();
        List<Pack> packs = List.of(new Pack("navidson", PackThreadRepro.data, PackThreadRepro.plan, PackThreadRepro.FOLDER), flightPack());
        List<Result> rs = new ArrayList<>();
        for (String m : mode.equals("both") ? List.of("old", "new") : List.of(mode)) {
            boolean old = m.equals("old");
            for (Pack p : packs) {
                for (String kase : List.of("synthetic", "post", "post-nopresent"))
                    rs.add(run(p, kase, 2, old));
                if (old)
                    rs.add(run(p, "post-nopresent", 1, true));
            }
        }
        // the same check without Vulkan: RecordingBackend's frames-in-flight model (also run by packExecTest on every pack)
        int[] model = new int[2];
        for (Pack p : packs)
            for (int o = 0; o < 2; o++) {
                if (!mode.equals("both") && (o == 0) != mode.equals("old"))
                    continue;
                FrameExecutor.forgetAtFrameEndForTest = o == 0;
                RecordingBackend rec = new RecordingBackend();
                rec.framesInFlight = true;
                FrameExecutor ex = new FrameExecutor(p.data(), p.plan(), p.folder(), rec, FrameExecutor.cap(1L << 30));
                SyntheticScene sc = new SyntheticScene(p.data(), p.plan().frames.get(p.folder()), p.plan().resources.get(p.folder()), 64, 36);
                for (int f = 0; f < FRAMES; f++)
                    ex.frame(sc);
                FrameExecutor.forgetAtFrameEndForTest = false;
                model[o] += rec.violations.size();
                System.out.println("# packflight-model-" + (o == 0 ? "old" : "new") + " " + p.name() + " violations " + rec.violations.size()
                        + (rec.violations.isEmpty() ? "" : " e.g. " + rec.violations.get(0)));
            }
        int ctl = positiveControl();
        System.out.println("# packflight-control cross-submission clear->clear with no barrier, 2 in flight: sync-hazards " + ctl
                + (ctl > 0 ? " (sync validation sees hazards across submissions)" : " (BLIND: sync validation did not flag it)"));
        for (Result r : rs) {
            System.out.println(r.line());
            r.samples().stream().limit(2).forEach(s -> System.out.println("    " + (s.length() > 400 ? s.substring(0, 400) : s).replace('\n', ' ')));
        }
        int oldHz = rs.stream().filter(r -> r.old() && r.inFlight() > 1).mapToInt(Result::hazards).sum();
        int newHz = rs.stream().filter(r -> !r.old()).mapToInt(Result::hazards).sum();
        int other = rs.stream().mapToInt(Result::otherErrors).sum();
        boolean haveOld = rs.stream().anyMatch(Result::old), haveNew = rs.stream().anyMatch(r -> !r.old());
        boolean pass = ctl > 0 && (!haveOld || oldHz > 0 && model[0] > 0) && (!haveNew || newHz == 0 && model[1] == 0) && other == 0;
        System.out.println("# packflight " + (pass ? "PASS" : "FAIL") + " (old in-flight hazards " + (haveOld ? oldHz : "-") + ", new hazards "
                + (haveNew ? newHz : "-") + ", other validation errors " + other + ")");
        System.exit(pass ? 0 : 1);
    }

    static Result run(Pack pack, String kase, int inFlight, boolean old) {
        FrameExecutor.forgetAtFrameEndForTest = old;
        HeadlessVkBackend gpu = new HeadlessVkBackend(false, 1L << 30, true, inFlight);
        int[] perFrame = new int[FRAMES];
        int[] frameNo = {-1};
        GpuBackend counted = (GpuBackend) java.lang.reflect.Proxy.newProxyInstance(GpuBackend.class.getClassLoader(), new Class<?>[]{GpuBackend.class},
                (proxy, m, a) -> {
                    if (m.getName().equals("barrier") && frameNo[0] >= 0)
                        perFrame[frameNo[0]]++;
                    if (TRACE && frameNo[0] >= 3 && frameNo[0] <= 4 && !m.getName().equals("desc") && !m.getName().startsWith("time"))
                        System.out.println("  call f" + frameNo[0] + " " + m.getName() + " " + (a == null ? "" : java.util.Arrays.stream(a)
                                .map(o -> o instanceof GpuBackend.Draw d ? "draw " + d.pass() + " colour " + java.util.Arrays.toString(d.colour())
                                        + " set0 " + d.set0().values().stream().map(x -> x.image() + "").toList() : String.valueOf(o)).toList()));
                    try {
                        return m.invoke(gpu, a);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        List<String> samples = new ArrayList<>();
        try {
            FrameExecutor ex = new FrameExecutor(pack.data(), pack.plan(), pack.folder(), counted, FrameExecutor.cap(1L << 30));
            ex.tracing = false;
            SyntheticScene syn = new SyntheticScene(pack.data(), pack.plan().frames.get(pack.folder()), pack.plan().resources.get(pack.folder()), W, H);
            PackFrameInputs in = syn;
            if (kase.startsWith("post")) {
                int sc = sceneImage(gpu, "scene colour", "R8G8B8A8_UNORM");
                int sd = sceneImage(gpu, "scene depth", "D32_SFLOAT");
                if (kase.equals("post"))
                    ex.presentTo = sc;
                Map<String, Integer> copies = new LinkedHashMap<>();
                copies.put("colortex0", sc);
                copies.put("depthtex0", sd);
                copies.put("depthtex1", sd);
                copies.put("depthtex2", sd);
                Map<String, float[]> clears = new LinkedHashMap<>();
                clears.put("colortex6", new float[]{0, 0, 1, 0});
                clears.put("colortex4", new float[]{0, 1, 0, 0});
                clears.put("shadowtex0", new float[]{1, 1, 1, 1});
                clears.put("shadowtex1", new float[]{1, 1, 1, 1});
                in = new PackFrameInputs() {
                    public int width() { return syn.width(); }
                    public int height() { return syn.height(); }
                    public Map<String, double[]> uniforms() { return syn.uniforms(); }
                    public Set<String> suppliedImages() { return Set.of(); }
                    public float[] image(String n) { return null; }
                    public int[] extent(String n) { return null; }
                    public String startStage() { return "deferred"; }
                    public double frameSeconds() { return syn.frameSeconds(); }
                    public Map<String, Integer> copySources() { return copies; }
                    public Map<String, float[]> clearValues() { return clears; }
                };
            }
            for (int f = 0; f < FRAMES; f++) {
                frameNo[0] = f;
                ex.frame(in);
            }
            frameNo[0] = -1;
            ex.close();
        } catch (Throwable t) {
            samples.add("exception: " + t);
        } finally {
            FrameExecutor.forgetAtFrameEndForTest = false;
        }
        gpu.close();
        List<String> v = new ArrayList<>(gpu.validationErrors);
        List<String> hz = v.stream().filter(s -> s.contains("hazard")).toList();
        v.removeAll(hz);
        // RQ-V6-a (pre-existing, not this card): lavapipe has no BLIT_DST for B10G11R11 (Navidson colortex0) = VUID 02000
        List<String> known = v.stream().filter(s -> s.contains("vkCmdBlitImage") && s.contains("B10G11R11")).toList();
        v.removeAll(known);
        samples.addAll(hz);
        samples.addAll(v);
        int exc = (int) samples.stream().filter(s -> s.startsWith("exception")).count();
        return new Result(pack.name() + ":" + kase, inFlight, old, hz.size(), v.size() + exc, known.size(), perFrame, samples);
    }

    /** the harness can see a cross-frame hazard at all: frame A clears an image, frame B clears it again with no barrier */
    static int positiveControl() {
        HeadlessVkBackend gpu = new HeadlessVkBackend(false, 1L << 30, true, 2);
        int h = gpu.createImage(new GpuBackend.ImageDesc("control", "R8G8B8A8_UNORM", W, H, 1, 1, Set.of("SAMPLED", "TRANSFER_DST")));
        gpu.barrier(h, "TRANSFER_WRITE", "TRANSFER_WRITE");
        for (int f = 0; f < 2; f++) {
            gpu.beginFrame();
            gpu.clear(h, new float[]{f, 0, 0, 1});
            gpu.endFrame();
        }
        gpu.close();
        return (int) gpu.validationErrors.stream().filter(s -> s.contains("hazard")).count();
    }

    /**
     * a renderer-owned stand-in (in game: SceneTarget's VulkanImage, imported): made on the device (zeroed, the barrier
     * after its creation clear recorded here), then registered as an import whose layout the backend tracks
     */
    static int sceneImage(HeadlessVkBackend gpu, String name, String fmt) {
        int own = gpu.createImage(new GpuBackend.ImageDesc(name + " (owner)", fmt, W, H, 1, 1,
                Set.of(fmt.startsWith("D") ? "DEPTH" : "COLOR_ATTACHMENT", "SAMPLED", "TRANSFER_SRC", "TRANSFER_DST")));
        gpu.barrier(own, "TRANSFER_WRITE", "TRANSFER_WRITE");
        VkPackBackend.Img im = gpu.img(own);
        int[] layout = {VK_IMAGE_LAYOUT_GENERAL};
        return gpu.importRaw(new GpuBackend.ImageDesc(name, fmt, W, H, 1, 1, Set.of("TRANSFER_SRC", "TRANSFER_DST")), im.image, im.viewAll, im.format,
                new VkPackBackend.ImportedLayout() {
                    public int get() { return layout[0]; }
                    public void set(int l) { layout[0] = l; }
                });
    }
}
