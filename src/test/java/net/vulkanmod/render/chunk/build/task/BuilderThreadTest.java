package net.vulkanmod.render.chunk.build.task;

import net.vulkanmod.render.PipelineManager;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.render.vertex.TerrainBuilder;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * R-P213BC (RQ-P213-b), run from UpstreamBackportTest: a chunk task that throws must not end its builder thread, a
 * repeated failure is rate limited, and stopThreads' join cannot hang when a builder misses the wakeup.
 */
public final class BuilderThreadTest {
    private static int checks;
    private static List<String> fails;

    /** A task without a section: runs {@code body}, which may throw. */
    private static final class Probe extends ChunkTask {
        final Runnable body;
        final CountDownLatch ran = new CountDownLatch(1);
        final AtomicInteger rebuilds = new AtomicInteger();

        Probe(Runnable body) {
            super(null);
            this.body = body;
        }

        public String name() { return "probe"; }

        public Result runTask(BuilderResources r) {
            try {
                this.body.run();
            } finally {
                this.ran.countDown();
            }
            return Result.SUCCESSFUL;
        }

        @Override
        void markForRebuild() { this.rebuilds.incrementAndGet(); }
    }

    public static int run(List<String> failList) throws Exception {
        fails = failList;
        checks = 0;
        PipelineManager.terrainVertexFormat = CustomVertexFormat.COMPRESSED_TERRAIN;
        if (net.vulkanmod.Initializer.CONFIG == null) net.vulkanmod.Initializer.CONFIG = new net.vulkanmod.config.Config();
        ThreadBuilderPack.setTerrainBuilderConstructor(t -> new TerrainBuilder(64));
        survivesThrowingTask();
        rateLimit();
        missedWakeup();
        return checks;
    }

    private static int builders(String prefix) {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet())
            if (t.getName().startsWith(prefix) && t.isAlive())
                n++;
        return n;
    }

    private static void survivesThrowingTask() throws Exception {
        TaskDispatcher d = new TaskDispatcher();
        d.resourceFactory = () -> null;
        d.createThreads(2);
        int before = builders("Builder-");
        // more throwing tasks than builders: with the old loop each one ended a thread
        Probe[] bad = new Probe[6];
        for (int i = 0; i < bad.length; i++) {
            bad[i] = new Probe(() -> { throw new NullPointerException("probe " + System.nanoTime()); });
            d.schedule(bad[i]);
        }
        for (Probe p : bad)
            check("throwing task ran", p.ran.await(10, TimeUnit.SECONDS));
        Probe good = new Probe(() -> { });
        d.schedule(good);
        check("a task scheduled after six failures still runs (builders alive)", good.ran.await(5, TimeUnit.SECONDS));
        check("builder threads still alive: " + builders("Builder-") + " of " + before, builders("Builder-") >= before);
        waitIdle(d);
        check("pending counter back to zero after failures", d.isIdle());
        long t0 = System.nanoTime();
        d.stopThreads();
        check("stopThreads after failures returns promptly", System.nanoTime() - t0 < 3_000_000_000L);
    }

    private static void rateLimit() throws Exception {
        TaskDispatcher d = new TaskDispatcher();
        d.resourceFactory = () -> null;
        d.createThreads(1);
        // the same failure 25 times at the same (null) section: logged at 1, 10; retried twice, then dropped
        for (int i = 0; i < 25; i++) {
            Probe p = new Probe(() -> { throw new IllegalStateException("same message"); });
            d.schedule(p);
            p.ran.await(5, TimeUnit.SECONDS);
        }
        waitIdle(d);
        var f = d.failures;
        check("25 identical failures logged " + f.logged + " times (expect 2: first and 10th)", f.logged == 2);
        check("section retried " + f.retried + " times (expect " + ChunkTaskFailures.MAX_RETRIES + ")", f.retried == ChunkTaskFailures.MAX_RETRIES);
        check("the rest dropped: " + f.dropped, f.dropped == 25 - ChunkTaskFailures.MAX_RETRIES);
        d.stopThreads();
    }

    /**
     * Forced interleaving: the builder has polled nothing and is about to wait; stopThreads() runs completely (flag, notifyAll)
     * inside that gap. With a plain wait() the notify is lost and join() hangs.
     */
    private static void missedWakeup() throws Exception {
        TaskDispatcher d = new TaskDispatcher();
        CountDownLatch inGap = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        d.afterEmptyPoll = () -> {
            d.afterEmptyPoll = null;
            inGap.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        };
        d.resourceFactory = () -> null;
        d.createThreads(1);
        check("builder reached the poll/wait gap", inGap.await(5, TimeUnit.SECONDS));
        Thread stopper = new Thread(d::stopThreads, "stopper");
        stopper.start();
        Thread.sleep(300); // stopThreads has set its flag and notified: nobody is waiting yet
        release.countDown();
        stopper.join(4000);
        boolean hung = stopper.isAlive();
        check("stopThreads did not hang on a notify that landed before the wait", !hung);
        if (hung) {
            // old code: unstick the leftovers so the test process can end
            for (Thread t : Thread.getAllStackTraces().keySet())
                if (t.getName().startsWith("Builder-") || t == stopper)
                    t.interrupt();
        }
        check("builder thread gone after stop", builders("Builder-") == 0 || !hung);

        // a schedule() landing in the same gap runs the task instead of sleeping on it
        TaskDispatcher e = new TaskDispatcher();
        CountDownLatch gap2 = new CountDownLatch(1);
        CountDownLatch rel2 = new CountDownLatch(1);
        e.afterEmptyPoll = () -> {
            e.afterEmptyPoll = null;
            gap2.countDown();
            try {
                rel2.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        };
        e.resourceFactory = () -> null;
        e.createThreads(1);
        gap2.await(5, TimeUnit.SECONDS);
        Probe p = new Probe(() -> { });
        e.schedule(p); // offer + notify happen while the builder sits in the gap
        rel2.countDown();
        check("a task scheduled inside the gap still runs", p.ran.await(5, TimeUnit.SECONDS));
        e.stopThreads();
    }

    private static void waitIdle(TaskDispatcher d) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (!d.isIdle() && System.nanoTime() < end)
            Thread.sleep(5);
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok)
            fails.add(what);
    }
}
