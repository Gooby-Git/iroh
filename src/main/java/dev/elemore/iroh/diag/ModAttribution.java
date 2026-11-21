package dev.elemore.iroh.diag;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Iroh (P1-13a): which mod a stack frame belongs to. Used by GlShim's log lines and EmuStats.
 * <ul>
 *   <li>A mixin handler merged into a vanilla class carries its mod in the method name
 *       ({@code handler$abc000$modid$name}, {@code redirect$...}, {@code wrapOperation$...}): that mod wins.</li>
 *   <li>Otherwise the class is looked up in every mod's root paths ({@code ModContainer.getRootPaths()}, which also
 *       covers jar-in-jar mods): the mod whose root contains {@code <class>.class}. Mods are tried from the most
 *       specific (nested) to the least, and Minecraft / Java / Fabric loader are excluded, so a nested library is not
 *       blamed for its parent's classes and vice versa (the slice-2 "everything is additional_rpg_jewelry" bug was
 *       a jar-in-jar root "/" that contained every path).</li>
 *   <li>Unknown: "minecraft" for net.minecraft / com.mojang, else the package.</li>
 * </ul>
 * Results are cached per class; the first lookup of a class walks the mod list (~400 roots), later ones are a map hit.
 */
public final class ModAttribution {
    private static final Pattern MIXIN_METHOD = Pattern.compile(
            "^(?:handler|redirect|modify|wrapOperation|wrapWithCondition|localvar|constant|args|mixinextras\\$bridge)\\$[^$]*\\$([a-z0-9_.\\-]+)\\$");
    private static final Map<String, String> BY_CLASS = new ConcurrentHashMap<>();
    private static volatile List<ModContainer> mods;

    private ModAttribution() {
    }

    /** Mod id for a frame (class + method name). */
    public static String of(String className, String methodName) {
        if (methodName != null && methodName.indexOf('$') > 0) {
            Matcher m = MIXIN_METHOD.matcher(methodName);
            if (m.find()) return m.group(1);
        }
        return ofClass(className);
    }

    public static String ofClass(String className) {
        return BY_CLASS.computeIfAbsent(className, ModAttribution::lookup);
    }

    private static String lookup(String cn) {
        try {
            String file = cn.replace('.', '/') + ".class";
            for (ModContainer m : mods()) {
                for (Path root : m.getRootPaths()) {
                    if (Files.exists(root.resolve(file))) return m.getMetadata().getId();
                }
            }
        } catch (Throwable ignored) {
        }
        if (cn.startsWith("net.minecraft.") || cn.startsWith("com.mojang.")) return "minecraft";
        int dot = cn.lastIndexOf('.');
        return dot > 0 ? cn.substring(0, dot) : cn;
    }

    /** Every mod except minecraft / java / fabricloader, nested (jar-in-jar) mods first. */
    private static List<ModContainer> mods() {
        List<ModContainer> l = mods;
        if (l == null) {
            l = FabricLoader.getInstance().getAllMods().stream()
                    .filter(m -> !List.of("minecraft", "java", "fabricloader", "mixinextras").contains(m.getMetadata().getId()))
                    .sorted((a, b) -> Boolean.compare(b.getContainingMod().isPresent(), a.getContainingMod().isPresent()))
                    .toList();
            mods = l;
        }
        return l;
    }
}
