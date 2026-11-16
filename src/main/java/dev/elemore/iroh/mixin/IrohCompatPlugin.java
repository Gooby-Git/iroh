package dev.elemore.iroh.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Applies a compat mixin only when the mod it targets is loaded: a mixin under {@code dev.elemore.iroh.mixin.compat.<modid>}
 * needs mod {@code <modid>}. Iroh must run without any Elemore mod (vanilla gate).
 */
public class IrohCompatPlugin implements IMixinConfigPlugin {
    private static final String PREFIX = "dev.elemore.iroh.mixin.compat.";

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!mixinClassName.startsWith(PREFIX)) return true;
        String rest = mixinClassName.substring(PREFIX.length());
        int dot = rest.indexOf('.');
        return dot > 0 && FabricLoader.getInstance().isModLoaded(rest.substring(0, dot));
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
