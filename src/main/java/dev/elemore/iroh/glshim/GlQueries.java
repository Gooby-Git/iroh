package dev.elemore.iroh.glshim;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.elemore.iroh.glshim.GlNet.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Iroh (P1-10 item 4b): GL query objects (GL 1.5 / 3.3 timer queries) on threads without a GL context. First user:
 * Reflex AntiLag 1.1.6 ({@code io.tythee.GpuTimeCollector}), which puts {@code glQueryCounter(GL_TIMESTAMP)} around
 * the frame's render submission and reads {@code glGetInteger64v(GL_TIMESTAMP)} to map GPU ticks to
 * {@code System.nanoTime}.
 * <p>
 * Emulation: a query is a handle; GL_TIMESTAMP (both the query counter and {@code glGetInteger64v}) is
 * {@code System.nanoTime()} at the call, so GPU ticks equal system nanoseconds (offset 0, 64 counter bits);
 * GL_TIME_ELAPSED is the CPU time between begin and end; sample queries report "passed"; every result is available at
 * once. There is no GL timeline under Iroh: the "GPU" times are the CPU recording times of the same points.
 */
public final class GlQueries {
    static final int GL_TIMESTAMP = 0x8E28, GL_TIME_ELAPSED = 0x88BF;
    static final int GL_QUERY_COUNTER_BITS = 0x8864, GL_CURRENT_QUERY = 0x8865;
    static final int GL_QUERY_RESULT = 0x8866, GL_QUERY_RESULT_AVAILABLE = 0x8867, GL_QUERY_RESULT_NO_WAIT = 0x9194;

    private static final AtomicInteger NEXT = new AtomicInteger(1);
    private static final ConcurrentHashMap<Integer, long[]> QUERIES = new ConcurrentHashMap<>(); // {result, beginNs}
    private static final ConcurrentHashMap<Integer, Integer> ACTIVE = new ConcurrentHashMap<>(); // target -> id

    private GlQueries() {
    }

    public static int gen() {
        int id = NEXT.getAndIncrement();
        QUERIES.put(id, new long[2]);
        return id;
    }

    public static void delete(int id) {
        QUERIES.remove(id);
    }

    public static boolean isQuery(int id) {
        return QUERIES.containsKey(id);
    }

    public static long timestamp() {
        return System.nanoTime();
    }

    public static void queryCounter(int id, int target) {
        long[] q = QUERIES.computeIfAbsent(id, k -> new long[2]);
        q[0] = System.nanoTime();
    }

    public static void begin(int target, int id) {
        long[] q = QUERIES.computeIfAbsent(id, k -> new long[2]);
        q[1] = System.nanoTime();
        q[0] = target == GL_TIME_ELAPSED ? 0 : 1; // sample / primitive queries: "something passed"
        ACTIVE.put(target, id);
    }

    public static void end(int target) {
        Integer id = ACTIVE.remove(target);
        if (id == null) return;
        long[] q = QUERIES.get(id);
        if (q != null && target == GL_TIME_ELAPSED) q[0] = System.nanoTime() - q[1];
    }

    /** glGetQueryObject*: result or availability (always available). */
    public static long object(int id, int pname) {
        if (pname == GL_QUERY_RESULT_AVAILABLE) return 1;
        long[] q = QUERIES.get(id);
        return q == null ? 0 : q[0];
    }

    /** glGetQueryiv. */
    public static int queryiv(int target, int pname) {
        if (pname == GL_QUERY_COUNTER_BITS) return target == GL_TIMESTAMP || target == GL_TIME_ELAPSED ? 64 : 32;
        if (pname == GL_CURRENT_QUERY) return ACTIVE.getOrDefault(target, 0);
        return 0;
    }

    /** glGetInteger64v: GL_TIMESTAMP from the clock, anything else from the int state. */
    public static long integer64(int pname) {
        if (pname == GL_TIMESTAMP) return System.nanoTime();
        int[] v = new int[16];
        GlState.getIntegerv(pname, v);
        return v[0];
    }

    /** Registers the pointer-path natives on {@link GlNet} (array overloads: mixin GL15CM / GL32CM). */
    static void register() {
        on("glGenQueries", "V:IP", a -> {
            int n = i(a, 0);
            long p = p(a, 1);
            for (int k = 0; k < n; k++) memPutInt(p + 4L * k, gen());
            return 0;
        });
        on("glDeleteQueries", "V:IP", a -> {
            int n = i(a, 0);
            long p = p(a, 1);
            for (int k = 0; k < n; k++) delete(memGetInt(p + 4L * k));
            return 0;
        });
        on("glIsQuery", "Z:I", a -> isQuery(i(a, 0)) ? 1 : 0);
        on("glQueryCounter", "V:II", a -> { queryCounter(i(a, 0), i(a, 1)); return 0; });
        on("glBeginQuery", "V:II", a -> { begin(i(a, 0), i(a, 1)); return 0; });
        on("glEndQuery", "V:I", a -> { end(i(a, 0)); return 0; });
        on("glGetQueryiv", "V:IIP", a -> { memPutInt(p(a, 2), queryiv(i(a, 0), i(a, 1))); return 0; });
        on("glGetQueryObjectiv", "V:IIP", a -> { memPutInt(p(a, 2), (int) object(i(a, 0), i(a, 1))); return 0; });
        on("glGetQueryObjectuiv", "V:IIP", a -> { memPutInt(p(a, 2), (int) object(i(a, 0), i(a, 1))); return 0; });
        on("glGetQueryObjecti64v", "V:IIP", a -> { memPutLong(p(a, 2), object(i(a, 0), i(a, 1))); return 0; });
        on("glGetQueryObjectui64v", "V:IIP", a -> { memPutLong(p(a, 2), object(i(a, 0), i(a, 1))); return 0; });
        on("glGetInteger64v", "V:IP", a -> { memPutLong(p(a, 1), integer64(i(a, 0))); return 0; });
    }
}
