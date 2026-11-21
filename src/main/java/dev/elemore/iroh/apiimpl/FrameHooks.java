package dev.elemore.iroh.apiimpl;

import dev.elemore.iroh.api.FrameContext;
import dev.elemore.iroh.api.FrameHook;
import dev.elemore.iroh.api.FrameStage;
import dev.elemore.iroh.api.IrohApi;
import dev.elemore.iroh.api.IrohFrame;
import dev.elemore.iroh.api.RenderPath;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Arrays;
import java.util.EnumSet;

/**
 * Iroh internal (P1-13a): {@link IrohFrame} storage and dispatch. Per stage an immutable sorted array (copy on write);
 * {@link #dispatch} reads it once, so a stage with no hook costs one array-length test and dispatch allocates nothing.
 * Also the stage clock for {@link FrameProfiler}.
 */
public final class FrameHooks {
    private static final FrameStage[] STAGES = FrameStage.values();

    static final class Entry {
        final int priority;
        final long seq;
        final EnumSet<RenderPath> paths;
        final FrameHook hook;
        int failures;

        Entry(int priority, long seq, EnumSet<RenderPath> paths, FrameHook hook) {
            this.priority = priority;
            this.seq = seq;
            this.paths = paths;
            this.hook = hook;
        }
    }

    private static final Entry[] NONE = new Entry[0];
    @SuppressWarnings("unchecked")
    private static final Entry[][] HOOKS = new Entry[STAGES.length][];
    private static long seq;
    private static final Context CONTEXT = new Context();

    static {
        Arrays.fill(HOOKS, NONE);
    }

    private FrameHooks() {
    }

    public static synchronized IrohFrame.Registration add(FrameStage stage, int priority, EnumSet<RenderPath> paths, FrameHook hook) {
        if (hook == null) throw new NullPointerException("hook");
        Entry e = new Entry(priority, seq++, paths, hook);
        Entry[] cur = HOOKS[stage.ordinal()];
        Entry[] next = Arrays.copyOf(cur, cur.length + 1);
        next[cur.length] = e;
        Arrays.sort(next, (a, b) -> a.priority != b.priority ? Integer.compare(a.priority, b.priority) : Long.compare(a.seq, b.seq));
        HOOKS[stage.ordinal()] = next;
        return () -> remove(stage, e);
    }

    static synchronized void remove(FrameStage stage, Entry e) {
        Entry[] cur = HOOKS[stage.ordinal()];
        int i = Arrays.asList(cur).indexOf(e);
        if (i < 0) return;
        Entry[] next = new Entry[cur.length - 1];
        System.arraycopy(cur, 0, next, 0, i);
        System.arraycopy(cur, i + 1, next, i, cur.length - i - 1);
        HOOKS[stage.ordinal()] = next.length == 0 ? NONE : next;
    }

    /** Number of hooks at a stage (tests, diagnostics). */
    public static int count(FrameStage stage) {
        return HOOKS[stage.ordinal()].length;
    }

    /** New frame: index and partial tick. Render thread. */
    public static void beginFrame(float partialTick, int width, int height) {
        Context c = CONTEXT;
        c.frameIndex++;
        c.partialTick = partialTick;
        c.width = width;
        c.height = height;
        c.inWorld = false;
    }

    /** World pass matrices / camera (from Fabric's WorldRenderContext at WORLD_START). Copies, no allocation. */
    public static void setWorld(double cx, double cy, double cz, Matrix4fc view, Matrix4fc projection) {
        Context c = CONTEXT;
        c.camera.set(cx, cy, cz);
        if (view != null) c.view.set(view);
        if (projection != null) c.projection.set(projection);
    }

    public static void setInWorld(boolean inWorld) {
        CONTEXT.inWorld = inWorld;
    }

    /** Runs the hooks of {@code stage} (and marks the stage for the profiler). Render thread. */
    public static void dispatch(FrameStage stage) {
        if (FrameProfiler.ON) FrameProfiler.mark(stage);
        Entry[] hooks = HOOKS[stage.ordinal()];
        if (hooks.length == 0) return;
        Context c = CONTEXT;
        c.stage = stage;
        RenderPath path = IrohApi.renderPath();
        for (int i = 0; i < hooks.length; i++) {
            Entry e = hooks[i];
            if (!e.paths.contains(path)) continue;
            StateGuard.capture();
            DrawImpl.insideHook = true;
            try {
                e.hook.run(c);
                if (c.gui != null) c.gui.flush();
            } catch (Throwable t) {
                if (++e.failures >= 3) {
                    net.vulkanmod.Initializer.LOGGER.error("Iroh: frame hook {} at {} failed 3 times; removed", e.hook.getClass().getName(), stage, t);
                    remove(stage, e);
                } else {
                    net.vulkanmod.Initializer.LOGGER.error("Iroh: frame hook {} at {} threw ({} of 3)", e.hook.getClass().getName(), stage, e.failures, t);
                }
            } finally {
                if (stage != FrameStage.AFTER_PRESENT) DrawImpl.flush();
                DrawImpl.insideHook = false;
                StateGuard.verifyAndRestore(e.hook, stage);
            }
        }
    }

    static boolean inWorld() { return CONTEXT.inWorld; }

    static Matrix4f view() { return CONTEXT.view; }

    static Matrix4f projection() { return CONTEXT.projection; }

    /** 2D stages: dispatch with the frame's GuiGraphics in the context (flushed before and after each hook). */
    public static void dispatch2d(FrameStage stage, net.minecraft.client.gui.GuiGraphics gui) {
        if (HOOKS[stage.ordinal()].length != 0) {
            gui.flush();
            CONTEXT.gui = gui;
            try {
                dispatch(stage);
            } finally {
                CONTEXT.gui = null;
            }
        } else if (FrameProfiler.ON) {
            dispatch(stage);
        }
    }

    /** The context object (tests). */
    public static FrameContext context() {
        return CONTEXT;
    }

    static final class Context implements FrameContext {
        FrameStage stage = FrameStage.FRAME_BEGIN;
        long frameIndex;
        float partialTick;
        boolean inWorld;
        int width, height;
        final Vector3d camera = new Vector3d();
        final Matrix4f view = new Matrix4f(), projection = new Matrix4f();

        @Override public FrameStage stage() { return stage; }
        @Override public long frameIndex() { return frameIndex; }
        @Override public float partialTick() { return partialTick; }
        @Override public boolean inWorld() { return inWorld; }
        @Override public Vector3dc cameraPos() { return camera; }
        @Override public Matrix4fc viewMatrix() { return view; }
        @Override public Matrix4fc projectionMatrix() { return projection; }
        @Override public int viewportWidth() { return width; }
        @Override public int viewportHeight() { return height; }
        @Override public RenderPath renderPath() { return IrohApi.renderPath(); }
        @Override public boolean isShadowPass() { return false; }
        net.minecraft.client.gui.GuiGraphics gui;
        @Override public net.minecraft.client.gui.GuiGraphics guiGraphics() { return gui; }
    }
}
