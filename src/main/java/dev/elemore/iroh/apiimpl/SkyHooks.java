package dev.elemore.iroh.apiimpl;

import dev.elemore.iroh.api.CloudOverride;
import dev.elemore.iroh.api.IrohFrame;
import dev.elemore.iroh.api.WeatherOverride;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.Arrays;
import java.util.function.Predicate;

/** Iroh internal (P1-13a): {@link dev.elemore.iroh.api.IrohSky} registries and their checks. Copy-on-write arrays. */
public final class SkyHooks {
    private record Prio<T>(int priority, long seq, T value) {}

    @SuppressWarnings("unchecked") private static volatile Predicate<ClientLevel>[] suppressors = new Predicate[0];
    @SuppressWarnings("unchecked") private static volatile Prio<CloudOverride>[] clouds = new Prio[0];
    @SuppressWarnings("unchecked") private static volatile Prio<WeatherOverride>[] weather = new Prio[0];
    private static long seq;

    private SkyHooks() {
    }

    public static synchronized IrohFrame.Registration addCloudSuppressor(Predicate<ClientLevel> p) {
        suppressors = append(suppressors, p);
        return () -> { synchronized (SkyHooks.class) { suppressors = removeEq(suppressors, p); } };
    }

    public static synchronized IrohFrame.Registration addCloudOverride(int priority, CloudOverride o) {
        Prio<CloudOverride> e = new Prio<>(priority, seq++, o);
        clouds = sorted(append(clouds, e));
        return () -> { synchronized (SkyHooks.class) { clouds = removeEq(clouds, e); } };
    }

    public static synchronized IrohFrame.Registration addWeatherOverride(int priority, WeatherOverride o) {
        Prio<WeatherOverride> e = new Prio<>(priority, seq++, o);
        weather = sorted(append(weather, e));
        return () -> { synchronized (SkyHooks.class) { weather = removeEq(weather, e); } };
    }

    /** True when a suppressor wants no clouds for this level. Render thread; one length test when none registered. */
    public static boolean cloudsSuppressed(ClientLevel level) {
        Predicate<ClientLevel>[] s = suppressors;
        for (Predicate<ClientLevel> p : s) {
            try {
                if (p.test(level)) return true;
            } catch (Throwable t) {
                net.vulkanmod.Initializer.LOGGER.error("Iroh: cloud suppressor {} threw", p.getClass().getName(), t);
            }
        }
        return false;
    }

    /** First cloud override that draws claims the stage. */
    public static boolean drawCloudOverride() {
        Prio<CloudOverride>[] c = clouds;
        if (c.length == 0) return false;
        for (Prio<CloudOverride> e : c) {
            boolean claimed = false;
            StateGuard.capture();
            DrawImpl.insideHook = true;
            try {
                claimed = e.value().draw(FrameHooks.context());
            } catch (Throwable t) {
                net.vulkanmod.Initializer.LOGGER.error("Iroh: cloud override {} threw", e.value().getClass().getName(), t);
            } finally {
                DrawImpl.flush();
                DrawImpl.insideHook = false;
                StateGuard.verifyAndRestore(e.value(), dev.elemore.iroh.api.FrameStage.AFTER_TRANSLUCENT);
            }
            if (claimed) return true;
        }
        return false;
    }

    public static boolean drawWeatherOverride() {
        Prio<WeatherOverride>[] w = weather;
        if (w.length == 0) return false;
        for (Prio<WeatherOverride> e : w) {
            boolean claimed = false;
            StateGuard.capture();
            DrawImpl.insideHook = true;
            try {
                claimed = e.value().draw(FrameHooks.context());
            } catch (Throwable t) {
                net.vulkanmod.Initializer.LOGGER.error("Iroh: weather override {} threw", e.value().getClass().getName(), t);
            } finally {
                DrawImpl.flush();
                DrawImpl.insideHook = false;
                StateGuard.verifyAndRestore(e.value(), dev.elemore.iroh.api.FrameStage.AFTER_WEATHER);
            }
            if (claimed) return true;
        }
        return false;
    }

    private static <T> T[] append(T[] a, T v) {
        T[] n = Arrays.copyOf(a, a.length + 1);
        n[a.length] = v;
        return n;
    }

    private static <T> T[] removeEq(T[] a, T v) {
        return Arrays.stream(a).filter(x -> x != v).toArray(n -> Arrays.copyOf(a, n));
    }

    private static <T> Prio<T>[] sorted(Prio<T>[] a) {
        Arrays.sort(a, (x, y) -> x.priority() != y.priority() ? Integer.compare(x.priority(), y.priority()) : Long.compare(x.seq(), y.seq()));
        return a;
    }
}
