package dev.elemore.iroh.apiimpl;

import dev.elemore.iroh.api.FogState;
import dev.elemore.iroh.api.SectionListener;

import java.util.concurrent.CopyOnWriteArrayList;

/** api 4 internals: section built state + listeners, and the terrain fog snapshot. Render thread. */
public final class Sections {
    private static final CopyOnWriteArrayList<SectionListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile FogState fog = new FogState(Float.MAX_VALUE, Float.MAX_VALUE, 0, 0, 0, 0, 0);

    private Sections() {}

    public static dev.elemore.iroh.api.IrohFrame.Registration add(SectionListener l) {
        if (l == null) throw new IllegalArgumentException("listener");
        LISTENERS.add(l);
        return () -> LISTENERS.remove(l);
    }

    public static boolean any() {
        return !LISTENERS.isEmpty();
    }

    public static void built(int sx, int sy, int sz, boolean empty) {
        for (SectionListener l : LISTENERS) {
            try {
                l.sectionBuilt(sx, sy, sz, empty);
            } catch (RuntimeException e) {
                LISTENERS.remove(l);
                net.vulkanmod.Initializer.LOGGER.error("Iroh: section listener {} threw; removed", l.getClass().getName(), e);
            }
        }
    }

    public static void reset(int sx, int sy, int sz) {
        for (SectionListener l : LISTENERS) {
            try {
                l.sectionReset(sx, sy, sz);
            } catch (RuntimeException e) {
                LISTENERS.remove(l);
                net.vulkanmod.Initializer.LOGGER.error("Iroh: section listener {} threw; removed", l.getClass().getName(), e);
            }
        }
    }

    public static boolean isBuilt(int sx, int sy, int sz) {
        var wr = net.vulkanmod.render.chunk.WorldRenderer.getInstance();
        if (wr == null || wr.getSectionGrid() == null) return false;
        var s = wr.getSectionGrid().getSectionAtBlockPos(sx << 4, sy << 4, sz << 4);
        if (s == null || s.xOffset != sx << 4 || s.yOffset != sy << 4 || s.zOffset != sz << 4) return false;
        return s.isCompiled();
    }

    /** WorldRenderer.renderSectionLayer (solid): the fog values the terrain shader reads this frame. */
    public static void captureFog() {
        float[] c = com.mojang.blaze3d.systems.RenderSystem.getShaderFogColor();
        fog = new FogState(com.mojang.blaze3d.systems.RenderSystem.getShaderFogStart(), com.mojang.blaze3d.systems.RenderSystem.getShaderFogEnd(),
                com.mojang.blaze3d.systems.RenderSystem.getShaderFogShape().getIndex(), c[0], c[1], c[2], c[3]);
    }

    public static FogState fog() {
        return fog;
    }
}
