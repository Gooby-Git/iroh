package dev.elemore.iroh.apiimpl;

import dev.elemore.iroh.api.FaceCullVeto;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.vulkanmod.Initializer;

import java.util.Arrays;
import java.util.function.Predicate;

/**
 * Iroh internal (P1-6b): the chunk mesher's face-cull hooks: registered {@link FaceCullVeto}s plus the built-in leaf
 * culling. The mesher takes one {@link Snapshot} per mesh task ({@link #snapshot()}, null when there is nothing to do)
 * so an idle hook costs one null check per visible face and nothing per registration lookup.
 */
public final class FaceCullHooks {
    private static final FaceCullVeto[] NONE = new FaceCullVeto[0];
    private static final Predicate<BlockState> IS_LEAVES_BLOCK = s -> s.getBlock() instanceof LeavesBlock;

    private static volatile FaceCullVeto[] vetoes = NONE;
    private static volatile Snapshot cached;

    private FaceCullHooks() {
    }

    public static synchronized void register(FaceCullVeto veto) {
        if (veto == null)
            throw new NullPointerException("veto");
        for (FaceCullVeto v : vetoes)
            if (v == veto)
                return;
        FaceCullVeto[] next = Arrays.copyOf(vetoes, vetoes.length + 1);
        next[next.length - 1] = veto;
        vetoes = next;
        Initializer.LOGGER.info("Iroh api: face-cull veto registered: {}", veto.getClass().getName());
    }

    public static synchronized void unregister(FaceCullVeto veto) {
        FaceCullVeto[] cur = vetoes;
        for (int i = 0; i < cur.length; i++) {
            if (cur[i] == veto) {
                FaceCullVeto[] next = new FaceCullVeto[cur.length - 1];
                System.arraycopy(cur, 0, next, 0, i);
                System.arraycopy(cur, i + 1, next, i, cur.length - i - 1);
                vetoes = next.length == 0 ? NONE : next;
                return;
            }
        }
    }

    /**
     * The hooks for one mesh task, taken when the task starts (BuilderResources.update), or null when no veto is
     * registered and leaf culling is off. Any thread. Allocates only when the inputs changed since the last call.
     */
    public static Snapshot snapshot() {
        FaceCullVeto[] v = vetoes;
        int leafDepth = LeafCulling.enabled() ? LeafCulling.depthNow() : 0;
        float rejection = LeafCulling.rejection();
        if (v.length == 0 && leafDepth == 0)
            return null;
        Snapshot s = cached;
        if (s != null && s.vetoes == v && s.leafDepth == leafDepth && s.rejection == rejection)
            return s;
        s = new Snapshot(v, leafDepth, rejection);
        cached = s;
        return s;
    }

    public static final class Snapshot {
        final FaceCullVeto[] vetoes;
        final int leafDepth;
        final float rejection;

        Snapshot(FaceCullVeto[] vetoes, int leafDepth, float rejection) {
            this.vetoes = vetoes;
            this.leafDepth = leafDepth;
            this.rejection = rejection;
        }

        /** Whether to hide a face vanilla's rule left visible. Meshing worker threads. */
        public boolean hide(BlockState state, BlockGetter level, BlockPos pos, Direction face) {
            if (this.leafDepth > 0 && state.getBlock() instanceof LeavesBlock
                    && LeafCulling.cullFace(level, pos, face, IS_LEAVES_BLOCK, this.leafDepth, this.rejection))
                return true;

            FaceCullVeto[] v = this.vetoes;
            for (int i = 0; i < v.length; i++) {
                try {
                    if (v[i].hideFace(state, level, pos, face))
                        return true;
                } catch (RuntimeException | LinkageError e) {
                    // One mod's bug must not break meshing: drop that veto for good, say so once.
                    Initializer.LOGGER.error("Iroh api: face-cull veto {} threw; it is removed", v[i].getClass().getName(), e);
                    unregister(v[i]);
                }
            }
            return false;
        }
    }
}
