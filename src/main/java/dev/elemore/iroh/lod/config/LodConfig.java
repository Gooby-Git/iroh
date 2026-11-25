package dev.elemore.iroh.lod.config;

/**
 * Live values of the Distant Terrain feature (card VL-0). Plain fields so later renderer cards read them on hot paths
 * the way they read {@code Initializer.CONFIG}. Rows and persistence are {@link LodOptions} (config/iroh.json, flat keys
 * {@code lod.*}); {@link LodMigration} fills them once from an existing Vista config. Defaults and ranges are the table
 * in docs/VISTA-IN-IROH.md section 3.
 */
public final class LodConfig {
    public static final LodConfig INSTANCE = new LodConfig();

    // range limits shared by the option rows and the migration clamp
    public static final int DISTANCE_MIN = 256, DISTANCE_MAX = 12288, DISTANCE_STEP = 256;
    public static final int DETAIL_MIN = 256, DETAIL_MAX = 2048, DETAIL_STEP = 32;
    public static final int BLEND_MAX = 96, TRANSITION_MAX = 1500;
    public static final int BRIGHTNESS_MIN = 50, BRIGHTNESS_MAX = 150, SATURATION_MAX = 150;
    public static final int FOG_MIN = 50, FOG_MAX = 300;
    public static final int MIN_PIXELS_MAX = 12, NAME_TAG_MAX = 512;
    public static final int BUDGET_MIN = 512, BUDGET_MAX = 8192, BUDGET_STEP = 256;
    public static final int THREADS_MAX = 8, UPLOAD_MIN = 1, UPLOAD_MAX = 32;
    public static final int STRUCTURE_RANGE_MAX = 8192;

    public boolean enabled = true;
    public int distance = 4096;
    public int detailDistance = 800;
    public int blendWidth = 0;
    public int transitionMs = 400;
    public boolean landmarks = true;
    public int brightness = 100;
    public int saturation = 100;
    public int fogDistance = 100;
    public boolean nightLights = true;
    public boolean motion = true;
    public boolean weatherOnLods = true;
    public boolean weatherCurtains = true;
    public boolean farPlayers = true;
    public int farPlayerMinPixels = 4;
    public int nameTagDistance = 128;
    public boolean seedLods = true;
    public boolean captureVisited = true;
    public boolean shareWithMaps = true;
    public int budgetMiB = 0;
    public boolean occlusionCulling = true;
    public int workerThreads = 0;
    public int uploadMiBPerFrame = 6;
    public boolean debugLevels = false;
    public boolean debugFreeze = false;
    public boolean serverStructurePass = true;
    public int serverStructureRange = 4096;
    public boolean serverShareSeedTerrain = true;
    public boolean serverSharePlayerPositions = true;

    public static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 0 = automatic, else 512..8192. */
    public static int normaliseBudget(int v) {
        return v <= 0 ? 0 : clamp(v, BUDGET_MIN, BUDGET_MAX);
    }

    public void reset() {
        LodConfig d = new LodConfig();
        enabled = d.enabled; distance = d.distance; detailDistance = d.detailDistance; blendWidth = d.blendWidth;
        transitionMs = d.transitionMs; landmarks = d.landmarks; brightness = d.brightness; saturation = d.saturation;
        fogDistance = d.fogDistance; nightLights = d.nightLights; motion = d.motion; weatherOnLods = d.weatherOnLods;
        weatherCurtains = d.weatherCurtains; farPlayers = d.farPlayers; farPlayerMinPixels = d.farPlayerMinPixels;
        nameTagDistance = d.nameTagDistance; seedLods = d.seedLods; captureVisited = d.captureVisited;
        shareWithMaps = d.shareWithMaps; budgetMiB = d.budgetMiB; occlusionCulling = d.occlusionCulling;
        workerThreads = d.workerThreads; uploadMiBPerFrame = d.uploadMiBPerFrame; debugLevels = d.debugLevels;
        debugFreeze = d.debugFreeze; serverStructurePass = d.serverStructurePass; serverStructureRange = d.serverStructureRange;
        serverShareSeedTerrain = d.serverShareSeedTerrain; serverSharePlayerPositions = d.serverSharePlayerPositions;
    }
}
