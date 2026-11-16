package dev.elemore.iroh.glshim;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.FunctionProvider;
import org.lwjgl.system.Pointer;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.FFIType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.system.libffi.LibFFI.*;

/**
 * The GL safety net (P1-2). No LWJGL OpenGL call made on a thread without a GL context may abort the JVM.
 *
 * <p>How LWJGL 3.3 dispatches GL: every {@code GLxxC.glFoo}/{@code nglFoo} is a JNI native that loads the function
 * pointer from a per-thread table hung off the JNIEnv (index fixed per function). A thread that never called
 * {@code GL.setCapabilities} uses the global table {@code ThreadLocalUtil.FUNCTION_MISSING_ABORT_TABLE} (2228 entries
 * in 3.3.3), every entry pointing to a native that prints "No context is current..." and aborts the JVM. Under Iroh
 * that is every thread: Iroh never makes a GL context current.
 *
 * <p>The net overwrites each entry of that table with a libffi closure:
 * <ul>
 *   <li>functions with a registered {@link Handler} (state and texture emulation, {@link GlState}) get a closure
 *       whose call interface matches the real C signature, so the handler reads the real arguments;</li>
 *   <li>every other function gets an argument-less closure returning 0 (an int, GL_FALSE, a null pointer; void
 *       callers ignore it) that logs once per (function, calling mod) through {@link GlShim#unhandled}.</li>
 * </ul>
 * Threads that made a real context current (DreamDisplays, WaterMedia on their own threads) have their own table
 * filled by {@code GL.createCapabilities} and never see the net. So the net needs no "is a context current" check
 * and no {@code @Overwrite} of any LWJGL native: other mods' injectors into LWJGL keep working, and the real driver
 * stays reachable from context threads.
 *
 * <p>Array overloads ({@code glGetIntegerv(int, int[])} etc.) do not use the table: they read the address from
 * {@code GL.getICD()}. For threads without a context {@link #icdCapabilities()} answers with the real libGL entry
 * points, which are documented no-ops when no context is current (glvnd/Mesa dispatch); see the GLM mixin.
 *
 * <p>Function names come from LWJGL itself: a GLCapabilities is built with a fake provider that hands out a unique
 * address per name; position i of its address buffer then names table entry i.
 */
public final class GlNet {
    /** A handled GL function: reads its arguments from libffi's {@code void** args}, returns the integral result. */
    @FunctionalInterface
    public interface Handler {
        long call(long args);
    }

    private record Registered(String sig, Handler handler) {}

    private static final Map<String, Registered> HANDLERS = new HashMap<>();
    private static final Map<String, FFICIF> CIFS = new HashMap<>();
    private static volatile long installedTable;
    private static String[] names = new String[0];
    private static Stub[] stubs;
    private static long[] stubAddresses;
    private static volatile GLCapabilities icdCaps;
    private static volatile boolean icdTried;
    private static int handledCount;

    private GlNet() {}

    // ---------------------------------------------------------------- registration

    /**
     * Registers the emulation of a GL function. {@code sig} is "R:ARGS" with one letter per C argument:
     * I int32/enum/uint, Z GLboolean, F float, D double, J int64 (GLsizeiptr, GLintptr, GLint64), P pointer;
     * R is the same set plus V (void). Example: {@code on("glGetIntegerv", "V:IP", a -> ...)}.
     * Must be called before {@link #install()}.
     */
    public static synchronized void on(String fn, String sig, Handler h) {
        HANDLERS.put(fn, new Registered(sig, h));
    }

    // ---------------------------------------------------------------- argument access for handlers

    private static long slot(long args, int n) {
        return memGetAddress(args + (long) n * Pointer.POINTER_SIZE);
    }

    public static int i(long args, int n) { return memGetInt(slot(args, n)); }
    public static float f(long args, int n) { return memGetFloat(slot(args, n)); }
    public static double d(long args, int n) { return memGetDouble(slot(args, n)); }
    public static long j(long args, int n) { return memGetLong(slot(args, n)); }
    public static long p(long args, int n) { return memGetAddress(slot(args, n)); }
    public static boolean z(long args, int n) { return memGetByte(slot(args, n)) != 0; }

    // ---------------------------------------------------------------- install

    /** Idempotent. Called after {@code GL.create} (GLM mixin) and from Iroh's client initializer. */
    public static synchronized void install() {
        long table;
        int size;
        try {
            Class<?> tlu = Class.forName("org.lwjgl.system.ThreadLocalUtil");
            Field tf = tlu.getDeclaredField("FUNCTION_MISSING_ABORT_TABLE");
            tf.setAccessible(true);
            table = tf.getLong(null);
            Field sf = GLCapabilities.class.getDeclaredField("ADDRESS_BUFFER_SIZE");
            sf.setAccessible(true);
            size = sf.getInt(null);
        } catch (Throwable t) {
            GlShim.LOGGER.error("Iroh GL shim: safety net NOT installed (LWJGL internals not found); a GL call without a context will abort the JVM", t);
            return;
        }
        if (table == 0L || table == installedTable) return;

        try {
            GlState.register();
            String[] n = functionNames(size);
            Stub[] s = new Stub[size];
            long[] addr = new long[size];
            int handled = 0;
            for (int i = 0; i < size; i++) {
                Registered r = n[i] != null ? HANDLERS.get(n[i]) : null;
                s[i] = r != null ? new Stub(n[i], r.handler, r.sig) : new Stub(n[i] != null ? n[i] : "gl#" + i, null, "J:");
                if (r != null) handled++;
                addr[i] = s[i].address();
            }
            for (int i = 0; i < size; i++)
                memPutAddress(table + (long) i * Pointer.POINTER_SIZE, addr[i]);
            names = n;
            stubs = s;
            stubAddresses = addr;
            handledCount = handled;
            installedTable = table;
            GlShim.LOGGER.info("Iroh GL shim: safety net over {} GL entry points ({} emulated, {} handlers registered); calls without a GL context no longer abort",
                    size, handled, HANDLERS.size());
            for (String fn : HANDLERS.keySet())
                if (!java.util.Arrays.asList(n).contains(fn))
                    GlShim.LOGGER.warn("Iroh GL shim: handler for {} has no LWJGL table entry (name typo?)", fn);
        } catch (Throwable t) {
            GlShim.LOGGER.error("Iroh GL shim: safety net install failed; a GL call without a context will abort the JVM", t);
        }
    }

    public static boolean installed() {
        return installedTable != 0L;
    }

    public static int handledCount() {
        return handledCount;
    }

    /** Name of table entry i, or null. */
    public static String name(int i) {
        String[] n = names;
        return i >= 0 && i < n.length ? n[i] : null;
    }

    /** Every GL function name LWJGL knows, by table index; built with a fake provider (no driver involved). */
    static String[] functionNames(int size) throws ReflectiveOperationException {
        Map<Long, String> byAddress = new HashMap<>();
        long[] next = {0x1000L};
        FunctionProvider fake = new FunctionProvider() {
            @Override
            public long getFunctionAddress(CharSequence functionName) {
                long a = next[0];
                next[0] += 16;
                byAddress.put(a, functionName.toString());
                return a;
            }

            @Override
            public long getFunctionAddress(ByteBuffer functionName) {
                return getFunctionAddress(memASCII(functionName));
            }
        };
        GLCapabilities caps = construct(fake, allCapabilityNames(), size);
        Field af = GLCapabilities.class.getDeclaredField("addresses");
        af.setAccessible(true);
        PointerBuffer addresses = (PointerBuffer) af.get(caps);
        String[] n = new String[size];
        for (int i = 0; i < size && i < addresses.capacity(); i++)
            n[i] = byAddress.get(addresses.get(i));
        return n;
    }

    /** "OpenGL11" ... "OpenGL46" and every "GL_*" extension flag name of GLCapabilities. */
    static Set<String> allCapabilityNames() {
        Set<String> ext = new HashSet<>();
        for (Field f : GLCapabilities.class.getFields())
            if (f.getType() == boolean.class && !Modifier.isStatic(f.getModifiers())) ext.add(f.getName());
        return ext;
    }

    private static GLCapabilities construct(FunctionProvider provider, Set<String> ext, int size) throws ReflectiveOperationException {
        Constructor<GLCapabilities> c = GLCapabilities.class.getDeclaredConstructor(FunctionProvider.class, Set.class, boolean.class, IntFunction.class);
        c.setAccessible(true);
        IntFunction<PointerBuffer> buffers = PointerBuffer::allocateDirect;
        return c.newInstance(provider, ext, false, buffers);
    }

    /**
     * Capabilities for {@code GL.getICD()} on threads without a context: the real libGL entry points (no-ops without a
     * current context) so array overloads do not throw. Never returned by {@code GL.getCapabilities()}, whose all-false
     * flags tell mods there is no GL. Null if it cannot be built (LWJGL then throws a Java exception, not an abort).
     */
    public static GLCapabilities icdCapabilities() {
        if (icdTried) return icdCaps;
        synchronized (GlNet.class) {
            if (!icdTried) {
                try {
                    FunctionProvider p = GL.getFunctionProvider();
                    Field sf = GLCapabilities.class.getDeclaredField("ADDRESS_BUFFER_SIZE");
                    sf.setAccessible(true);
                    if (p != null) icdCaps = construct(p, allCapabilityNames(), sf.getInt(null));
                } catch (Throwable t) {
                    GlShim.LOGGER.warn("Iroh GL shim: no ICD capabilities for context-less threads; GL array overloads will throw", t);
                }
                icdTried = true;
            }
        }
        return icdCaps;
    }

    // ---------------------------------------------------------------- closures

    private static FFIType type(char c) {
        return switch (c) {
            case 'V' -> ffi_type_void;
            case 'I' -> ffi_type_sint32;
            case 'Z' -> ffi_type_uint8;
            case 'F' -> ffi_type_float;
            case 'D' -> ffi_type_double;
            case 'J' -> ffi_type_sint64;
            case 'P' -> ffi_type_pointer;
            default -> throw new IllegalArgumentException("signature letter " + c);
        };
    }

    private static synchronized FFICIF cif(String sig) {
        return CIFS.computeIfAbsent(sig, s -> {
            int colon = s.indexOf(':');
            FFIType ret = type(s.charAt(0));
            String a = s.substring(colon + 1);
            FFIType[] args = new FFIType[a.length()];
            for (int k = 0; k < args.length; k++) args[k] = type(a.charAt(k));
            return APIUtil.apiCreateCIF(FFI_DEFAULT_ABI, ret, args);
        });
    }

    private static final class Stub implements CallbackI {
        private final String fn;
        private final Handler handler;
        private final char ret;
        private final FFICIF cif;

        Stub(String fn, Handler handler, String sig) {
            this.fn = fn;
            this.handler = handler;
            this.ret = sig.charAt(0);
            this.cif = cif(sig);
        }

        @Override
        public FFICIF getCallInterface() {
            return cif;
        }

        @Override
        public void callback(long retAddr, long args) {
            long r = 0L;
            if (handler == null) {
                try {
                    GlShim.unhandled(fn);
                } catch (Throwable ignored) {
                }
            } else {
                try {
                    r = handler.call(args);
                } catch (Throwable t) {
                    try {
                        GlShim.failed(fn, t);
                    } catch (Throwable ignored) {
                    }
                    r = 0L;
                }
            }
            // libffi: integral results narrower than a register are written as a full ffi_arg (8 bytes).
            switch (ret) {
                case 'V' -> {}
                case 'F' -> memPutFloat(retAddr, Float.intBitsToFloat((int) r));
                case 'D' -> memPutDouble(retAddr, Double.longBitsToDouble(r));
                default -> memPutLong(retAddr, r);
            }
        }
    }
}
