package dev.elemore.iroh.shaderpack.runtime;

import dev.elemore.iroh.shaderpack.FrontEnd;
import dev.elemore.iroh.shaderpack.PackSource;
import dev.elemore.iroh.shaderpack.StandardMacros;
import dev.elemore.iroh.shaderpack.graph.PackData;
import dev.elemore.iroh.shaderpack.graph.PackPlan;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * V-5 (ladder run 10, int-2b-v8): pack path teardown from a NON-render thread, on lavapipe with the validation layer
 * (CPU only; HeadlessVkBackend refuses any GPU). Real code: Navidson through FrontEnd/PackPlan, PackLifecycle +
 * FrameExecutor + VkPackBackend (framebuffer cache, freeImage, destroyShared) on HeadlessVkBackend, RenderThreadCall.
 * <p>
 * A "render thread" R renders post-mode pack frames and drains a task queue between frames (Minecraft's runAllTasks);
 * a "control thread" C (Proctor's proctor-conn) switches the pack path off the way PackRuntimeImpl.disable does it at the
 * lifecycle/backend level: {@code lc.disable(); gpu.destroyShared()}. R mirrors PackRuntimeImpl.worldEnd's failure
 * path (state FAILED -> lc.disable()).
 * <ul>
 *   <li>old = C runs the disable itself (pre-V-5: DebugSwitches applied iroh.pack.enabled on the caller's thread)</li>
 *   <li>new = C goes through {@link RenderThreadCall} (V-5: PackRuntime.setEnabled/reload), R applies it between frames</li>
 * </ul>
 * Cases: "interleaved" (deterministic: C's disable lands while R is inside a frame, at a stage boundary), "random"
 * (N trials, C fires at a random moment of the frame), and "wiring" (PackRuntime.setEnabled/reload from C run on R).
 * V-6 (RQ-V5-a): sync hazards are counted too and must be 0 in new mode (they were 1 per device: the backend's
 * creation clear of the dummy shadow-depth image, then FrameExecutor's far-plane upload with no barrier between, since
 * the executor did not know about the clear). {@code -PoldCreate} = negative control: the pre-V-6 untracked creation
 * clear ({@link FrameExecutor#untrackedCreateForTest}), new mode only; last line "# packthread-oldcreate HAZARD n" with
 * n >= 1 expected.
 * <p>
 * Counted: use-after-free and other exceptions on R or C, validation errors (double vkDestroyFramebuffer etc.).
 * {@code ./gradlew packThreadRepro [-Pmode=old|new|both] [-Ptrials=N]}; both = old in a child JVM (a double free may abort
 * the process) then new here. Last line "# packthread PASS|FAIL ...": PASS = old shows the bug, new is clean.
 */
public final class PackThreadRepro {

    /** validation = every validation ERROR except sync hazards; hazards counted apart (the first-frame clear->upload WAW,
     *  RQ-V5-a, fixed by V-6: CLEAN now also needs 0 hazards) */
    record Result(int uaf, int otherExceptions, int validation, int doubleDestroy, int hazards, List<String> samples) {
        int bugs() {
            return uaf + otherExceptions + validation;
        }

        String line(String mode, String kase) {
            return "# packthread-" + mode + " " + kase + " uaf " + uaf + " exceptions " + otherExceptions + " validation " + validation
                    + " framebuffer-destroy " + doubleDestroy + " sync-hazards " + hazards;
        }
    }

    /** validation message kinds over the whole run (printed per mode) */
    static final java.util.Map<String, Integer> KINDS = new java.util.concurrent.ConcurrentSkipListMap<>();
    /** first sync hazard messages (V-6) */
    static final List<String> HAZARDS = Collections.synchronizedList(new ArrayList<>());
    static PackData data;
    static PackPlan plan;
    static final String FOLDER = "world0";

    public static void main(String[] args) throws Exception {
        String mode = System.getProperty("pt.mode", "both");
        int trials = Integer.getInteger("pt.trials", 40);
        if (Boolean.getBoolean("pt.oldCreate")) { // V-6 negative control
            FrameExecutor.untrackedCreateForTest = true;
            String r = runMode("new", trials);
            int hz = Integer.parseInt(r.replaceAll(".*sync hazards (\\d+).*", "$1"));
            System.out.println("# packthread-summary " + r);
            System.out.println("# packthread-oldcreate HAZARD " + hz + " (expected >= 1: pre-V-6 untracked creation clear)");
            System.exit(hz >= 1 ? 0 : 1);
        }
        if (mode.equals("both")) {
            String old = runChild("old", trials);
            String neu = runMode("new", trials);
            boolean oldBug = old.startsWith("BUG");
            boolean newClean = neu.startsWith("CLEAN"); // not "NOT-CLEAN"
            System.out.println("# old: " + old);
            System.out.println("# new: " + neu);
            System.out.println("# packthread " + (oldBug && newClean ? "PASS" : "FAIL") + " (old " + (oldBug ? "shows the bug" : "did NOT show the bug")
                    + ", new " + (newClean ? "clean" : "NOT clean") + ")");
            System.exit(oldBug && newClean ? 0 : 1);
        }
        String r = runMode(mode, trials);
        System.out.println("# packthread-summary " + r);
        System.exit(0);
    }

    /** old mode in a child JVM: a real double free can abort the process; that counts as the bug showing */
    static String runChild(String mode, int trials) throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        List<String> cmd = new ArrayList<>(List.of(java, "-Xmx3g", "-Djava.awt.headless=true", "-Dpt.mode=" + mode, "-Dpt.trials=" + trials,
                "-cp", System.getProperty("java.class.path"), PackThreadRepro.class.getName()));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String summary = null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String l; (l = br.readLine()) != null; ) {
                System.out.println("  [" + mode + "] " + l);
                if (l.startsWith("# packthread-summary "))
                    summary = l.substring("# packthread-summary ".length());
            }
        }
        int rc = p.waitFor();
        if (summary == null)
            return "BUG (child JVM died rc " + rc + " before its summary: native crash in the teardown race)";
        return summary;
    }

    static String runMode(String mode, int trials) throws Exception {
        load();
        boolean old = mode.equals("old");
        List<Result> rs = new ArrayList<>();
        Result inter = interleaved(old);
        System.out.println(inter.line(mode, "interleaved"));
        inter.samples().stream().limit(3).forEach(s -> System.out.println("    " + s));
        rs.add(inter);
        Random rnd = new Random(0x5eed + (old ? 1 : 2));
        int uaf = 0, exc = 0, val = 0, dd = 0, hz = 0;
        List<String> samples = new ArrayList<>();
        for (int t = 0; t < trials; t++) {
            Result r = randomTrial(old, rnd);
            uaf += r.uaf();
            exc += r.otherExceptions();
            val += r.validation();
            dd += r.doubleDestroy();
            hz += r.hazards();
            if (samples.size() < 4)
                samples.addAll(r.samples().subList(0, Math.min(2, r.samples().size())));
        }
        Result rand = new Result(uaf, exc, val, dd, hz, samples);
        System.out.println(rand.line(mode, "random x" + trials));
        samples.stream().limit(4).forEach(s -> System.out.println("    " + s));
        rs.add(rand);
        KINDS.forEach((k, n) -> System.out.println("  validation kind x" + n + ": " + k.replace('\n', ' ')));
        HAZARDS.stream().limit(2).forEach(h -> System.out.println("  sync hazard: " + (h.length() > 300 ? h.substring(0, 300) : h).replace('\n', ' ')));
        boolean wiringOk = old || wiring();
        int bugs = rs.stream().mapToInt(Result::bugs).sum();
        int hazards = hz + inter.hazards();
        int dbg = SyncPlan.DEBUG_VIOLATIONS_ALL.get(); // B-3B: the compiled schedule vs the old tracker + SyncCheck (debug mode)
        SyncPlan.DEBUG_SAMPLE.stream().limit(3).forEach(x -> System.out.println("  sync debug: " + x));
        String verdict = old ? (bugs > 0 ? "BUG" : "no-bug") : (bugs == 0 && hazards == 0 && wiringOk && dbg == 0 ? "CLEAN" : "NOT-CLEAN");
        return verdict + " " + mode + ": interleaved uaf " + inter.uaf() + " exc " + inter.otherExceptions() + " val " + inter.validation()
                + "; random uaf " + uaf + " exc " + exc + " val " + val + " (framebuffer destroy errors " + dd + "); sync hazards " + hazards
                + "; sync debug violations " + dbg
                + (old ? "" : "; wiring " + (wiringOk ? "ok" : "BROKEN"));
    }

    static void load() throws Exception {
        if (data != null)
            return;
        Path pack = Paths.get(System.getProperty("pt.pack", System.getProperty("user.home") + "/.local/share/atlauncher/instances/Vulkan/shaderpacks/Navidson"));
        Path optFile = pack.resolveSibling(pack.getFileName() + ".txt");
        String saved = Files.isRegularFile(optFile) ? Files.readString(optFile, StandardCharsets.UTF_8) : null;
        Path dump = Paths.get("build/packThreadRepro/_dump");
        FrontEnd.PackResult fr = FrontEnd.run(PackSource.open(pack), new FrontEnd.Config(StandardMacros.Env.defaults(), saved, true, dump));
        if (!fr.packErrors.isEmpty())
            throw new IllegalStateException("front end refused the pack: " + fr.packErrors);
        data = PackData.load(dump);
        plan = PackPlan.build(data);
    }

    /** the render thread: frames + a task queue drained between frames (Minecraft.runAllTasks) */
    static final class Render implements Runnable {
        final HeadlessVkBackend gpu;
        final PackLifecycle lc;
        final PackFrameInputs in;
        final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
        final List<String> errors = Collections.synchronizedList(new ArrayList<>());
        final Thread thread = new Thread(this, "repro-render");
        volatile boolean stop;
        volatile int frames;
        volatile java.util.function.Consumer<String> onStage;
        volatile long frameNs;
        /** set when a thread other than R frees a pack image (C's teardown has begun) */
        volatile boolean controlFreeing;

        Render(HeadlessVkBackend gpu, PackFrameInputs in) {
            this.gpu = gpu;
            this.in = in;
            lc = new PackLifecycle(slowFrees(gpu), FrameExecutor.cap(1L << 30));
            lc.tracing = false;
            lc.enable(data, plan);
        }

        public void run() {
            try {
                while (!stop) {
                    for (Runnable t; (t = tasks.poll()) != null; )
                        t.run();
                    if (lc.state() != PackLifecycle.State.ACTIVE) {
                        Runnable t = tasks.poll(5, TimeUnit.MILLISECONDS);
                        if (t != null)
                            t.run();
                        continue;
                    }
                    long t0 = System.nanoTime();
                    if (lc.executor() != null)
                        lc.executor().onStage = onStage;
                    lc.frame(in, FOLDER, -1, Set.of()); // FrameExecutor.frame does gpu.beginFrame .. gpu.endFrame
                    frameNs = System.nanoTime() - t0;
                    frames++;
                    if (lc.state() == PackLifecycle.State.FAILED) { // PackRuntimeImpl.worldEnd -> fail(lc.failure) -> lc.disable()
                        errors.add("R: pack path OFF: " + lc.failure);
                        lc.disable();
                    }
                }
            } catch (Throwable t) {
                errors.add("R: " + t);
            }
        }

        /**
         * The real backend behind the GpuBackend interface, with freeImage slowed by {@link #FREE_DELAY_MS}: in game a
         * pack image free goes through VulkanImage.FREE / ImageTrace / MemoryManager and is far slower than lavapipe's
         * list append, and that teardown time is the race window. Nothing else is changed; both modes get it.
         */
        GpuBackend slowFrees(HeadlessVkBackend real) {
            return (GpuBackend) java.lang.reflect.Proxy.newProxyInstance(GpuBackend.class.getClassLoader(), new Class<?>[]{GpuBackend.class}, (proxy, m, a) -> {
                boolean slow = false;
                if (m.getName().equals("freeImage")) {
                    if (Thread.currentThread() != thread)
                        controlFreeing = true;
                    slow = !real.imgs.get((Integer) a[0]).freed; // only a real free is slow (the in-game release work), a skip is not
                }
                try {
                    return m.invoke(real, a);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                } finally {
                    if (slow)
                        Thread.sleep(FREE_DELAY_MS); // after the flag and the framebuffer loop, like releaseImage in game
                }
            });
        }
    }

    static final long FREE_DELAY_MS = Long.getLong("pt.freeDelayMs", 1);

    /** PackRuntimeImpl.disable at the lifecycle/backend level (in game: lc.disable(); gpu.close() = destroyShared + query pool) */
    static void disable(Render r) {
        r.lc.disable();
        r.gpu.destroyShared();
    }

    static void control(Render r, boolean old, List<String> errors) {
        try {
            if (old)
                disable(r);
            else
                RenderThreadCall.call(() -> {
                    disable(r);
                    return "ok";
                }, 10_000);
        } catch (Throwable t) {
            errors.add("C: " + t);
        }
    }

    static void install(Render r) {
        RenderThreadCall.testOnRenderThread = () -> Thread.currentThread() == r.thread;
        RenderThreadCall.testExecutor = r.tasks::add;
    }

    static SyntheticScene scene() {
        return new SyntheticScene(data, plan.frames.get(FOLDER), plan.resources.get(FOLDER), 160, 90);
    }

    static Result finish(Render r, List<String> cErrors) throws InterruptedException {
        long until = System.nanoTime() + 60_000_000_000L;
        while (r.lc.state() == PackLifecycle.State.ACTIVE && System.nanoTime() < until)
            Thread.sleep(2);
        r.stop = true;
        r.thread.join(120_000);
        r.gpu.close(); // runs every deferred release (the double destroy shows here) and destroys the device
        List<String> all = new ArrayList<>(r.errors);
        all.addAll(cErrors);
        int uaf = 0, exc = 0;
        for (String e : all)
            if (e.contains("use after free"))
                uaf++;
            else
                exc++;
        List<String> v = new ArrayList<>(r.gpu.validationErrors);
        int hz = (int) v.stream().filter(s -> s.contains(" hazard detected")).count();
        v.stream().filter(s -> s.contains(" hazard detected")).limit(2).forEach(HAZARDS::add);
        v.removeIf(s -> s.contains(" hazard detected"));
        int dd = (int) v.stream().filter(s -> s.contains("vkDestroyFramebuffer")).count();
        for (String m : v) { // kinds: the message up to its first period (handles differ per run)
            String k = m.replaceAll("0x[0-9a-f]+", "#");
            int dot = k.indexOf(". ");
            KINDS.merge(dot > 0 ? k.substring(0, Math.min(dot, 140)) : k.substring(0, Math.min(k.length(), 140)), 1, Integer::sum);
        }
        List<String> samples = new ArrayList<>();
        all.forEach(s -> samples.add(s.length() > 200 ? s.substring(0, 200) : s));
        v.forEach(s -> samples.add("validation: " + (s.length() > 200 ? s.substring(0, 200) : s)));
        return new Result(uaf, exc, v.size(), dd, hz, samples);
    }

    /** deterministic: C's switch arrives while R is inside a frame (entering "composite"); R carries on once C's teardown runs */
    static Result interleaved(boolean old) throws Exception {
        HeadlessVkBackend gpu = new HeadlessVkBackend(false, 1L << 30, true, Integer.getInteger("iroh.replay.inflight", 2));
        Render r = new Render(gpu, scene());
        install(r);
        List<String> cErrors = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch go = new CountDownLatch(1);
        Thread c = new Thread(() -> {
            try {
                go.await();
            } catch (InterruptedException e) {
                return;
            }
            control(r, old, cErrors);
        }, "repro-control");
        c.start();
        r.thread.start();
        while (r.frames < 1 && r.thread.isAlive())
            Thread.sleep(2);
        boolean[] fired = {false};
        r.onStage = stage -> {
            if (fired[0] || !stage.startsWith("composite"))
                return;
            fired[0] = true;
            go.countDown();
            // old: R goes on as soon as C's teardown has begun (both run at once from here, as in game);
            // new: C's switch is queued behind this frame, so R gives up waiting after 500 ms and finishes the frame
            long until = System.nanoTime() + 500_000_000L;
            while (!r.controlFreeing && System.nanoTime() < until)
                Thread.onSpinWait();
        };
        go.await(120, TimeUnit.SECONDS);
        c.join(120_000);
        return finish(r, cErrors);
    }

    /** one trial: C fires at a random moment of a frame (after 2 warm frames) */
    static Result randomTrial(boolean old, Random rnd) throws Exception {
        HeadlessVkBackend gpu = new HeadlessVkBackend(false, 1L << 30, true, Integer.getInteger("iroh.replay.inflight", 2));
        Render r = new Render(gpu, scene());
        install(r);
        List<String> cErrors = Collections.synchronizedList(new ArrayList<>());
        r.thread.start();
        while (r.frames < 2 && r.thread.isAlive())
            Thread.sleep(1);
        long frameNs = Math.max(1_000_000L, r.frameNs);
        long delay = (long) (rnd.nextDouble() * frameNs);
        Thread.sleep(delay / 1_000_000L, (int) (delay % 1_000_000L));
        Thread c = new Thread(() -> control(r, old, cErrors), "repro-control");
        c.start();
        c.join(120_000);
        return finish(r, cErrors);
    }

    /** the production entry points: PackRuntime.setEnabled(false) / reload() from C must run ON R */
    static boolean wiring() throws Exception {
        HeadlessVkBackend gpu = new HeadlessVkBackend(false, 1L << 30, true, Integer.getInteger("iroh.replay.inflight", 2));
        Render r = new Render(gpu, scene());
        r.lc.disable(); // nothing to render: R only drains tasks
        int[] onR = {0};
        RenderThreadCall.testOnRenderThread = () -> Thread.currentThread() == r.thread;
        RenderThreadCall.testExecutor = task -> r.tasks.add(() -> {
            if (Thread.currentThread() == r.thread)
                onR[0]++;
            task.run();
        });
        r.thread.start();
        String[] res = new String[2];
        Thread c = new Thread(() -> {
            res[0] = PackRuntime.setEnabled(false);
            res[1] = PackRuntime.reload();
        }, "repro-control");
        c.start();
        c.join(30_000);
        r.stop = true;
        r.thread.join(30_000);
        gpu.close();
        boolean ok = onR[0] == 2 && "ok".equals(res[0]) && res[1] != null && res[1].startsWith("ok");
        System.out.println("# packthread-wiring PackRuntime.setEnabled(false) -> " + res[0] + ", reload() -> " + res[1] + ", tasks run on the render thread "
                + onR[0] + "/2 " + (ok ? "ok" : "BROKEN"));
        return ok;
    }
}
