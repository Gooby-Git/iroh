package dev.elemore.iroh.compat.asyncparticles;

import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

/**
 * Turns on AsyncParticles' VulkanMod backend under Iroh (P1-2 slice 2, item 5; the "Mod id decision" in OUTLINE.md:
 * Iroh is not {@code vulkanmod}, so the mod's own gate stays closed).
 *
 * <p>AsyncParticles decides once, in {@code ModListHelper.<clinit>}: {@code VULKAN_MOD_LOADED} (read by its mixin plugin
 * to apply the {@code compat.vulkanmod} mixins into Iroh's Renderer / Buffer) and {@code VULKAN_MOD_0_6} (read by
 * {@code Backends.<clinit>} to pick the Vulkan backend). Its plugin reads the class while mixin configs are being
 * prepared, so a mixin into ModListHelper would come too late. This is the plugin of an empty mixin config with a
 * lower priority than AsyncParticles' (100 < 1000): its {@code onLoad} runs first and sets the two flags in memory.
 * Nothing in the AsyncParticles jar changes. Every member its backend touches exists in Iroh with the 0.6 shape
 * (checked from its call sites, knowledge/pack-compat.md 0e): Renderer.inFlightFences / currentFrame (PUTSTATIC in
 * submitFrame) / getFramesNum / recreateSwapChain -> Pipeline.recreateDescriptorSets(int), Buffer.offset, Drawer,
 * AutoIndexBuffer, MemoryManager.createBuffer(Buffer,long,int,int), MemoryTypes.GPU_MEM, MainPass.rebindMainTarget.
 *
 * <p>{@code -Diroh.compat.asyncParticlesVulkan=false} keeps the gate closed (AsyncParticles then runs its CPU path with
 * Iroh's GL answers: no compute, no transform feedback, no DSA).
 */
public final class AsyncParticlesGate implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogManager.getLogger("Iroh");
    private static final String HELPER = "fabric.fun.qu_an.minecraft.asyncparticles.client.compat.ModListHelper";

    @Override
    public void onLoad(String mixinPackage) {
        if (!FabricLoader.getInstance().isModLoaded("asyncparticles") || FabricLoader.getInstance().isModLoaded("vulkanmod"))
            return;
        if (!Boolean.parseBoolean(System.getProperty("iroh.compat.asyncParticlesVulkan", "true"))) {
            LOGGER.info("Iroh: AsyncParticles VulkanMod backend left off (-Diroh.compat.asyncParticlesVulkan=false)");
            return;
        }
        try {
            Class<?> helper = Class.forName(HELPER, true, AsyncParticlesGate.class.getClassLoader());
            setStaticFinal(helper.getDeclaredField("VULKAN_MOD_LOADED"), true);
            setStaticFinal(helper.getDeclaredField("VULKAN_MOD_0_6"), true);
            LOGGER.info("Iroh: AsyncParticles uses its VulkanMod backend on Iroh (gate flags set in memory)");
        } catch (Throwable t) {
            LOGGER.warn("Iroh: could not switch AsyncParticles to its VulkanMod backend; it keeps its OpenGL path", t);
        }
    }

    private static void setStaticFinal(Field f, boolean value) throws ReflectiveOperationException {
        Field uf = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        sun.misc.Unsafe u = (sun.misc.Unsafe) uf.get(null);
        @SuppressWarnings("deprecation") Object base = u.staticFieldBase(f);
        @SuppressWarnings("deprecation") long off = u.staticFieldOffset(f);
        u.putBooleanVolatile(base, off, value);
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return false; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String t, ClassNode c, String m, IMixinInfo i) {}
    @Override public void postApply(String t, ClassNode c, String m, IMixinInfo i) {}
}
