package dev.elemore.iroh.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.video.VideoModeSet;
import net.vulkanmod.config.video.WindowMode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Iroh's typed option registry (card P1-6). One {@link Opt} per option: id, type, default, range or choices, category,
 * tooltip key, what applying it costs (restart, chunk reload, resource reload), an availability predicate with a reason
 * (a reason makes the screen show the option greyed with that text) and how it is serialised to {@code config/iroh.json}.
 * The live values stay in {@link Config} (the renderer reads {@code Initializer.CONFIG.field} on hot paths); the registry
 * binds each option to its field. Options stored by vanilla ({@code options.txt}) are listed too, with
 * {@link Storage#VANILLA}, so the screen is built from one model; they are not written to iroh.json.
 *
 * Nothing here touches GLFW or Vulkan during class initialisation (the migration test runs headless).
 */
public final class IrohOptions {
    private IrohOptions() {}

    public static final Logger LOGGER = LogManager.getLogger("Iroh");
    public static final String FILE = "iroh.json";
    public static final String LEGACY_FILE = "vulkanmod_settings.json";

    public enum Category { VIDEO, QUALITY, PERFORMANCE, SHADERS, ADVANCED }
    public enum Reload { NONE, RESTART, RELOAD_CHUNKS, RELOAD_RESOURCES }
    /** JSON: config/iroh.json. VANILLA: options.txt. READ_ONLY: shown, never edited from the screen. */
    public enum Storage { JSON, VANILLA, READ_ONLY }
    public enum LeavesQuality { DEFAULT, FAST, FANCY }
    public enum DeferMode { ALWAYS, ONE_FRAME, ZERO_FRAMES }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Opt<?>> BY_ID = new LinkedHashMap<>();

    private static Config config = new Config();
    private static Path configDir;
    private static boolean restartPending;

    // =====================================================================================================
    // The option type
    // =====================================================================================================

    public static final class Opt<T> {
        public final String id;
        public final Class<T> type;
        public final T defaultValue;
        public final Category category;
        public Storage storage = Storage.JSON;
        public Reload reload = Reload.NONE;
        public String nameKey;
        public String tooltipKey;
        /** INT options: slider range when {@link #choices} is null. */
        public int min, max, step = 1;
        public IntSupplier maxSupplier;
        /** Cycling choices; when {@link #strictChoices}, a value outside the list is rejected when read from the file. */
        public List<T> choices;
        public boolean strictChoices = true;
        public Function<T, Component> label;
        /** Reads the live value. */
        public Supplier<T> getter;
        /** Pure assignment, no side effects (used when reading the file). */
        public Consumer<T> store;
        /** What the screen runs on Apply, if more than {@code store} (side effects). */
        public Consumer<T> applier;
        /** Non-null result greys the whole option and is shown as its reason. */
        public Supplier<Component> unavailable;
        /** Non-null result greys one choice of a cycling option and is shown as its reason. */
        public Function<T, Component> choiceUnavailable;
        /** Extra tooltip lines (translation key), appended after the main tooltip. */
        public String noteKey;

        private Opt(String id, Class<T> type, T def, Category cat) {
            this.id = id;
            this.type = type;
            this.defaultValue = def;
            this.category = cat;
            this.nameKey = "iroh.options." + id;
            this.tooltipKey = "iroh.options." + id + ".tooltip";
        }

        public Opt<T> storage(Storage s) { this.storage = s; return this; }
        public Opt<T> reload(Reload r) { this.reload = r; return this; }
        public Opt<T> name(String key) { this.nameKey = key; return this; }
        public Opt<T> tooltip(String key) { this.tooltipKey = key; return this; }
        public Opt<T> note(String key) { this.noteKey = key; return this; }
        public Opt<T> range(int min, int max, int step) { this.min = min; this.max = max; this.step = step; return this; }
        public Opt<T> range(int min, IntSupplier max, int step) { this.min = min; this.maxSupplier = max; this.max = min + 1; this.step = step; return this; }
        @SafeVarargs public final Opt<T> choices(T... values) { this.choices = List.of(values); return this; }
        public Opt<T> choices(List<T> values) { this.choices = values; return this; }
        public Opt<T> loose() { this.strictChoices = false; return this; }
        public Opt<T> label(Function<T, Component> f) { this.label = f; return this; }
        public Opt<T> applier(Consumer<T> f) { this.applier = f; return this; }
        public Opt<T> unavailable(Supplier<Component> s) { this.unavailable = s; return this; }
        public Opt<T> choiceUnavailable(Function<T, Component> f) { this.choiceUnavailable = f; return this; }

        public T get() { return getter.get(); }

        public int maxValue() { return maxSupplier != null ? maxSupplier.getAsInt() : max; }

        /** Component naming this option. */
        public Component displayName() { return Component.translatable(nameKey); }

        /** Reason the whole option cannot be changed, or null. */
        public Component unavailableReason() { return unavailable == null ? null : unavailable.get(); }

        /** Reason one choice cannot be picked, or null. */
        public Component choiceReason(T value) { return choiceUnavailable == null ? null : choiceUnavailable.apply(value); }

        /** Apply a value chosen on the screen: side effects, then the reload the option declares. */
        public void apply(T value) {
            if (applier != null) {
                applier.accept(value);
            } else if (store != null) {
                store.accept(value);
                runReload(reload);
            }
            if (reload == Reload.RESTART) restartPending = true;
        }

        // ---- serialisation ----

        JsonElement toJson() {
            Object v = getter.get();
            if (v instanceof Enum<?> e) return GSON.toJsonTree(e.name());
            return GSON.toJsonTree(v, type);
        }

        /** Read from the file, validate, assign. Returns false (and keeps the value) when the file holds nonsense. */
        @SuppressWarnings({"unchecked", "rawtypes"})
        boolean fromJson(JsonElement e) {
            try {
                T v;
                if (type.isEnum()) v = (T) Enum.valueOf((Class) type, e.getAsString());
                else if (type == Boolean.class) v = (T) Boolean.valueOf(e.getAsBoolean());
                else if (type == Integer.class) v = (T) Integer.valueOf(e.getAsInt());
                else if (type == String.class) v = (T) e.getAsString();
                else v = GSON.fromJson(e, type);
                if (v == null) return false;
                if (type == Integer.class && (choices == null || !strictChoices) && (max > min || maxSupplier != null)) {
                    int i = Math.max(min, Math.min(maxValue(), (Integer) v));
                    v = (T) Integer.valueOf(i);
                }
                if (choices != null && strictChoices && !choices.contains(v)) return false;
                store.accept(v);
                return true;
            } catch (RuntimeException ex) {
                return false;
            }
        }
    }

    // =====================================================================================================
    // Registration helpers
    // =====================================================================================================

    private static <T> Opt<T> reg(Opt<T> o) {
        if (BY_ID.put(o.id, o) != null) throw new IllegalStateException("duplicate option id " + o.id);
        return o;
    }

    private static Opt<Boolean> bool(String id, Category c, boolean def, Function<Config, Boolean> g, BiConsumer<Config, Boolean> s) {
        Opt<Boolean> o = new Opt<>(id, Boolean.class, def, c);
        o.getter = () -> g.apply(config);
        o.store = v -> s.accept(config, v);
        return reg(o);
    }

    private static Opt<Integer> integer(String id, Category c, int def, Function<Config, Integer> g, BiConsumer<Config, Integer> s) {
        Opt<Integer> o = new Opt<>(id, Integer.class, def, c);
        o.getter = () -> g.apply(config);
        o.store = v -> s.accept(config, v);
        return reg(o);
    }

    private static <E extends Enum<E>> Opt<E> enumOpt(String id, Category c, Class<E> type, E def, Function<Config, E> g, BiConsumer<Config, E> s) {
        Opt<E> o = new Opt<>(id, type, def, c);
        o.getter = () -> g.apply(config);
        o.store = v -> s.accept(config, v);
        o.choices = List.of(type.getEnumConstants());
        return reg(o);
    }

    private static <T> Opt<T> vanilla(String id, Category c, Class<T> type, T def, Supplier<T> g, Consumer<T> apply) {
        Opt<T> o = new Opt<>(id, type, def, c);
        o.storage = Storage.VANILLA;
        o.getter = g;
        o.applier = apply;
        return reg(o);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String s, E def) {
        try {
            return Enum.valueOf(type, s);
        } catch (RuntimeException e) {
            return def;
        }
    }

    // =====================================================================================================
    // The options
    // =====================================================================================================

    // ---- ported from VulkanMod (persisted; defaults unchanged) ----
    public static final Opt<VideoModeSet.VideoMode> VIDEO_MODE;
    public static final Opt<WindowMode> WINDOW_MODE;
    public static final Opt<Integer> ADV_CULLING;
    public static final Opt<Boolean> ENTITY_CULLING;
    public static final Opt<Boolean> UNIQUE_OPAQUE_LAYER;
    public static final Opt<Boolean> BACKFACE_CULLING;
    public static final Opt<Boolean> INDIRECT_DRAW;
    public static final Opt<Integer> AMBIENT_OCCLUSION;
    public static final Opt<Integer> BUILDER_THREADS;
    public static final Opt<Integer> FRAME_QUEUE_SIZE;
    public static final Opt<Integer> DEVICE;

    // ---- the pack's Sodium / Sodium Extra / Iris / Cull Fewer Leaves settings ----
    public static final Opt<LeavesQuality> LEAVES_QUALITY;
    public static final Opt<Boolean> ENABLE_VIGNETTE;
    public static final Opt<Boolean> SHOW_FPS;
    public static final Opt<Integer> FOG_DISTANCE_CHUNKS;
    public static final Opt<Integer> LEAF_CULLING_DEPTH;
    public static final Opt<Integer> LEAF_CULLING_REJECTION;
    public static final Opt<DeferMode> CHUNK_BUILD_DEFER_MODE;
    public static final Opt<String> SHADER_PACK;
    public static final Opt<Boolean> SHADERS_ENABLED;

    // ---- stored by vanilla, shown on the screen ----
    public static final Opt<Integer> FRAMERATE_LIMIT;
    public static final Opt<Boolean> VSYNC;
    public static final Opt<Integer> GUI_SCALE;
    public static final Opt<Integer> GAMMA;
    public static final Opt<Boolean> VIEW_BOBBING;
    public static final Opt<net.minecraft.client.AttackIndicatorStatus> ATTACK_INDICATOR;
    public static final Opt<Boolean> AUTOSAVE_INDICATOR;
    public static final Opt<Integer> RENDER_DISTANCE;
    public static final Opt<Integer> SIMULATION_DISTANCE;
    public static final Opt<net.minecraft.client.PrioritizeChunkUpdates> PRIORITIZE_CHUNK_UPDATES;
    public static final Opt<GraphicsStatus> GRAPHICS;
    public static final Opt<net.minecraft.client.ParticleStatus> PARTICLES;
    public static final Opt<net.minecraft.client.CloudStatus> CLOUDS;
    public static final Opt<Boolean> ENTITY_SHADOWS;
    public static final Opt<Integer> ENTITY_DISTANCE_SCALING;
    public static final Opt<Integer> BIOME_BLEND;
    public static final Opt<Integer> MIPMAPS;

    static {
        VIDEO_MODE = reg(new Opt<>("videoMode", VideoModeSet.VideoMode.class, null, Category.VIDEO));
        VIDEO_MODE.getter = () -> config.videoMode;
        VIDEO_MODE.store = v -> config.videoMode = v;

        WINDOW_MODE = enumOpt("windowMode", Category.VIDEO, WindowMode.class, WindowMode.WINDOWED,
                c -> WindowMode.fromValue(c.windowMode), (c, v) -> c.windowMode = v.mode);
        WINDOW_MODE.name("vulkanmod.options.windowMode").tooltip("iroh.options.windowMode.tooltip");
        WINDOW_MODE.label(v -> Component.translatable(WindowMode.getComponentName(v)));

        ADV_CULLING = integer("advCulling", Category.PERFORMANCE, 2, c -> c.advCulling, (c, v) -> c.advCulling = v)
                .name("vulkanmod.options.advCulling").tooltip("vulkanmod.options.advCulling.tooltip");
        ADV_CULLING.choices(1, 2, 3, 10).label(v -> Component.translatable(switch (v) {
            case 1 -> "vulkanmod.options.advCulling.aggressive";
            case 2 -> "vulkanmod.options.advCulling.normal";
            case 3 -> "vulkanmod.options.advCulling.conservative";
            case 10 -> "options.off";
            default -> "vulkanmod.options.unknown";
        }));

        ENTITY_CULLING = bool("entityCulling", Category.PERFORMANCE, true, c -> c.entityCulling, (c, v) -> c.entityCulling = v)
                .name("vulkanmod.options.entityCulling").tooltip("vulkanmod.options.entityCulling.tooltip");
        UNIQUE_OPAQUE_LAYER = bool("uniqueOpaqueLayer", Category.PERFORMANCE, true, c -> c.uniqueOpaqueLayer, (c, v) -> c.uniqueOpaqueLayer = v)
                .name("vulkanmod.options.uniqueOpaqueLayer").tooltip("vulkanmod.options.uniqueOpaqueLayer.tooltip");
        UNIQUE_OPAQUE_LAYER.reload(Reload.RELOAD_CHUNKS);
        BACKFACE_CULLING = bool("backFaceCulling", Category.PERFORMANCE, true, c -> c.backFaceCulling, (c, v) -> c.backFaceCulling = v)
                .name("vulkanmod.options.backfaceCulling").tooltip("vulkanmod.options.backfaceCulling.tooltip");
        BACKFACE_CULLING.reload(Reload.RELOAD_CHUNKS);
        INDIRECT_DRAW = bool("indirectDraw", Category.PERFORMANCE, true, c -> c.indirectDraw, (c, v) -> c.indirectDraw = v)
                .name("vulkanmod.options.indirectDraw").tooltip("vulkanmod.options.indirectDraw.tooltip");

        AMBIENT_OCCLUSION = integer("ambientOcclusion", Category.QUALITY, 1, c -> c.ambientOcclusion, (c, v) -> c.ambientOcclusion = v)
                .name("options.ao").tooltip("iroh.options.ambientOcclusion.tooltip");
        AMBIENT_OCCLUSION.choices(0, 1, 2).reload(Reload.RELOAD_CHUNKS).label(v -> Component.translatable(switch (v) {
            case 0 -> "options.off";
            case 1 -> "options.on";
            case 2 -> "vulkanmod.options.ao.subBlock";
            default -> "vulkanmod.options.unknown";
        }));

        BUILDER_THREADS = integer("builderThreads", Category.PERFORMANCE, 0, c -> c.builderThreads, (c, v) -> c.builderThreads = v)
                .name("vulkanmod.options.builderThreads");
        BUILDER_THREADS.range(0, () -> Math.max(1, Runtime.getRuntime().availableProcessors() - 1), 1)
                .label(v -> v == 0 ? Component.translatable("vulkanmod.options.builderThreads.auto") : Component.literal(String.valueOf(v)));

        FRAME_QUEUE_SIZE = integer("frameQueueSize", Category.PERFORMANCE, 2, c -> c.frameQueueSize, (c, v) -> c.frameQueueSize = v)
                .name("vulkanmod.options.frameQueue").tooltip("vulkanmod.options.frameQueue.tooltip");
        FRAME_QUEUE_SIZE.range(2, 5, 1);

        DEVICE = integer("device", Category.ADVANCED, -1, c -> c.device, (c, v) -> c.device = v)
                .name("vulkanmod.options.deviceSelector");
        DEVICE.reload(Reload.RESTART).loose();
        DEVICE.min = -1;
        DEVICE.max = 64;

        // ---- pack options ----
        LEAVES_QUALITY = enumOpt("leavesQuality", Category.QUALITY, LeavesQuality.class, LeavesQuality.DEFAULT,
                c -> parse(LeavesQuality.class, c.leavesQuality, LeavesQuality.DEFAULT), (c, v) -> c.leavesQuality = v.name());
        LEAVES_QUALITY.label(v -> Component.translatable("iroh.options.leavesQuality." + v.name().toLowerCase()));
        LEAVES_QUALITY.applier = v -> {
            config.leavesQuality = v.name();
            refreshLeaves(true);
        };

        ENABLE_VIGNETTE = bool("enableVignette", Category.QUALITY, true, c -> c.enableVignette, (c, v) -> c.enableVignette = v);
        SHOW_FPS = bool("showFps", Category.VIDEO, false, c -> c.showFps, (c, v) -> c.showFps = v);

        FOG_DISTANCE_CHUNKS = integer("fogDistanceChunks", Category.QUALITY, 0, c -> c.fogDistanceChunks, (c, v) -> c.fogDistanceChunks = v);
        FOG_DISTANCE_CHUNKS.choices(-1, 0, 4, 8, 12, 16, 24, 32).loose().range(-1, 256, 1);
        FOG_DISTANCE_CHUNKS.label(v -> v == -1 ? Component.translatable("options.off")
                : v == 0 ? Component.translatable("iroh.options.fogDistanceChunks.vanilla")
                : Component.translatable("iroh.options.fogDistanceChunks.chunks", v));
        FOG_DISTANCE_CHUNKS.choiceUnavailable(v -> v > 0 ? Component.translatable("iroh.reason.unverified") : null);

        LEAF_CULLING_DEPTH = integer("leafCullingDepth", Category.QUALITY, 2, c -> c.leafCullingDepth, (c, v) -> c.leafCullingDepth = v);
        LEAF_CULLING_DEPTH.range(0, 4, 1).reload(Reload.RELOAD_CHUNKS)
                .label(v -> v == 0 ? Component.translatable("options.off") : Component.literal(String.valueOf(v)))
                .note("iroh.note.mesherHook");
        LEAF_CULLING_REJECTION = integer("leafCullingRejection", Category.QUALITY, 0, c -> c.leafCullingRejection, (c, v) -> c.leafCullingRejection = v);
        LEAF_CULLING_REJECTION.range(0, 100, 5).reload(Reload.RELOAD_CHUNKS)
                .label(v -> Component.literal(v + "%")).note("iroh.note.mesherHook");

        CHUNK_BUILD_DEFER_MODE = enumOpt("chunkBuildDeferMode", Category.PERFORMANCE, DeferMode.class, DeferMode.ALWAYS,
                c -> parse(DeferMode.class, c.chunkBuildDeferMode, DeferMode.ALWAYS), (c, v) -> c.chunkBuildDeferMode = v.name());
        CHUNK_BUILD_DEFER_MODE.label(v -> Component.translatable("iroh.options.chunkBuildDeferMode." + v.name().toLowerCase()))
                .note("iroh.note.schedulerHook");

        SHADER_PACK = stringOpt("shaderPack");
        SHADERS_ENABLED = bool("shadersEnabled", Category.SHADERS, false, c -> c.shadersEnabled, (c, v) -> c.shadersEnabled = v);
        SHADERS_ENABLED.storage = Storage.READ_ONLY;

        // ---- vanilla-backed ----
        FRAMERATE_LIMIT = vanilla("framerateLimit", Category.VIDEO, Integer.class, 120,
                () -> mc().options.framerateLimit().get(),
                v -> { mc().options.framerateLimit().set(v); mc().getWindow().setFramerateLimit(v); });
        FRAMERATE_LIMIT.name("options.framerateLimit").range(10, 260, 10)
                .label(v -> v == 260 ? Component.translatable("options.framerateLimit.max") : Component.literal(String.valueOf(v)));
        VSYNC = vanilla("vsync", Category.VIDEO, Boolean.class, true,
                () -> mc().options.enableVsync().get(),
                v -> { mc().options.enableVsync().set(v); mc().getWindow().updateVsync(v); });
        VSYNC.name("options.vsync");
        GUI_SCALE = vanilla("guiScale", Category.VIDEO, Integer.class, 0,
                () -> mc().options.guiScale().get(),
                v -> { mc().options.guiScale().set(v); mc().resizeDisplay(); });
        GUI_SCALE.name("options.guiScale").range(0, () -> mc().getWindow().calculateScale(0, mc().isEnforceUnicode()), 1)
                .label(v -> Component.translatable(v == 0 ? "options.guiScale.auto" : String.valueOf(v)));
        GAMMA = vanilla("gamma", Category.VIDEO, Integer.class, 50,
                () -> (int) (mc().options.gamma().get() * 100.0),
                v -> mc().options.gamma().set(v * 0.01));
        GAMMA.name("options.gamma").range(0, 100, 1).label(v -> Component.translatable(switch (v) {
            case 0 -> "options.gamma.min";
            case 50 -> "options.gamma.default";
            case 100 -> "options.gamma.max";
            default -> String.valueOf(v);
        }));
        VIEW_BOBBING = vanilla("viewBobbing", Category.VIDEO, Boolean.class, true,
                () -> mc().options.bobView().get(), v -> mc().options.bobView().set(v));
        VIEW_BOBBING.name("options.viewBobbing");
        ATTACK_INDICATOR = vanilla("attackIndicator", Category.VIDEO, net.minecraft.client.AttackIndicatorStatus.class,
                net.minecraft.client.AttackIndicatorStatus.CROSSHAIR,
                () -> mc().options.attackIndicator().get(), v -> mc().options.attackIndicator().set(v));
        ATTACK_INDICATOR.name("options.attackIndicator").choices(net.minecraft.client.AttackIndicatorStatus.values())
                .label(v -> Component.translatable(v.getKey()));
        AUTOSAVE_INDICATOR = vanilla("autosaveIndicator", Category.VIDEO, Boolean.class, true,
                () -> mc().options.showAutosaveIndicator().get(), v -> mc().options.showAutosaveIndicator().set(v));
        AUTOSAVE_INDICATOR.name("options.autosaveIndicator");

        RENDER_DISTANCE = vanilla("renderDistance", Category.QUALITY, Integer.class, 12,
                () -> mc().options.renderDistance().get(), v -> mc().options.renderDistance().set(v));
        RENDER_DISTANCE.name("options.renderDistance").range(2, 32, 1);
        SIMULATION_DISTANCE = vanilla("simulationDistance", Category.QUALITY, Integer.class, 12,
                () -> mc().options.simulationDistance().get(), v -> mc().options.simulationDistance().set(v));
        SIMULATION_DISTANCE.name("options.simulationDistance").range(5, 32, 1);
        PRIORITIZE_CHUNK_UPDATES = vanilla("prioritizeChunkUpdates", Category.QUALITY, net.minecraft.client.PrioritizeChunkUpdates.class,
                net.minecraft.client.PrioritizeChunkUpdates.NONE,
                () -> mc().options.prioritizeChunkUpdates().get(), v -> mc().options.prioritizeChunkUpdates().set(v));
        PRIORITIZE_CHUNK_UPDATES.name("options.prioritizeChunkUpdates").choices(net.minecraft.client.PrioritizeChunkUpdates.values())
                .label(v -> Component.translatable(v.getKey()));
        GRAPHICS = vanilla("graphics", Category.QUALITY, GraphicsStatus.class, GraphicsStatus.FANCY,
                () -> mc().options.graphicsMode().get(), v -> { mc().options.graphicsMode().set(v); refreshLeaves(false); });
        GRAPHICS.name("options.graphics").choices(GraphicsStatus.FAST, GraphicsStatus.FANCY, GraphicsStatus.FABULOUS)
                .label(v -> Component.translatable(v.getKey()))
                .choiceUnavailable(v -> v == GraphicsStatus.FABULOUS ? Component.translatable("iroh.reason.fabulous") : null);
        PARTICLES = vanilla("particles", Category.QUALITY, net.minecraft.client.ParticleStatus.class, net.minecraft.client.ParticleStatus.ALL,
                () -> mc().options.particles().get(), v -> mc().options.particles().set(v));
        PARTICLES.name("options.particles").choices(net.minecraft.client.ParticleStatus.MINIMAL, net.minecraft.client.ParticleStatus.DECREASED, net.minecraft.client.ParticleStatus.ALL)
                .label(v -> Component.translatable(v.getKey()));
        CLOUDS = vanilla("clouds", Category.QUALITY, net.minecraft.client.CloudStatus.class, net.minecraft.client.CloudStatus.FANCY,
                () -> mc().options.cloudStatus().get(), v -> mc().options.cloudStatus().set(v));
        CLOUDS.name("options.renderClouds").choices(net.minecraft.client.CloudStatus.values()).label(v -> Component.translatable(v.getKey()));
        ENTITY_SHADOWS = vanilla("entityShadows", Category.QUALITY, Boolean.class, true,
                () -> mc().options.entityShadows().get(), v -> mc().options.entityShadows().set(v));
        ENTITY_SHADOWS.name("options.entityShadows");
        ENTITY_DISTANCE_SCALING = vanilla("entityDistanceScaling", Category.QUALITY, Integer.class, 100,
                () -> (int) Math.round(mc().options.entityDistanceScaling().get() * 100.0),
                v -> mc().options.entityDistanceScaling().set(v * 0.01));
        ENTITY_DISTANCE_SCALING.name("options.entityDistanceScaling").range(50, 500, 25)
                .label(v -> Component.literal(v + "%"));
        BIOME_BLEND = vanilla("biomeBlendRadius", Category.QUALITY, Integer.class, 2,
                () -> mc().options.biomeBlendRadius().get(),
                v -> { mc().options.biomeBlendRadius().set(v); mc().levelRenderer.allChanged(); });
        BIOME_BLEND.name("options.biomeBlendRadius").range(0, 7, 1)
                .label(v -> Component.literal("%d x %d".formatted(v * 2 + 1, v * 2 + 1)));
        MIPMAPS = vanilla("mipmapLevels", Category.QUALITY, Integer.class, 4,
                () -> mc().options.mipmapLevels().get(),
                v -> { mc().options.mipmapLevels().set(v); mc().updateMaxMipLevel(v); mc().delayTextureReload(); });
        MIPMAPS.name("options.mipmapLevels").choices(0, 1, 2, 3, 4).label(v -> Component.literal(String.valueOf(v)));

        // ---- finishing touches ----
        AMBIENT_OCCLUSION.applier = v -> {
            mc().options.ambientOcclusion().set(v > 0);
            config.ambientOcclusion = v;
            mc().levelRenderer.allChanged();
        };
        UNIQUE_OPAQUE_LAYER.applier = v -> {
            config.uniqueOpaqueLayer = v;
            net.vulkanmod.render.vertex.TerrainRenderType.updateMapping();
            mc().levelRenderer.allChanged();
        };
        BACKFACE_CULLING.applier = v -> {
            config.backFaceCulling = v;
            mc().levelRenderer.allChanged();
        };
        BUILDER_THREADS.applier = v -> {
            config.builderThreads = v;
            net.vulkanmod.render.chunk.WorldRenderer.getInstance().getTaskDispatcher().createThreads(v);
        };
        FRAME_QUEUE_SIZE.applier = v -> {
            config.frameQueueSize = v;
            net.vulkanmod.vulkan.Renderer.scheduleSwapChainUpdate();
        };
        WINDOW_MODE.applier = v -> {
            boolean exclusive = v == WindowMode.EXCLUSIVE_FULLSCREEN;
            mc().options.fullscreen().set(exclusive);
            config.windowMode = v.mode;
            net.vulkanmod.config.option.Options.fullscreenDirty = true;
        };
        // Settings that change what the chunk mesher produces need the sections rebuilt.
        ENABLE_VIGNETTE.applier = v -> config.enableVignette = v;
        SHOW_FPS.applier = v -> config.showFps = v;
        FOG_DISTANCE_CHUNKS.applier = v -> config.fogDistanceChunks = v;
        LEAF_CULLING_DEPTH.applier = v -> { config.leafCullingDepth = v; mc().levelRenderer.allChanged(); };
        LEAF_CULLING_REJECTION.applier = v -> { config.leafCullingRejection = v; mc().levelRenderer.allChanged(); };
        CHUNK_BUILD_DEFER_MODE.applier = v -> config.chunkBuildDeferMode = v.name();
    }

    /** shaderPack is a plain string; kept out of the generic helpers because it has no widget. */
    private static Opt<String> stringOpt(String id) {
        Opt<String> o = new Opt<>(id, String.class, "", Category.SHADERS);
        o.storage = Storage.READ_ONLY;
        o.getter = () -> config.shaderPack;
        o.store = v -> config.shaderPack = v;
        return reg(o);
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    // =====================================================================================================
    // Registry access
    // =====================================================================================================

    public static List<Opt<?>> all() { return new ArrayList<>(BY_ID.values()); }

    public static Opt<?> byId(String id) { return BY_ID.get(id); }

    public static List<Opt<?>> inCategory(Category c) {
        List<Opt<?>> out = new ArrayList<>();
        for (Opt<?> o : BY_ID.values()) if (o.category == c) out.add(o);
        return out;
    }

    public static Config config() { return config; }

    /** True once an option that needs a restart was applied in this session. */
    public static boolean restartPending() { return restartPending; }

    private static void runReload(Reload r) {
        Minecraft m = Minecraft.getInstance();
        if (m == null) return;
        switch (r) {
            case RELOAD_CHUNKS -> { if (m.levelRenderer != null) m.levelRenderer.allChanged(); }
            case RELOAD_RESOURCES -> m.delayTextureReload();
            default -> { }
        }
    }

    // =====================================================================================================
    // Hooks other code reads (static, allocation free)
    // =====================================================================================================

    /** Vignette on? Read by the Gui.renderVignette cancel (priority 1500). */
    public static boolean vignette() { return config.enableVignette; }

    public static boolean showFps() { return config.showFps; }

    /** Terrain fog setting: -1 off, 0 vanilla; any other value is unverified and reads as vanilla. */
    public static int fogDistanceChunks() {
        int v = config.fogDistanceChunks;
        return v == -1 ? -1 : 0;
    }

    /**
     * Hook for the block mesher (card owned elsewhere): percentage 0..100 of inner-layer leaves that Cull Fewer Leaves
     * kept rendered at random ({@code randomRejection}). 0 = never reject extra.
     */
    public static int leafCullingRejection() { return config.leafCullingRejection; }

    /** Hook for the block mesher: layers of leaves before the inside is culled; 0 = no leaf culling. */
    public static int leafCullingDepth() { return config.leafCullingDepth; }

    /** Hook for the chunk scheduler: how player-changed sections are rebuilt. Base behaviour is ALWAYS (all async). */
    public static DeferMode chunkBuildDeferMode() { return parse(DeferMode.class, config.chunkBuildDeferMode, DeferMode.ALWAYS); }

    /** Fancy leaves? FANCY / FAST override, DEFAULT follows the vanilla Graphics setting. */
    public static boolean leavesFancy(boolean vanillaFancy) {
        return switch (LEAVES_QUALITY.get()) {
            case FANCY -> true;
            case FAST -> false;
            default -> vanillaFancy;
        };
    }

    /** Called once when the Minecraft constructor has finished (window, Vulkan device and options exist). */
    public static void onGameStart() {
        try {
            dev.elemore.iroh.ui.CapabilityReport.get().logBlock();
        } catch (Throwable t) {
            LOGGER.warn("Capability report failed: {}", t.toString());
        }
        if (LEAVES_QUALITY.get() != LeavesQuality.DEFAULT) refreshLeaves(false);
    }

    /** Re-applies the render-type switch for leaves (vanilla's ItemBlockRenderTypes.setFancy, filtered by our mixin). */
    public static void refreshLeaves(boolean rebuild) {
        try {
            Minecraft m = Minecraft.getInstance();
            if (m == null || m.options == null) return;
            net.minecraft.client.renderer.ItemBlockRenderTypes.setFancy(Minecraft.useFancyGraphics());
            if (rebuild && m.levelRenderer != null) m.levelRenderer.allChanged();
        } catch (Throwable t) {
            LOGGER.debug("leaves refresh skipped: {}", t.toString());
        }
    }

    // =====================================================================================================
    // File
    // =====================================================================================================

    /**
     * Binds the registry to {@code cfg}, then loads {@code configDir/iroh.json}. When that file does not exist: migrate
     * {@code vulkanmod_settings.json} (kept in place), then seed the pack options from the leftover Sodium / Sodium
     * Extra / Iris / Cull Fewer Leaves config, then write iroh.json. Returns the log lines of what it did.
     */
    public static List<String> load(Config cfg, Path dir) {
        config = cfg;
        configDir = dir;
        List<String> log = new ArrayList<>();
        Path file = dir.resolve(FILE);
        if (Files.isRegularFile(file)) {
            readFile(file, log);
            return log;
        }
        log.add("no " + FILE + ": first run");
        Path legacy = dir.resolve(LEGACY_FILE);
        if (Files.isRegularFile(legacy)) {
            migrateLegacy(legacy, log);
        }
        PackMigration.apply(dir, config, log);
        save();
        return log;
    }

    private static void readFile(Path file, List<String> log) {
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Opt<?> o : BY_ID.values()) {
                if (o.storage == Storage.VANILLA) continue;
                JsonElement e = root.get(o.id);
                if (e == null || e.isJsonNull()) continue;
                if (!o.fromJson(e)) log.add("ignored bad value for " + o.id + ": " + e);
            }
        } catch (IOException | RuntimeException e) {
            log.add("could not read " + file + ": " + e);
            LOGGER.warn("Could not read {} ({}); using defaults for what it does not say", file, e.toString());
        }
    }

    private static void migrateLegacy(Path legacy, List<String> log) {
        try {
            Config old = GSON.fromJson(Files.readString(legacy, StandardCharsets.UTF_8), Config.class);
            if (old == null) return;
            config.videoMode = old.videoMode;
            config.windowMode = old.windowMode;
            config.advCulling = old.advCulling;
            config.indirectDraw = old.indirectDraw;
            config.uniqueOpaqueLayer = old.uniqueOpaqueLayer;
            config.entityCulling = old.entityCulling;
            config.device = old.device;
            config.ambientOcclusion = old.ambientOcclusion;
            config.frameQueueSize = old.frameQueueSize;
            config.builderThreads = old.builderThreads;
            config.backFaceCulling = old.backFaceCulling;
            log.add("migrated " + LEGACY_FILE + " (kept in place)");
        } catch (IOException | RuntimeException e) {
            log.add("could not migrate " + legacy + ": " + e);
        }
    }

    /** The JSON text that save() writes. */
    public static String toJsonText() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        for (Opt<?> o : BY_ID.values()) {
            if (o.storage == Storage.VANILLA) continue;
            if (o == VIDEO_MODE && config.videoMode == null) continue;
            root.add(o.id, o.toJson());
        }
        return GSON.toJson(root);
    }

    public static void save() {
        if (configDir == null) return;
        try {
            Files.createDirectories(configDir);
            Files.writeString(configDir.resolve(FILE), toJsonText() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Could not write {}: {}", FILE, e.toString());
        }
    }

    /** Every option id with its category, storage and default: used by the unit test and docs. */
    public static List<String> describeAll() {
        List<String> out = new ArrayList<>();
        for (Opt<?> o : BY_ID.values()) {
            out.add(o.id + " | " + o.category + " | " + o.storage + " | " + o.type.getSimpleName() + " | default "
                    + o.defaultValue + " | reload " + o.reload
                    + (o.choices != null ? " | choices " + Arrays.toString(o.choices.toArray()) : "")
                    + (o.choices == null && (o.max > o.min) ? " | range " + o.min + ".." + (o.maxSupplier != null ? "dynamic" : o.max) : ""));
        }
        return out;
    }
}
