package dev.elemore.iroh.telemetry;

import dev.elemore.iroh.memory.MemoryGuard;
import dev.elemore.iroh.shaderpack.runtime.PackRuntime;
import dev.elemore.iroh.temporal.ScaleMode;
import dev.elemore.iroh.temporal.TemporalFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.server.MinecraftServer;
import net.vulkanmod.config.Config;
import net.vulkanmod.render.chunk.WorldRenderer;

/**
 * The game-side counters of the Telemetry overlay (card TEL-1), read at the sample rate (4 Hz) on the render thread.
 * Everything comes from counters that already exist: vanilla's connection / integrated server / level, Iroh's
 * MemoryGuard (VMA + VK_EXT_memory_budget numbers, sampled once a second there), the chunk build dispatcher's queue.
 */
public final class McSources implements Telemetry.Sources {
    private static final double MiB = 1024.0 * 1024.0;

    @Override
    public void fill(Telemetry.Snapshot s, Config c) {
        Minecraft mc = Minecraft.getInstance();
        s.framerateCap = mc.options.framerateLimit().get();

        // memory: device-local usage / budget (VK_EXT_memory_budget through VMA) and Iroh's share
        var budget = MemoryGuard.BUDGET;
        long usage = budget.driverUsage(), cap = budget.driverBudget();
        s.vramUsedMiB = usage > 0 ? usage / MiB : -1;
        s.vramBudgetMiB = cap > 0 ? cap / MiB : -1;
        long iroh = budget.irohUsage();
        s.irohVramMiB = iroh > 0 ? iroh / MiB : -1;
        if (s.vramUsedMiB < 0 && iroh > 0) s.vramUsedMiB = iroh / MiB; // no budget extension: Iroh's own number
        Runtime rt = Runtime.getRuntime();
        s.heapUsedMiB = (rt.totalMemory() - rt.freeMemory()) / MiB;
        s.heapMaxMiB = rt.maxMemory() / MiB;

        // network / server
        s.pingMs = -1;
        s.tps = s.mspt = -1;
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            double mspt = server.getAverageTickTimeNanos() / 1e6;
            s.mspt = mspt;
            s.tps = mspt <= 50 ? 20 : 1000 / mspt;
        } else if (mc.getConnection() != null && mc.player != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            if (info != null) s.pingMs = info.getLatency();
        }

        // world
        s.entities = mc.level == null ? -1 : mc.level.getEntityCount();
        s.renderDistance = mc.options.getEffectiveRenderDistance();
        WorldRenderer wr = WorldRenderer.getInstance();
        s.chunksPending = wr == null || wr.getTaskDispatcher() == null ? -1
                : wr.getTaskDispatcher().pendingTaskCount() + wr.getTaskDispatcher().pendingResultCount();

        // active Iroh features
        if (TelemetryHooks.renderingLevel == null && PackRuntime.active()) s.renderingLevel = "Shader pack";
        if (TemporalFrame.ACTIVE && TemporalFrame.mode != ScaleMode.OFF) s.fsrMode = TemporalFrame.mode.key;
    }
}
