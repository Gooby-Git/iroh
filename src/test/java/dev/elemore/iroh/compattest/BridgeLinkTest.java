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
 * {@code ./gradlew bridgeLinkTest}; last line "# bridgelink PASS n/n". Keep this list in step with the two bridges.
 */
public final class BridgeLinkTest {
    private static final String API = "dev/elemore/iroh/api/IrohApi";
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
        Path iroh = args.length > 0 && !args[0].isEmpty() ? Path.of(args[0]) : AsyncParticlesLinkTest.findIroh();
        Map<String, Integer> members = new HashMap<>();
        try (ZipFile z = new ZipFile(iroh.toFile())) {
            for (String cls : new String[]{API, "dev/elemore/iroh/api/FaceCullVeto", "dev/elemore/iroh/api/HeldLightProvider", "dev/elemore/iroh/api/ShadeHook"}) {
                var e = z.getEntry(cls + ".class");
                if (e == null) continue;
                try (InputStream in = z.getInputStream(e)) {
                    new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override public MethodVisitor visitMethod(int acc, String n, String d, String s, String[] x) {
                            members.put(cls + "." + n + d, acc);
                            return null;
                        }
                    }, ClassReader.SKIP_CODE);
                }
            }
        }
        int pass = 0, fail = 0;
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
