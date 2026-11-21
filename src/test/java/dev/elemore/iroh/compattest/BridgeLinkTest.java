package dev.elemore.iroh.compattest;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * rc3r2: the members our own mods' IrohBridge classes look up by MethodHandles / LambdaMetafactory (elemore-core
 * com.elemore.client.IrohBridge, Polaris com.polaris.client.IrohBridge), checked against the BUILT (intermediary-mapped)
 * Iroh jar by exact name + descriptor + public (static for IrohApi, abstract for the interfaces). A rename or signature
 * change in dev.elemore.iroh.api fails here instead of silently switching a hook off in game.
 * The bridge classes are read from the jars in build/p16b at test time (elemore-core-*.jar, polaris-*.jar): every
 * "dev.elemore.iroh.api.*" class name they hold must exist, and every string they hold that names an api member must be
 * in the list below (so the list cannot drift from the bridges). {@code ./gradlew bridgeLinkTest [-Piroh=jar]
 * [-Pbridges=dir]}; last line "# bridgelink PASS n/n".
 */
public final class BridgeLinkTest {
    private static final String API = "dev/elemore/iroh/api/IrohApi";

    static Path findIroh() throws Exception {
        try (var s = java.nio.file.Files.list(Path.of("build/libs"))) {
            return s.filter(p -> p.getFileName().toString().matches("iroh-.*\\.jar") && !p.toString().contains("sources"))
                    .findFirst().orElseThrow(() -> new IllegalStateException("build the Iroh jar first (./build.sh)"));
        }
    }
    private static final String BG = "Lnet/minecraft/class_1922;", BP = "Lnet/minecraft/class_2338;", DIR = "Lnet/minecraft/class_2350;",
            BS = "Lnet/minecraft/class_2680;", PL = "Lnet/minecraft/class_1657;", HAND = "Lnet/minecraft/class_1268;", STACK = "Lnet/minecraft/class_1799;",
            MQV = "Lnet/fabricmc/fabric/api/renderer/v1/mesh/MutableQuadView;";

    // {owner, name, descriptor, user}
    private static final String[][] EXPECT = {
            {API, "isBoxVisible", "(DDDDDD)Z", "elemore ClavisLockCullMixin"},
            {API, "isShaderPackInUse", "()Z", "elemore ShaderGuard"},
            {API, "currentPackName", "()Ljava/lang/String;", "elemore ShaderGuard"},
            {API, "isRenderingShadowPass", "()Z", "elemore ShadowPass"},
            {API, "cullLeafFace", "(" + BG + BP + DIR + "Ljava/util/function/Predicate;)Z", "elemore leaf veto"},
            {API, "renderPath", "()Ldev/elemore/iroh/api/RenderPath;", "elemore ShaderGuard"},
            {API, "capabilities", "()Ljava/util/Set;", "elemore ShaderGuard"},
            {API, "registerFaceCullVeto", "(Ldev/elemore/iroh/api/FaceCullVeto;)V", "elemore leaf veto"},
            {API, "registerHeldLightProvider", "(Ldev/elemore/iroh/api/HeldLightProvider;)V", "elemore trinket lantern"},
            {API, "isChunkMeshView", "(" + BG + ")Z", "polaris MeshGate"},
            {API, "registerShadeHook", "(Ldev/elemore/iroh/api/ShadeHook;)V", "polaris slope lighting"},
            {"dev/elemore/iroh/api/FaceCullVeto", "hideFace", "(" + BS + BG + BP + DIR + ")Z", "elemore LambdaMetafactory"},
            {"dev/elemore/iroh/api/HeldLightProvider", "lightSource", "(" + PL + HAND + STACK + ")" + STACK, "elemore LambdaMetafactory"},
            {"dev/elemore/iroh/api/ShadeHook", "beforeShade", "(" + BS + BP + MQV + ")Z", "polaris LambdaMetafactory"},
    };

    public static void main(String[] args) throws Exception {
        Path iroh = args.length > 0 && !args[0].isEmpty() ? Path.of(args[0]) : findIroh();
        Path bridges = Path.of(args.length > 1 && !args[1].isEmpty() ? args[1] : "build/p16b");
        Map<String, Integer> members = new HashMap<>();
        java.util.Set<String> apiClasses = new java.util.HashSet<>(), apiMemberNames = new java.util.HashSet<>();
        try (ZipFile z = new ZipFile(iroh.toFile())) {
            for (var e : java.util.Collections.list(z.entries())) {
                String n = e.getName();
                if (!n.startsWith("dev/elemore/iroh/api/") || !n.endsWith(".class")) continue;
                String cls = n.substring(0, n.length() - 6);
                apiClasses.add(cls);
                try (InputStream in = z.getInputStream(e)) {
                    new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override public MethodVisitor visitMethod(int acc, String mn, String d, String s, String[] x) {
                            members.put(cls + "." + mn + d, acc);
                            if ((acc & Opcodes.ACC_PUBLIC) != 0 && !mn.startsWith("<")) apiMemberNames.add(mn);
                            return null;
                        }
                    }, ClassReader.SKIP_CODE);
                }
            }
        }
        // strings the bridges hold
        java.util.Set<String> bridgeStrings = new java.util.TreeSet<>();
        int bridgeClasses = 0;
        try (var files = java.nio.file.Files.list(bridges)) {
            for (Path jar : files.filter(f -> f.getFileName().toString().matches("(elemore-core|polaris)-.*\\.jar")).toList()) {
                try (ZipFile z = new ZipFile(jar.toFile())) {
                    for (var e : java.util.Collections.list(z.entries())) {
                        if (!e.getName().matches(".*/IrohBridge.*\\.class")) continue;
                        bridgeClasses++;
                        try (InputStream in = z.getInputStream(e)) {
                            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                                @Override public MethodVisitor visitMethod(int a, String n, String d, String sg, String[] x) {
                                    return new MethodVisitor(Opcodes.ASM9) {
                                        @Override public void visitLdcInsn(Object v) { if (v instanceof String str) bridgeStrings.add(str); }
                                    };
                                }
                            }, 0);
                        }
                    }
                }
            }
        }
        java.util.Set<String> listed = new java.util.HashSet<>();
        for (String[] e : EXPECT) listed.add(e[1]);
        int pre = 0, preFail = 0;
        if (bridgeClasses == 0) { System.out.println("FAIL no IrohBridge classes in " + bridges + "/(elemore-core|polaris)-*.jar"); preFail++; }
        for (String str : bridgeStrings) {
            if (str.startsWith("dev.elemore.iroh.api.")) {
                boolean ok = apiClasses.contains(str.replace('.', '/'));
                System.out.println((ok ? "ok   " : "FAIL ") + "bridge names class " + str);
                if (ok) pre++; else preFail++;
            } else if (apiMemberNames.contains(str) && !listed.contains(str)) {
                System.out.println("FAIL bridge looks up " + str + ", which this test does not list with a descriptor");
                preFail++;
            }
        }
        for (String name : listed)
            if (!bridgeStrings.contains(name)) { System.out.println("FAIL listed " + name + " is not used by any bridge (stale list)"); preFail++; }
        int pass = pre, fail = preFail;
        for (String[] e : EXPECT) {
            Integer acc = members.get(e[0] + "." + e[1] + e[2]);
            boolean api = e[0].equals(API);
            boolean ok = acc != null && (acc & Opcodes.ACC_PUBLIC) != 0
                    && (api ? (acc & Opcodes.ACC_STATIC) != 0 : (acc & Opcodes.ACC_ABSTRACT) != 0);
            System.out.println((ok ? "ok   " : "FAIL ") + e[0].substring(e[0].lastIndexOf('/') + 1) + "." + e[1] + e[2] + "  (" + e[3] + ")");
            if (ok) pass++; else fail++;
        }
        System.out.println("# bridgelink " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        if (fail != 0) System.exit(1);
    }
}
