package dev.elemore.iroh.lod.config;

import dev.elemore.iroh.config.IrohOptions;
import dev.elemore.iroh.config.IrohOptions.Category;
import dev.elemore.iroh.config.IrohOptions.Opt;
import dev.elemore.iroh.lod.LodFeature;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.OptionBlock;
import net.vulkanmod.config.option.InfoOption;
import net.vulkanmod.config.option.Option;
import net.vulkanmod.config.option.Options;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The "Distant Terrain" settings rows (card VL-0): one registry entry per option of docs/VISTA-IN-IROH.md section 3,
 * persisted by {@link IrohOptions} in config/iroh.json under flat keys {@code lod.*}. Values live in
 * {@link LodConfig#INSTANCE}. Every row is greyed with its reason while the backend is not built or the Vista mod is
 * installed. Nothing here touches GLFW, Vulkan or Minecraft state during class initialisation.
 */
public final class LodOptions {
    private LodOptions() {}

    private static final LodConfig C = LodConfig.INSTANCE;
    private static final String[] GROUPS = {"general", "detail", "look", "weather", "data", "memory", "advanced", "server"};
    private static final List<Opt<?>> ROWS = new ArrayList<>();
    private static final List<String> ROW_GROUP = new ArrayList<>();

    public static Opt<Boolean> ENABLED, LANDMARKS, NIGHT_LIGHTS, MOTION, WEATHER_ON_LODS, WEATHER_CURTAINS, FAR_PLAYERS,
            SEED_LODS, CAPTURE_VISITED, SHARE_WITH_MAPS, OCCLUSION_CULLING, DEBUG_LEVELS, DEBUG_FREEZE,
            STRUCTURE_PASS, SHARE_SEED_TERRAIN, SHARE_PLAYER_POSITIONS;
    public static Opt<Integer> DISTANCE, DETAIL_DISTANCE, BLEND_WIDTH, TRANSITION_MS, BRIGHTNESS, SATURATION, FOG_DISTANCE,
            FAR_PLAYER_MIN_PIXELS, NAME_TAG_DISTANCE, BUDGET_MIB, WORKER_THREADS, UPLOAD_MIB_PER_FRAME, STRUCTURE_RANGE;

    /** Called once at the end of {@code IrohOptions}'s static initialiser. */
    public static void register() {
        ENABLED = bool("general", "lod.enabled", true, v -> C.enabled = v, () -> C.enabled);
        ENABLED.applier = v -> { C.enabled = v; LodFeature.refresh(); };
        DISTANCE = intRow("general", "lod.distance", 4096, LodConfig.DISTANCE_MIN, LodConfig.DISTANCE_MAX, LodConfig.DISTANCE_STEP,
                v -> C.distance = v, () -> C.distance, v -> Component.translatable("iroh.lod.blocks", v));
        DETAIL_DISTANCE = intRow("detail", "lod.detailDistance", 800, LodConfig.DETAIL_MIN, LodConfig.DETAIL_MAX, LodConfig.DETAIL_STEP,
                v -> C.detailDistance = v, () -> C.detailDistance, v -> Component.translatable("iroh.lod.blocks", v));
        BLEND_WIDTH = intRow("detail", "lod.blendWidth", 0, 0, LodConfig.BLEND_MAX, 2,
                v -> C.blendWidth = v, () -> C.blendWidth, v -> v == 0 ? Component.translatable("iroh.lod.cleanSwitch") : Component.translatable("iroh.lod.blocks", v));
        TRANSITION_MS = intRow("detail", "lod.transitionMs", 400, 0, LodConfig.TRANSITION_MAX, 50,
                v -> C.transitionMs = v, () -> C.transitionMs, v -> Component.literal(v + " ms"));
        LANDMARKS = bool("detail", "lod.landmarks", true, v -> C.landmarks = v, () -> C.landmarks);
        BRIGHTNESS = intRow("look", "lod.brightness", 100, LodConfig.BRIGHTNESS_MIN, LodConfig.BRIGHTNESS_MAX, 5,
                v -> C.brightness = v, () -> C.brightness, v -> Component.literal(v + "%"));
        SATURATION = intRow("look", "lod.saturation", 100, 0, LodConfig.SATURATION_MAX, 5,
                v -> C.saturation = v, () -> C.saturation, v -> Component.literal(v + "%"));
        FOG_DISTANCE = intRow("look", "lod.fogDistance", 100, LodConfig.FOG_MIN, LodConfig.FOG_MAX, 10,
                v -> C.fogDistance = v, () -> C.fogDistance, v -> Component.literal(v + "%"));
        NIGHT_LIGHTS = bool("look", "lod.nightLights", true, v -> C.nightLights = v, () -> C.nightLights);
        MOTION = bool("look", "lod.motion", true, v -> C.motion = v, () -> C.motion);
        WEATHER_ON_LODS = bool("weather", "lod.weatherOnLods", true, v -> C.weatherOnLods = v, () -> C.weatherOnLods);
        WEATHER_ON_LODS.unavailable = () -> reason(true, false);
        WEATHER_CURTAINS = bool("weather", "lod.weatherCurtains", true, v -> C.weatherCurtains = v, () -> C.weatherCurtains);
        WEATHER_CURTAINS.unavailable = () -> reason(true, false);
        FAR_PLAYERS = bool("weather", "lod.farPlayers", true, v -> C.farPlayers = v, () -> C.farPlayers);
        FAR_PLAYER_MIN_PIXELS = intRow("weather", "lod.farPlayerMinPixels", 4, 0, LodConfig.MIN_PIXELS_MAX, 1,
                v -> C.farPlayerMinPixels = v, () -> C.farPlayerMinPixels, v -> v == 0 ? Component.translatable("iroh.lod.trueSize") : Component.literal(v + " px"));
        NAME_TAG_DISTANCE = intRow("weather", "lod.nameTagDistance", 128, 0, LodConfig.NAME_TAG_MAX, 16,
                v -> C.nameTagDistance = v, () -> C.nameTagDistance, v -> v == 0 ? Component.translatable("options.off") : Component.translatable("iroh.lod.blocks", v));
        SEED_LODS = bool("data", "lod.seedLods", true, v -> C.seedLods = v, () -> C.seedLods);
        CAPTURE_VISITED = bool("data", "lod.captureVisited", true, v -> C.captureVisited = v, () -> C.captureVisited);
        SHARE_WITH_MAPS = bool("data", "lod.shareWithMaps", true, v -> C.shareWithMaps = v, () -> C.shareWithMaps);
        SHARE_WITH_MAPS.unavailable = () -> reason(false, true);
        BUDGET_MIB = intRow("memory", "lod.budgetMiB", 0, 0, LodConfig.BUDGET_MAX, LodConfig.BUDGET_STEP,
                v -> C.budgetMiB = LodConfig.normaliseBudget(v), () -> C.budgetMiB,
                v -> v == 0 ? Component.translatable("iroh.lod.auto") : Component.literal(v + " MiB"));
        OCCLUSION_CULLING = bool("memory", "lod.occlusionCulling", true, v -> C.occlusionCulling = v, () -> C.occlusionCulling);
        WORKER_THREADS = intRow("memory", "lod.workerThreads", 0, 0, LodConfig.THREADS_MAX, 1,
                v -> C.workerThreads = v, () -> C.workerThreads, v -> v == 0 ? Component.translatable("iroh.lod.auto") : Component.literal(String.valueOf(v)));
        UPLOAD_MIB_PER_FRAME = intRow("advanced", "lod.uploadMiBPerFrame", 6, LodConfig.UPLOAD_MIN, LodConfig.UPLOAD_MAX, 1,
                v -> C.uploadMiBPerFrame = v, () -> C.uploadMiBPerFrame, v -> Component.literal(v + " MiB"));
        DEBUG_LEVELS = bool("advanced", "lod.debugLevels", false, v -> C.debugLevels = v, () -> C.debugLevels);
        DEBUG_FREEZE = bool("advanced", "lod.debugFreeze", false, v -> C.debugFreeze = v, () -> C.debugFreeze);
        STRUCTURE_PASS = bool("server", "lod.server.structurePass", true, v -> C.serverStructurePass = v, () -> C.serverStructurePass);
        STRUCTURE_RANGE = intRow("server", "lod.server.structureRange", 4096, 0, LodConfig.STRUCTURE_RANGE_MAX, 256,
                v -> C.serverStructureRange = v, () -> C.serverStructureRange, v -> Component.translatable("iroh.lod.blocks", v));
        SHARE_SEED_TERRAIN = bool("server", "lod.server.shareSeedTerrain", true, v -> C.serverShareSeedTerrain = v, () -> C.serverShareSeedTerrain);
        SHARE_PLAYER_POSITIONS = bool("server", "lod.server.sharePlayerPositions", true, v -> C.serverSharePlayerPositions = v, () -> C.serverSharePlayerPositions);
    }

    /** Greyed-reason: the feature-wide one first, then the optional "needs Tempest" / "no map bridge" ones. */
    private static Component reason(boolean needsTempest, boolean needsBridge) {
        String k = LodFeature.rowsReasonKey();
        if (k != null) return Component.translatable(k);
        if (needsTempest && !LodFeature.tempestInstalled()) return Component.translatable("iroh.lod.reason.tempest");
        if (needsBridge) return Component.translatable("iroh.lod.reason.noBridge");
        return null;
    }

    private static Opt<Boolean> bool(String group, String id, boolean def, java.util.function.Consumer<Boolean> store, Supplier<Boolean> get) {
        Opt<Boolean> o = new Opt<>(id, Boolean.class, def, Category.DISTANT_TERRAIN);
        o.getter = get;
        o.store = store;
        o.unavailable = () -> reason(false, false);
        return add(group, o);
    }

    private static Opt<Integer> intRow(String group, String id, int def, int min, int max, int step,
                                       java.util.function.Consumer<Integer> store, Supplier<Integer> get, Function<Integer, Component> label) {
        Opt<Integer> o = new Opt<>(id, Integer.class, def, Category.DISTANT_TERRAIN);
        o.getter = get;
        o.store = store;
        o.range(min, max, step);
        o.label = label;
        o.unavailable = () -> reason(false, false);
        return add(group, o);
    }

    private static <T> Opt<T> add(String group, Opt<T> o) {
        IrohOptions.register(o);
        ROWS.add(o);
        ROW_GROUP.add(group);
        return o;
    }

    /** All rows, in tab order. */
    public static List<Opt<?>> rows() { return List.copyOf(ROWS); }

    /** The tab: a status block, then one block per group. */
    public static OptionBlock[] page() {
        List<OptionBlock> out = new ArrayList<>();
        InfoOption status = new InfoOption(Component.translatable("iroh.lod.status"), () -> {
            String why = LodFeature.offReason();
            return why == null ? Component.translatable("iroh.lod.status.on")
                    : Component.translatable("iroh.lod.status.off", Component.literal(why));
        });
        status.setTooltip(Component.translatable("iroh.lod.status.tooltip"));
        InfoOption natives = new InfoOption(Component.translatable("iroh.lod.native"), () -> Component.translatable("iroh.lod.native.none"));
        natives.setTooltip(Component.translatable("iroh.lod.native.tooltip"));
        out.add(new OptionBlock("", new Option<?>[]{status, natives}));
        for (String g : GROUPS) {
            List<Option<?>> opts = new ArrayList<>();
            for (int i = 0; i < ROWS.size(); i++)
                if (ROW_GROUP.get(i).equals(g)) opts.add(Options.toOption(ROWS.get(i)));
            if (!opts.isEmpty())
                out.add(new OptionBlock(Component.translatable("iroh.lod.group." + g).getString(), opts.toArray(new Option<?>[0])));
        }
        return out.toArray(new OptionBlock[0]);
    }
}
