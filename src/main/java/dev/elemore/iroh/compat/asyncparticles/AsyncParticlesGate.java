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
 * Nothing in the AsyncParticles jar changes.
 *
 * <p>rc3r2 (td-c8876c): the slice-2 claim "every member exists with the 0.6 shape" was a hand-made list of member NAMES
 * from its call sites; it missed the {@code Buffer(String, int, MemoryType)} constructor overload, and world join crashed
 * with NoSuchMethodError. Now, before opening the gate, {@link LinkSurface} resolves every reference the mod's Fabric
 * classes make into {@code net.vulkanmod} (exact descriptors, static-ness, access, its compat mixins' targets) against
 * Iroh's own jar, exactly as {@code ./gradlew asyncParticlesLinkTest} does offline. Any unresolved reference keeps the
 * gate closed (its OpenGL path) and logs each one, so a future AsyncParticles update cannot crash a world join.
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
            java.util.List<String> missing = linkProblems();
            if (missing == null) {
                LOGGER.warn("Iroh: AsyncParticles link check could not read the jars; VulkanMod backend left off");
                return;
            }
            if (!missing.isEmpty()) {
                LOGGER.warn("Iroh: AsyncParticles' VulkanMod backend needs {} member(s) Iroh lacks; it keeps its OpenGL path:", missing.size());
                missing.forEach(m -> LOGGER.warn("Iroh:   {}", m));
                return;
            }
            Class<?> helper = Class.forName(HELPER, true, AsyncParticlesGate.class.getClassLoader());
            setStaticFinal(helper.getDeclaredField("VULKAN_MOD_LOADED"), true);
            setStaticFinal(helper.getDeclaredField("VULKAN_MOD_0_6"), true);
            LOGGER.info("Iroh: AsyncParticles uses its VulkanMod backend on Iroh (gate flags set in memory)");
        } catch (Throwable t) {
            LOGGER.warn("Iroh: could not switch AsyncParticles to its VulkanMod backend; it keeps its OpenGL path", t);
        }
    }

    /** Unresolved references of AsyncParticles into net.vulkanmod, or null when a jar cannot be read (dev runs). */
    private static java.util.List<String> linkProblems() {
        try {
            java.nio.file.Path ap = jarOf("asyncparticles"), iroh = jarOf("iroh");
            if (ap == null || iroh == null) return null;
            long t0 = System.nanoTime();
            dev.elemore.iroh.compat.LinkSurface ls = new dev.elemore.iroh.compat.LinkSurface(iroh, o -> o.startsWith("net/vulkanmod/"));
            ls.check(ap, n -> n.startsWith("fabric/"));
            LOGGER.info("Iroh: AsyncParticles link check: {} references into net.vulkanmod, {} unresolved ({} ms)",
                    ls.checked, ls.problems.size(), (System.nanoTime() - t0) / 1_000_000);
            java.util.List<String> out = new java.util.ArrayList<>();
            ls.problems.forEach(p -> out.add(p.where() + ": " + p.what()));
            return out;
        } catch (Throwable t) {
            LOGGER.warn("Iroh: AsyncParticles link check failed", t);
            return null;
        }
    }

    private static java.nio.file.Path jarOf(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .flatMap(c -> c.getOrigin().getPaths().stream().filter(p -> p.toString().endsWith(".jar") && java.nio.file.Files.isRegularFile(p)).findFirst())
                .orElse(null);
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
