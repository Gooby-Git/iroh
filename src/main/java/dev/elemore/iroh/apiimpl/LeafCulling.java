package dev.elemore.iroh.apiimpl;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.vulkanmod.Initializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;

/**
 * Iroh internal (P1-6b): option {@code leafCulling}, Iroh's replacement for the Cull Fewer Leaves mod (LGPL-3.0, read
 * as bytecode for behaviour only, no code taken). Behaviour, in our words: a face of a leaves block is hidden when every
 * block in a straight line out of that face, up to {@code depth} blocks, is leaves (depth = the configured value on
 * fancy leaves, 1 on fast leaves). When the first block out is leaves but a deeper one is not, the face is hidden
 * anyway for {@code randomRejection} percent of block positions, chosen by a fixed per-position random number (the
 * first {@code nextFloat()} of {@code java.util.Random} seeded with {@code BlockPos.asLong()}, so the same blocks are
 * picked as with the old mod and the pattern never flickers). Faces whose first neighbour is not leaves are untouched.
 * <p>
 * Defaults are migrated from {@code config/cull-fewer-leaves.toml} when the old mod left one (the pack: enabled,
 * depth 2, randomRejection 20). Without that file the option is off, which is vanilla's and Sodium's look. The settings
 * screen (P1-6) owns persistence and calls {@link #set}; a change needs one {@code LevelRenderer.allChanged()}.
 */
public final class LeafCulling {
    private record Settings(boolean enabled, int depth, int randomRejection) {
    }

    private static volatile Settings settings = migrate();

    private LeafCulling() {
    }

    public static boolean enabled() {
        return settings.enabled;
    }

    public static int depth() {
        return settings.depth;
    }

    public static int randomRejection() {
        return settings.randomRejection;
    }

    static float rejection() {
        return settings.randomRejection / 100.0f;
    }

    /** Sets the option; returns true when it changed (then the caller rebuilds the world mesh once). */
    public static boolean set(boolean enabled, int depth, int randomRejection) {
        Settings next = new Settings(enabled, Math.max(1, Math.min(4, depth)), Math.max(0, Math.min(100, randomRejection)));
        if (next.equals(settings))
            return false;
        settings = next;
        return true;
    }

    /** Fancy leaves: what Iroh's mesher draws, which follows the graphics mode (vanilla's rule). Any thread. */
    public static boolean leavesFancy() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.options == null || mc.options.graphicsMode().get() != GraphicsStatus.FAST;
    }

    /** The depth to use right now: the setting on fancy leaves, 1 on fast. */
    public static int depthNow() {
        return leavesFancy() ? settings.depth : 1;
    }

    /** The rule described in the class comment ({@link LeafRule}). Worker threads; reads {@code level} only. */
    static boolean cullFace(BlockGetter level, BlockPos pos, Direction face, Predicate<BlockState> isLeaf, int depth, float rejection) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        int dx = face.getStepX(), dy = face.getStepY(), dz = face.getStepZ();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        return LeafRule.hide(depth, i -> {
            BlockState s = level.getBlockState(p.set(x + dx * i, y + dy * i, z + dz * i));
            return s != null && isLeaf.test(s);
        }, LeafRule.seededFloat(pos.asLong()), rejection);
    }

    public static boolean cullFace(BlockGetter level, BlockPos pos, Direction face, Predicate<BlockState> isLeaf, int depth) {
        return cullFace(level, pos, face, isLeaf, depth, rejection());
    }

    private static Settings migrate() {
        Path f = FabricLoader.getInstance().getConfigDir().resolve("cull-fewer-leaves.toml");
        if (!Files.isRegularFile(f))
            return new Settings(false, 2, 0);
        boolean enabled = true;
        int depth = 2, rejection = 0;
        try {
            for (String raw : Files.readAllLines(f)) {
                String line = raw.strip();
                if (line.startsWith("#") || !line.contains("="))
                    continue;
                String key = line.substring(0, line.indexOf('=')).strip();
                String val = line.substring(line.indexOf('=') + 1).strip();
                switch (key) {
                    case "enabled" -> enabled = Boolean.parseBoolean(val);
                    case "depth" -> depth = Integer.parseInt(val);
                    case "randomRejection" -> rejection = Integer.parseInt(val);
                    default -> {
                    }
                }
            }
        } catch (Exception e) {
            Initializer.LOGGER.warn("Iroh: could not read {} for the leafCulling defaults; leaf culling stays off", f, e);
            return new Settings(false, 2, 0);
        }
        Settings s = new Settings(enabled, Math.max(1, Math.min(4, depth)), Math.max(0, Math.min(100, rejection)));
        Initializer.LOGGER.info("Iroh: leafCulling migrated from cull-fewer-leaves.toml: {}", s);
        return s;
    }
}
