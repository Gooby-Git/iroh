package dev.elemore.iroh.apiimpl;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.util.Mth;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.SectionGrid;
import net.vulkanmod.render.chunk.WorldRenderer;

/** Iroh internal (P1-6b): section visibility from the last section-graph update. Render thread. */
public final class Visibility {
    private static final int MAX_SPAN = 4;

    private Visibility() {
    }

    public static boolean isSectionVisible(int sx, int sy, int sz) {
        if (!RenderSystem.isOnRenderThread())
            return true;
        WorldRenderer wr = WorldRenderer.getInstance();
        if (wr == null)
            return true;
        SectionGrid grid = wr.getSectionGrid();
        if (grid == null)
            return true;
        return visible(wr, grid, sx, sy, sz);
    }

    private static boolean visible(WorldRenderer wr, SectionGrid grid, int sx, int sy, int sz) {
        RenderSection s = grid.getSectionAtBlockPos(sx << 4, sy << 4, sz << 4);
        if (s == null)
            return true; // outside the build height: nothing there to hide it
        if (s.xOffset != sx << 4 || s.yOffset != sy << 4 || s.zOffset != sz << 4)
            return false; // the grid slot holds another section: this one is outside render distance
        return s.lastVisibleFrame == wr.getLastFrame();
    }

    public static boolean isBoxVisible(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (!RenderSystem.isOnRenderThread())
            return true;
        WorldRenderer wr = WorldRenderer.getInstance();
        if (wr == null)
            return true;
        SectionGrid grid = wr.getSectionGrid();
        if (grid == null)
            return true;
        int x0 = Mth.floor(minX) >> 4, y0 = Mth.floor(minY) >> 4, z0 = Mth.floor(minZ) >> 4;
        int x1 = Mth.floor(maxX) >> 4, y1 = Mth.floor(maxY) >> 4, z1 = Mth.floor(maxZ) >> 4;
        if (x1 - x0 >= MAX_SPAN || y1 - y0 >= MAX_SPAN || z1 - z0 >= MAX_SPAN)
            return true;
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++)
                    if (visible(wr, grid, x, y, z))
                        return true;
        return false;
    }
}
