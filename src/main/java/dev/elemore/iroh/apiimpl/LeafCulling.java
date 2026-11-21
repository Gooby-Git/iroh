package dev.elemore.iroh.apiimpl;

import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/**
 * Iroh internal (P1-6b, P1-10): the mesher's copy of option {@code leafCulling}, Iroh's replacement for the Cull Fewer
 * Leaves mod (LGPL-3.0, read as bytecode for behaviour only, no code taken). Behaviour, in our words: a face of a
 * leaves block is hidden when every block in a straight line out of that face, up to {@code depth} blocks, is leaves
 * (depth = the setting on fancy leaves, 1 on fast leaves). When the first block out is leaves but a deeper one is not,
 * the face is hidden anyway for {@code randomRejection} percent of block positions, chosen by a fixed per-position random
 * number (the first {@code nextFloat()} of {@code java.util.Random} seeded with {@code BlockPos.asLong()}, so the same
 * blocks are picked as with the old mod and the pattern never flickers). Faces whose first neighbour is not leaves are
 * untouched.
 * <p>
 * Single source of truth: {@code config/iroh.json} ({@code IrohOptions}, migrated from {@code cull-fewer-leaves.toml}
 * by {@code PackMigration}). {@code IrohOptions.applyLeafCulling} pushes it here with {@link #set} after loading and on
 * every change (then one {@code allChanged()}). Off until then.
 */
public final class LeafCulling {
    private record Settings(boolean enabled, int depth, int randomRejection) {
    }

    private static volatile Settings settings = new Settings(false, 2, 0);
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

    /**
     * Fancy leaves: what Iroh's mesher draws, i.e. option leavesQuality (FANCY / FAST, or DEFAULT = the graphics mode),
     * the same rule IrohLeavesM gives vanilla's ItemBlockRenderTypes. Any thread.
     */
    public static boolean leavesFancy() {
        Minecraft mc = Minecraft.getInstance();
        boolean vanillaFancy = mc == null || mc.options == null || mc.options.graphicsMode().get() != GraphicsStatus.FAST;
        return dev.elemore.iroh.config.IrohOptions.leavesFancy(vanillaFancy);
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
}
