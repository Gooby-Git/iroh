package net.vulkanmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.loader.api.FabricLoader;
import dev.elemore.iroh.config.IrohOptions;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.Platform;
import net.vulkanmod.config.video.VideoModeManager;
import net.vulkanmod.render.chunk.build.frapi.VulkanModRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Initializer implements ClientModInitializer {
	public static final Logger LOGGER = LogManager.getLogger("Iroh");

	private static String VERSION;
	public static Config CONFIG;

	@Override
	public void onInitializeClient() {

		VERSION = FabricLoader.getInstance()
				.getModContainer("iroh")
				.get()
				.getMetadata()
				.getVersion().getFriendlyString();

		LOGGER.info("== Iroh {} (a fork of VulkanMod by xCollateral, LGPL-3.0-only) ==", VERSION);

		Platform.init();
		// Iroh P1-2: GL safety net (normally installed when LWJGL's GL class initialises; idempotent; after
		// Platform.init so LWJGL's core natives are loaded before ThreadLocalUtil is touched)
		dev.elemore.iroh.glshim.GlNet.install();
		VideoModeManager.init();

		// config/iroh.json (first run: migrates vulkanmod_settings.json, then the pack's leftover Sodium / Iris config)
		CONFIG = new Config();
		CONFIG.videoMode = VideoModeManager.getFirstAvailable().getVideoMode();
		for (String line : IrohOptions.load(CONFIG, FabricLoader.getInstance().getConfigDir()))
			LOGGER.info("config: {}", line);

		RendererAccess.INSTANCE.registerRenderer(VulkanModRenderer.INSTANCE);
	}

	public static String getVersion() {
		return VERSION;
	}
}
