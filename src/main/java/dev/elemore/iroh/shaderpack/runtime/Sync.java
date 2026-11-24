package dev.elemore.iroh.shaderpack.runtime;

/**
 * B-3B: synchronization1 masks and the access codes the pack runtime's compiled barriers are made of. Values are the
 * Vulkan 1.0 VK_PIPELINE_STAGE_* / VK_ACCESS_* bits (the in-game device may lack synchronization2, RQ-APP1-a), kept
 * here as plain ints so {@link SyncPlan}, {@link SyncCheck} and {@link RecordingBackend} stay free of Vulkan.
 * <p>
 * An access code pairs the stages with the accesses of one kind of use. Owned pack images live in GENERAL for their
 * whole life, so a compiled barrier never changes a layout. Geometry and tessellation stages are never named (the
 * pack pipelines have vertex + fragment or compute only, and those stage bits need device features).
 */
public final class Sync {

    private Sync() {
    }

    // VK_PIPELINE_STAGE_*
    public static final int TOP = 0x1, VERTEX = 0x8, FRAGMENT = 0x80, EARLY_TESTS = 0x100, LATE_TESTS = 0x200,
            COLOR_OUT = 0x400, COMPUTE = 0x800, TRANSFER = 0x1000, BOTTOM = 0x2000, ALL_GRAPHICS = 0x8000, ALL_COMMANDS = 0x10000;
    // VK_ACCESS_*
    public static final int SHADER_READ = 0x20, SHADER_WRITE = 0x40, COLOR_READ = 0x80, COLOR_WRITE = 0x100, DS_READ = 0x200,
            DS_WRITE = 0x400, TRANSFER_READ = 0x800, TRANSFER_WRITE = 0x1000, MEMORY_READ = 0x8000, MEMORY_WRITE = 0x10000;

    /** every concrete stage a pack command can run in (what ALL_COMMANDS stands for in coverage checks) */
    static final int STAGES_ALL = VERTEX | FRAGMENT | EARLY_TESTS | LATE_TESTS | COLOR_OUT | COMPUTE | TRANSFER | 0x2 | 0x4 | TOP | BOTTOM;
    static final int GRAPHICS_STAGES = 0x2 | 0x4 | VERTEX | FRAGMENT | EARLY_TESTS | LATE_TESTS | COLOR_OUT;
    static final int READ_BITS = 0x1 | 0x2 | 0x4 | 0x8 | 0x10 | SHADER_READ | COLOR_READ | DS_READ | TRANSFER_READ | 0x2000 | MEMORY_READ;
    static final int WRITE_BITS = SHADER_WRITE | COLOR_WRITE | DS_WRITE | TRANSFER_WRITE | 0x4000 | MEMORY_WRITE;

    // access codes
    public static final int COLOR = 0, DEPTH = 1, GFX_R = 2, GFX_RW = 3, CS_R = 4, CS_RW = 5, XFER_R = 6, XFER_W = 7, XFER_RW = 8, ANY = 9;
    static final String[] NAME = {"COLOR", "DEPTH", "GFX_R", "GFX_RW", "CS_R", "CS_RW", "XFER_R", "XFER_W", "XFER_RW", "ANY"};
    static final int[] STAGE = {COLOR_OUT, EARLY_TESTS | LATE_TESTS, VERTEX | FRAGMENT, VERTEX | FRAGMENT, COMPUTE, COMPUTE,
            TRANSFER, TRANSFER, TRANSFER, ALL_COMMANDS};
    /** every access of the use (the destination mask of a barrier before it) */
    static final int[] ACCESS = {COLOR_READ | COLOR_WRITE, DS_READ | DS_WRITE, SHADER_READ, SHADER_READ | SHADER_WRITE, SHADER_READ,
            SHADER_READ | SHADER_WRITE, TRANSFER_READ, TRANSFER_WRITE, TRANSFER_READ | TRANSFER_WRITE, MEMORY_READ | MEMORY_WRITE};
    /** the write accesses of a writing use (the source mask of the barrier after it), 0 = a read */
    static final int[] WRITE_ACCESS = {COLOR_WRITE, DS_WRITE, 0, SHADER_WRITE, 0, SHADER_WRITE, 0, TRANSFER_WRITE, TRANSFER_WRITE, MEMORY_WRITE};

    // read classes: where a write has been made visible (one bit per reading code family)
    static final int R_GFX = 1, R_CS = 2, R_XFER = 4, R_ALL = 7;
    static final int[] READ_CLASS = {0, 0, R_GFX, 0, R_CS, 0, R_XFER, 0, 0, 0};
    static final int[] CLASS_STAGE = {VERTEX | FRAGMENT, COMPUTE, TRANSFER}, CLASS_ACCESS = {SHADER_READ, SHADER_READ, TRANSFER_READ};

    /** resource ids: images are backend image handles, buffers are {@code BUF | handle} */
    public static final int BUF = 1 << 30;

    // what a command is (how an access kind string maps to a code)
    public static final int PIPE_DRAW = 0, PIPE_DISPATCH = 1, PIPE_XFER = 2, PIPE_MIPS = 3, PIPE_ANY = 4;

    static boolean isWrite(int code) {
        return WRITE_ACCESS[code] != 0;
    }

    static int expandStages(int m) {
        if ((m & ALL_COMMANDS) != 0)
            return STAGES_ALL;
        if ((m & ALL_GRAPHICS) != 0)
            m |= GRAPHICS_STAGES;
        return m;
    }

    static int expandAccess(int a) {
        if ((a & MEMORY_READ) != 0)
            a |= READ_BITS;
        if ((a & MEMORY_WRITE) != 0)
            a |= WRITE_BITS;
        return a;
    }

    static boolean coversStages(int have, int need) {
        int n = expandStages(need);
        return (expandStages(have) & n) == n;
    }

    static boolean coversAccess(int have, int need) {
        int n = expandAccess(need) & ~(MEMORY_READ | MEMORY_WRITE);
        return (expandAccess(have) & n) == n;
    }

    /** the read classes a memory dependency with these destination masks makes a write visible to */
    static int classesCovered(int dstStages, int dstAccess) {
        int c = 0;
        for (int k = 0; k < 3; k++)
            if (coversStages(dstStages, CLASS_STAGE[k]) && coversAccess(dstAccess, CLASS_ACCESS[k]))
                c |= 1 << k;
        return c;
    }

    /**
     * the code of an executor access declaration ({@link GpuBackend} access kinds) in a command of type {@code pipe};
     * {@code depth}: the image has a depth format (its attachment use is the depth-test stages)
     */
    static int code(String kind, int pipe, boolean depth) {
        if (pipe == PIPE_ANY)
            return ANY;
        switch (kind) {
            case "ATTACHMENT_WRITE":
                return depth ? DEPTH : COLOR;
            case "SAMPLED_READ":
            case "STORAGE_READ":
                return pipe == PIPE_DISPATCH ? CS_R : pipe == PIPE_DRAW ? GFX_R : ANY;
            case "STORAGE_WRITE":
                return pipe == PIPE_DISPATCH ? CS_RW : pipe == PIPE_DRAW ? GFX_RW : ANY;
            case "TRANSFER_READ":
                return pipe == PIPE_MIPS ? XFER_RW : XFER_R;
            case "TRANSFER_WRITE":
                return pipe == PIPE_MIPS ? XFER_RW : XFER_W;
            default:
                return ANY;
        }
    }

    static String stages(int m) {
        StringBuilder sb = new StringBuilder();
        String[] n = {"TOP", "INDIRECT", "VERTEX_INPUT", "VERTEX", "TCS", "TES", "GEOMETRY", "FRAGMENT", "EARLY_TESTS", "LATE_TESTS",
                "COLOR_OUT", "COMPUTE", "TRANSFER", "BOTTOM", "HOST", "ALL_GRAPHICS", "ALL_COMMANDS"};
        for (int i = 0; i < n.length; i++)
            if ((m & (1 << i)) != 0)
                sb.append(sb.length() == 0 ? "" : "|").append(n[i]);
        return sb.length() == 0 ? "NONE" : sb.toString();
    }
}
