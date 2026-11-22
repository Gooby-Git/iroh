package dev.elemore.iroh.diag;

import dev.elemore.iroh.diag.SlowFrames.Counter;
import dev.elemore.iroh.diag.SlowFrames.Phase;

import java.util.ArrayList;
import java.util.List;

/**
 * perf/frame-attrib: ring, record, self-time and summary logic of {@link SlowFrames} on a fake clock.
 * {@code ./gradlew slowFramesTest}; last line "# slowframes PASS n/n".
 */
public final class SlowFramesTest {
    private static int pass, fail;
    private static long t, cpu, alloc;
    private static final List<String> out = new ArrayList<>();

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    static void ms(double d) {
        t += (long) (d * 1_000_000L);
    }

    /** A packet handler task: the lambda captures the packet. */
    static final class ChunkDataPacket {}

    static final class FakePacketTask implements Runnable {
        final Object packet = new ChunkDataPacket();
        @Override public void run() {}
    }

    static String last() {
        return out.isEmpty() ? "" : out.get(out.size() - 1);
    }

    public static void main(String[] args) throws Exception {
        SlowFrames.CLOCK = () -> t;
        SlowFrames.CPU = () -> cpu;
        SlowFrames.ALLOC = () -> alloc;
        SlowFrames.SINK = out::add;
        SlowFrames.reset();
        SlowFrames.testThresholdNs = 12_000_000L;
        t = 1_000_000_000L;

        check("parseMs: 12 -> 12 ms, junk/negative/empty -> off", SlowFrames.parseMs("12") == 12_000_000L
                && SlowFrames.parseMs("x") == 0 && SlowFrames.parseMs("-3") == 0 && SlowFrames.parseMs(null) == 0 && SlowFrames.parseMs("0.5") == 500_000L);

        // 1. a fast frame logs nothing
        SlowFrames.frameStart();
        SlowFrames.enter(Phase.RENDER); ms(4); SlowFrames.exit(Phase.RENDER);
        ms(1);
        SlowFrames.frameEnd();
        check("5 ms frame under a 12 ms threshold: no line", out.isEmpty());

        // 2. a slow frame: tasks 25 (one 20 ms chunk packet), render 10 with terrain 6 nested, 5 ms unaccounted
        SlowFrames.frameStart();
        cpu += 0; alloc += 0;
        SlowFrames.set(Counter.BUILDERS_BUSY, 2);
        SlowFrames.enter(Phase.TASKS);
        ms(5);
        SlowFrames.task(new FakePacketTask(), 20_000_000L);
        SlowFrames.task(new Object(), 1_000_000L);
        ms(20);
        SlowFrames.exit(Phase.TASKS);
        SlowFrames.enter(Phase.RENDER);
        ms(2);
        SlowFrames.enter(Phase.TERRAIN_DRAW); ms(6); SlowFrames.exit(Phase.TERRAIN_DRAW);
        SlowFrames.count(Counter.LAYERS, 3);
        ms(2);
        SlowFrames.exit(Phase.RENDER);
        ms(5);
        cpu += 30_000_000L;
        alloc += 4096L * 1024;
        SlowFrames.frameEnd();
        String l = last();
        System.out.println("     " + l);
        check("slow frame logged once", out.size() == 1 && l.startsWith("[iroh-slow] frame 2 40.0 ms:"));
        check("three largest phases with self ms (tasks 25, terrain 6, render self 4)", l.contains("tasks 25.0 terrain draw 6.0 render other 4.0"));
        check("unaccounted rest = wall - sum of self times (5.0)", l.contains("unaccounted 5.0"));
        check("cpu vs wall and allocation", l.contains("cpu 30.0 of wall 40.0") && l.contains("alloc 4096 KB"));
        check("counters (tasks=2, layers=3, buildersBusy=2)", l.contains("tasks=2") && l.contains("layers=3") && l.contains("buildersBusy=2"));
        check("slowest task named by its packet", l.contains("slowest task ChunkDataPacket (packet) 20.0"));

        // 3. self times add up exactly
        int r = SlowFrames.head;
        long sum = 0;
        for (long v : SlowFrames.selfNs[r]) sum += v;
        check("record: self times 35 ms of 40 ms wall", sum == 35_000_000L && SlowFrames.wallNs[r] == 40_000_000L);

        // 4. phase left open by an exception: closed at frame end; exit of a phase never entered is ignored;
        //    nested runTick belongs to the outer frame; other threads are ignored
        SlowFrames.frameStart();
        SlowFrames.exit(Phase.GUI); // never entered
        SlowFrames.enter(Phase.RENDER);
        SlowFrames.enter(Phase.ENTITIES);
        ms(3);
        SlowFrames.frameStart(); // nested runTick
        ms(2);
        SlowFrames.frameEnd();
        Thread other = new Thread(() -> SlowFrames.enter(Phase.TICK));
        other.start();
        other.join();
        ms(15);
        // RENDER and ENTITIES never exited (exception)
        SlowFrames.frameEnd();
        r = SlowFrames.head;
        check("open phases closed at frame end (entities 20 ms self)", SlowFrames.selfNs[r][Phase.ENTITIES.ordinal()] == 20_000_000L
                && SlowFrames.selfNs[r][Phase.RENDER.ordinal()] == 0 && SlowFrames.selfNs[r][Phase.TICK.ordinal()] == 0);
        check("nested runTick did not end the frame (one record, 20 ms)", SlowFrames.wallNs[r] == 20_000_000L && SlowFrames.frames == 3);
        check("exit of a phase never entered: ignored (gui 0)", SlowFrames.selfNs[r][Phase.GUI.ordinal()] == 0);

        // 5. ring wraps without allocating per frame
        com.sun.management.ThreadMXBean tmx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        for (int i = 0; i < 2000; i++) { SlowFrames.frameStart(); SlowFrames.enter(Phase.RENDER); ms(1); SlowFrames.exit(Phase.RENDER); SlowFrames.frameEnd(); }
        long a0 = tmx.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 20_000; i++) {
            SlowFrames.frameStart();
            SlowFrames.enter(Phase.RENDER);
            SlowFrames.enter(Phase.TERRAIN_DRAW);
            ms(1);
            SlowFrames.count(Counter.SECTIONS, 3);
            SlowFrames.exit(Phase.TERRAIN_DRAW);
            SlowFrames.exit(Phase.RENDER);
            SlowFrames.frameEnd();
        }
        long allocated = tmx.getCurrentThreadAllocatedBytes() - a0;
        check("20,000 fast frames allocate nothing measurable (" + allocated + " B)", allocated < 16 * 1024);
        check("ring index stays in range after wrapping (" + SlowFrames.head + ")", SlowFrames.head >= 0 && SlowFrames.head < SlowFrames.RING);

        // 6. summary: largest-phase ranking across slow frames, every 60 s and at quit
        for (int i = 0; i < 2; i++) {
            SlowFrames.frameStart();
            SlowFrames.enter(Phase.TASKS); ms(30); SlowFrames.exit(Phase.TASKS);
            SlowFrames.frameEnd();
        }
        SlowFrames.frameStart();
        SlowFrames.enter(Phase.SECTION_INTAKE); ms(16); SlowFrames.exit(Phase.SECTION_INTAKE);
        SlowFrames.frameEnd();
        String s = SlowFrames.summary("test");
        System.out.println("     " + s);
        check("summary ranks tasks (x3: two now + frame 2) before section intake (x1)",
                s.indexOf("tasks x3 mean 28.3") > 0 && s.indexOf("section intake x1 mean 16.0") > s.indexOf("tasks x3"));
        check("summary counts slow frames (5)", s.contains(" 5 over 12.0 ms"));
        int before = out.size();
        t += 61_000_000_000L;
        SlowFrames.frameStart(); ms(1); SlowFrames.frameEnd();
        check("a summary line every 60 s", out.size() == before + 1 && last().startsWith("[iroh-slow] summary (60 s)"));
        SlowFrames.quit();
        check("summary at quit", last().startsWith("[iroh-slow] summary (quit)"));

        // 7. hitches2: the stack sampler names a lock wait and its owner (a "render" thread blocked 40 ms on a monitor)
        Object lock = new Object();
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        Thread owner = new Thread(() -> {
            synchronized (lock) {
                held.countDown();
                try { Thread.sleep(60); } catch (InterruptedException ignored) {}
            }
        }, "light-engine-owner");
        String[] rep = {""};
        Thread renderT = new Thread(() -> {
            try { held.await(); } catch (InterruptedException ignored) {}
            SlowStacks.frameStart(System.nanoTime());
            synchronized (lock) { /* blocked until the owner lets go */ }
            rep[0] = SlowStacks.report();
            SlowStacks.frameEnd();
        }, "fake-render");
        owner.start();
        renderT.start();
        renderT.join();
        owner.join();
        System.out.println("    " + rep[0]);
        check("stack sampler: BLOCKED samples name the lock and its owner thread", rep[0].contains("BLOCKED") && rep[0].contains("owned by light-engine-owner"));
        check("stack sampler: leaf / first non-JDK frames listed", rep[0].contains("; leaf:") && rep[0].contains("SlowFramesTest"));

        System.out.println("# slowframes " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
