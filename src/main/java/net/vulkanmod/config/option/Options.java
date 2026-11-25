package net.vulkanmod.config.option;

import dev.elemore.iroh.config.Availability;
import dev.elemore.iroh.config.IrohOptions;
import dev.elemore.iroh.config.IrohOptions.Opt;
import dev.elemore.iroh.ui.CapabilityReport;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.gui.OptionBlock;
import net.vulkanmod.config.video.VideoModeManager;
import net.vulkanmod.config.video.VideoModeSet;
import net.vulkanmod.vulkan.device.DeviceManager;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Builds the screen's option pages from {@link IrohOptions}. Every row is a registry entry turned into a widget model by
 * {@link #toOption(Opt)}; the only hand-built rows are the resolution / refresh rate pair (they depend on each other)
 * and the read-only Shaders and About rows.
 */
public abstract class Options {
    public static boolean fullscreenDirty = false;

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    // =====================================================================================================
    // Registry entry -> widget model
    // =====================================================================================================

    @SuppressWarnings("unchecked")
    public static <T> Option<?> toOption(Opt<T> o) {
        Component name = o.displayName();
        Option<?> option;
        if (o.type == Boolean.class) {
            Opt<Boolean> b = (Opt<Boolean>) o;
            option = new SwitchOption(name, b::apply, b::get);
        } else if (o.type == Integer.class && o.choices == null) {
            Opt<Integer> i = (Opt<Integer>) o;
            option = new RangeOption(name, i.min, i.maxValue(), i.step,
                    v -> i.label != null ? i.label.apply(v) : Component.literal(String.valueOf(v)),
                    i::apply, i::get);
        } else {
            T[] values = o.choices.toArray((T[]) Array.newInstance(o.type, o.choices.size()));
            CyclingOption<T> c = new CyclingOption<>(name, values, o::apply, o::get);
            c.setTranslator(v -> o.label != null ? o.label.apply(v) : Component.literal(String.valueOf(v)));
            if (o.choiceUnavailable != null) c.setValueReason(o.choiceUnavailable);
            option = c;
        }
        option.setUnavailable(o::unavailableReason);
        option.setTooltipSupplier(tooltipOf(o));
        return option;
    }

    /** Main tooltip, then the option's note, what applying costs, and the reason when it is greyed. */
    private static Supplier<Component> tooltipOf(Opt<?> o) {
        return () -> {
            MutableComponent out = Component.empty().append(Component.translatable(o.tooltipKey));
            if (o.noteKey != null)
                out.append("\n").append(Component.translatable(o.noteKey).withStyle(ChatFormatting.GRAY));
            String cost = switch (o.reload) {
                case RESTART -> "iroh.screen.needsRestart";
                case RELOAD_CHUNKS -> "iroh.screen.reloadsChunks";
                case RELOAD_RESOURCES -> "iroh.screen.reloadsResources";
                default -> null;
            };
            if (cost != null) out.append("\n").append(Component.translatable(cost).withStyle(ChatFormatting.YELLOW));
            Component why = o.unavailableReason();
            if (why != null)
                out.append("\n").append(Component.translatable("iroh.screen.unavailable", why).withStyle(ChatFormatting.RED));
            return out;
        };
    }

    private static Option<?>[] rows(Opt<?>... opts) {
        Option<?>[] out = new Option<?>[opts.length];
        for (int i = 0; i < opts.length; i++) out[i] = toOption(opts[i]);
        return out;
    }

    private static OptionBlock block(Opt<?>... opts) {
        return new OptionBlock("", rows(opts));
    }

    // =====================================================================================================
    // Pages
    // =====================================================================================================

    public static OptionBlock[] getVideoOpts() {
        var config = Initializer.CONFIG;
        var videoMode = config.videoMode;
        var videoModeSet = VideoModeManager.getFromVideoMode(videoMode);

        if (videoModeSet == null) {
            videoModeSet = VideoModeSet.getDummy();
            videoMode = videoModeSet.getVideoMode(-1);
        }

        VideoModeManager.selectedVideoMode = videoMode;
        var refreshRates = videoModeSet.getRefreshRates();

        CyclingOption<Integer> refreshRate = (CyclingOption<Integer>) new CyclingOption<>(
                Component.translatable("vulkanmod.options.refreshRate"),
                refreshRates.toArray(new Integer[0]),
                (value) -> {
                    VideoModeManager.selectedVideoMode.refreshRate = value;
                    VideoModeManager.applySelectedVideoMode();

                    if (mc().options.fullscreen().get())
                        fullscreenDirty = true;
                },
                () -> VideoModeManager.selectedVideoMode.refreshRate)
                .setTranslator(rr -> Component.nullToEmpty(rr.toString()))
                .setTooltip(Component.translatable("iroh.options.refreshRate.tooltip"));

        Option<VideoModeSet> resolutionOption = new CyclingOption<>(
                Component.translatable("options.fullscreen.resolution"),
                VideoModeManager.getVideoResolutions(),
                (value) -> {
                    VideoModeManager.selectedVideoMode = value.getVideoMode(refreshRate.getNewValue());
                    VideoModeManager.applySelectedVideoMode();

                    if (mc().options.fullscreen().get())
                        fullscreenDirty = true;
                },
                () -> {
                    var selectedVideoMode = VideoModeManager.selectedVideoMode;
                    var selectedVideoModeSet = VideoModeManager.getFromVideoMode(selectedVideoMode);

                    return selectedVideoModeSet != null ? selectedVideoModeSet : VideoModeSet.getDummy();
                })
                .setTranslator(resolution -> Component.nullToEmpty(resolution.toString()))
                .setTooltip(Component.translatable("iroh.options.resolution.tooltip"));

        resolutionOption.setOnChange(() -> {
            var newVideoMode = resolutionOption.getNewValue();
            var newRefreshRates = newVideoMode.getRefreshRates().toArray(new Integer[0]);

            refreshRate.setValues(newRefreshRates);
            refreshRate.setNewValue(newRefreshRates[newRefreshRates.length - 1]);
        });

        return new OptionBlock[]{
                new OptionBlock("", concat(new Option<?>[]{resolutionOption, refreshRate},
                        rows(IrohOptions.WINDOW_MODE, IrohOptions.FRAMERATE_LIMIT, IrohOptions.VSYNC))),
                block(IrohOptions.GUI_SCALE, IrohOptions.GAMMA),
                block(IrohOptions.VIEW_BOBBING, IrohOptions.ATTACK_INDICATOR, IrohOptions.AUTOSAVE_INDICATOR, IrohOptions.SHOW_FPS)
        };
    }

    public static OptionBlock[] getQualityOpts() {
        return new OptionBlock[]{
                block(IrohOptions.RENDER_DISTANCE, IrohOptions.SIMULATION_DISTANCE, IrohOptions.PRIORITIZE_CHUNK_UPDATES),
                block(IrohOptions.GRAPHICS, IrohOptions.PARTICLES, IrohOptions.CLOUDS, IrohOptions.AMBIENT_OCCLUSION,
                        IrohOptions.BIOME_BLEND, IrohOptions.LEAVES_QUALITY),
                block(IrohOptions.ENTITY_SHADOWS, IrohOptions.ENTITY_DISTANCE_SCALING, IrohOptions.MIPMAPS),
                block(IrohOptions.DISTORTION_EFFECTS, IrohOptions.FOV_EFFECTS, IrohOptions.GLINT_SPEED, IrohOptions.GLINT_STRENGTH,
                        IrohOptions.MENU_BLURRINESS),
                block(IrohOptions.ENABLE_VIGNETTE, IrohOptions.FOG_DISTANCE_CHUNKS, IrohOptions.LEAF_CULLING, IrohOptions.LEAF_CULLING_DEPTH,
                        IrohOptions.LEAF_CULLING_REJECTION)
        };
    }

    public static OptionBlock[] getPerformanceOpts() {
        return new OptionBlock[]{
                block(IrohOptions.ADV_CULLING, IrohOptions.ENTITY_CULLING, IrohOptions.UNIQUE_OPAQUE_LAYER,
                        IrohOptions.BACKFACE_CULLING, IrohOptions.INDIRECT_DRAW),
                block(IrohOptions.BUILDER_THREADS, IrohOptions.CHUNK_BUILD_DEFER_MODE, IrohOptions.FRAME_QUEUE_SIZE)
        };
    }

    /**
     * P2-7b: the Culling page (owner, 2026-11-25: every GPU culling technique a player option). The CPU culling rows
     * stay on the Performance page; these need the world terrain arena on an indirect-draw device and are greyed with
     * the reason otherwise. Hi-Z occlusion (P2-7c) joins this page when it exists.
     */
    public static OptionBlock[] getCullingOpts() {
        return new OptionBlock[]{
                block(IrohOptions.CULL_GPU_FRUSTUM, IrohOptions.CULL_GPU_FACING, IrohOptions.CULL_GPU_SCREEN_SIZE, IrohOptions.CULL_GPU_HORIZON)
        };
    }

    /**
     * The Iroh section (owner decision "Settings UI", 2026-11-21): no shader selection screen. ONE look switch (ray
     * tracing <-> Navidson; greyed until both exist), the upscaler rows (P4-1b: live, wired to the runtime switches by
     * UpscaleSettings, unavailable choices name their reason) and frame generation (greyed until Phase 8). Navidson's
     * own options get no screen.
     */
    public static OptionBlock[] getShadersOpts() {
        InfoOption look = info("look", () -> reasonOr(lookReason(), "iroh.shaders.available"))
                .setUnavailable(Options::lookReason);
        InfoOption fg = info("shaders.frameGeneration", () -> reasonOr(Availability.frameGeneration(), "iroh.shaders.available"))
                .setUnavailable(Availability::frameGeneration);
        return new OptionBlock[]{
                new OptionBlock("", new Option<?>[]{look}),
                new OptionBlock("", new Option<?>[]{toOption(IrohOptions.UPSCALER), toOption(IrohOptions.UPSCALE_QUALITY),
                        toOption(IrohOptions.UPSCALE_SHARPNESS), fg})};
    }

    /** RT <-> Navidson: needs the hardware for RT and both looks built; Navidson alone is not a choice yet either. */
    static Component lookReason() {
        Component rt = Availability.rayTracing();
        return rt != null ? rt : Availability.shaderPacks();
    }

    public static OptionBlock[] getAdvancedOpts() {
        var devices = IntStream.range(-1, DeviceManager.suitableDevices.size()).boxed().toArray(Integer[]::new);
        Opt<Integer> dev = IrohOptions.DEVICE;
        CyclingOption<Integer> device = new CyclingOption<>(dev.displayName(), devices, dev::apply, dev::get);
        device.setTranslator(v -> v == -1 ? Component.translatable("vulkanmod.options.deviceSelector.auto")
                : Component.literal(DeviceManager.suitableDevices.get(v).deviceName));
        device.setTooltipSupplier(() -> Component.empty()
                .append(Component.translatable("iroh.options.device.tooltip", DeviceManager.device.deviceName))
                .append("\n").append(Component.translatable("iroh.screen.needsRestart").withStyle(ChatFormatting.YELLOW)));

        InfoOption file = info("advanced.configFile", () -> Component.literal("config/" + IrohOptions.FILE));
        InfoOption threads = info("advanced.cpus", () -> Component.literal(String.valueOf(Runtime.getRuntime().availableProcessors())));
        return new OptionBlock[]{
                new OptionBlock("", new Option<?>[]{device}),
                new OptionBlock("", new Option<?>[]{file, threads})
        };
    }

    public static OptionBlock[] getAboutOpts() {
        String version = Initializer.getVersion() == null ? "dev" : Initializer.getVersion();
        InfoOption name = info("about.iroh", () -> Component.literal("Iroh " + version));
        InfoOption fork = info("about.fork", () -> Component.translatable("iroh.about.fork.value"));
        InfoOption upstream = info("about.upstream", () -> Component.literal("https://github.com/xCollateral/VulkanMod"))
                .setOnClick(() -> Util.getPlatform().openUri("https://github.com/xCollateral/VulkanMod"));
        InfoOption licence = info("about.licence", () -> Component.literal("LGPL-3.0-only"))
                .setOnClick(() -> Util.getPlatform().openUri("https://www.gnu.org/licenses/lgpl-3.0.html"));

        CapabilityReport r = CapabilityReport.get();
        List<Option<?>> caps = new ArrayList<>();
        caps.add(info("about.gpu", () -> Component.literal(r.gpu)));
        caps.add(info("about.driver", () -> Component.literal(r.driver)));
        caps.add(info("about.vulkan", () -> Component.literal(r.vulkan)));
        for (CapabilityReport.Item item : r.items.values()) {
            caps.add(new InfoOption(Component.literal(item.label()),
                    () -> Component.translatable(item.found() ? "iroh.about.cap.yes" : "iroh.about.cap.no"))
                    .setTooltip(Component.translatable("iroh.about.cap." + item.id() + ".tooltip", item.detail())));
        }
        return new OptionBlock[]{
                new OptionBlock("", new Option<?>[]{name, fork, upstream, licence}),
                new OptionBlock("", caps.toArray(new Option<?>[0]))
        };
    }

    // =====================================================================================================
    // helpers
    // =====================================================================================================

    private static InfoOption info(String key, Supplier<Component> value) {
        InfoOption o = new InfoOption(Component.translatable("iroh." + key), value);
        o.setTooltip(Component.translatable("iroh." + key + ".tooltip"));
        return o;
    }

    private static Component reasonOr(Component reason, String okKey) {
        return reason != null ? reason : Component.translatable(okKey);
    }

    private static Option<?>[] concat(Option<?>[] a, Option<?>[] b) {
        Option<?>[] out = new Option<?>[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
