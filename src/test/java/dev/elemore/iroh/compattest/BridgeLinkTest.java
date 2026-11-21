package dev.elemore.iroh.compattest;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;

/**
 * Our own mods reach Iroh only through MethodHandles / LambdaMetafactory in their IrohBridge classes (no Iroh class in any
 * constant pool), so a rename in dev.elemore.iroh.api would silently switch a hook off in game. This test checks the
 * bridges against the BUILT (intermediary-mapped) Iroh jar, discovering what they look up instead of keeping a list:
 * <ul>
 * <li>every string constant of every {@code IrohBridge*} class in each bridge jar is classified: an api class name
 * ("dev.elemore.iroh.api.X") must exist; an identifier must resolve to a public method of, or an enum constant of, one of
 * the api classes that bridge names, unless it is one of the bridge jar's own method names (its own findStatic targets)
 * or a mod id passed to FabricLoader.isModLoaded; anything else is a FAIL (a lookup that does not resolve);</li>
 * <li>enum constants read by name: string constants passed straight into an IrohBridge static method from any class of the
 * same jar (e.g. {@code IrohBridge.hook("AFTER_HUD", ...)}, {@code createDynamic(id, w, h, "NEAREST", "CLAMP")}) must
 * be constants of an api enum the bridge names;</li>
 * <li>api interfaces built with LambdaMetafactory (@FunctionalInterface in the api) have exactly one abstract method,
 * and its name is the one the bridge passes;</li>
 * <li>a few pinned descriptors (signatures the bridges' MethodTypes encode, which bytecode only shows as Class
 * constants) must still exist exactly.</li>
 * </ul>
 * Bridge jars: newest {@code <mod>-*.jar} per mod holding an IrohBridge class, from the folder given ({@code -Pbridges=dir}),
 * default ~/Projects/iroh-wt-resync/build/p113b, else build/p16b. {@code ./gradlew bridgeLinkTest [-Piroh=jar]
 * [-Pbridges=dir]}; last line "# bridgelink PASS n/n".
 */
public final class BridgeLinkTest {
    private static final String API_PKG = "dev/elemore/iroh/api/";
    private static final Path DEFAULT_BRIDGES = Path.of(System.getProperty("user.home"), "Projects/iroh-wt-resync/build/p113b");

    static Path findIroh() throws Exception {
        try (var s = Files.list(Path.of("build/libs"))) {
            return s.filter(p -> p.getFileName().toString().matches("iroh-.*\\.jar") && !p.toString().contains("sources"))
                    .findFirst().orElseThrow(() -> new IllegalStateException("build the Iroh jar first (./build.sh)"));
        }
    }

    private static final String BG = "Lnet/minecraft/class_1922;", BP = "Lnet/minecraft/class_2338;", DIR = "Lnet/minecraft/class_2350;",
            BS = "Lnet/minecraft/class_2680;", PL = "Lnet/minecraft/class_1657;", HAND = "Lnet/minecraft/class_1268;", STACK = "Lnet/minecraft/class_1799;",
            MQV = "Lnet/fabricmc/fabric/api/renderer/v1/mesh/MutableQuadView;", RL = "Lnet/minecraft/class_2960;", A = "Ldev/elemore/iroh/api/",
            REG = A + "IrohFrame$Registration;", CTX = A + "FrameContext;";

    /** Pinned signatures, checked only when the bridges use that member name (signature drift guard). {class, name, desc}. */
    private static final String[][] PINNED = {
            {"IrohApi", "isBoxVisible", "(DDDDDD)Z"}, {"IrohApi", "isShaderPackInUse", "()Z"},
            {"IrohApi", "currentPackName", "()Ljava/lang/String;"}, {"IrohApi", "isRenderingShadowPass", "()Z"},
            {"IrohApi", "cullLeafFace", "(" + BG + BP + DIR + "Ljava/util/function/Predicate;)Z"},
            {"IrohApi", "renderPath", "()" + A + "RenderPath;"}, {"IrohApi", "capabilities", "()Ljava/util/Set;"},
            {"IrohApi", "registerFaceCullVeto", "(" + A + "FaceCullVeto;)V"},
            {"IrohApi", "registerHeldLightProvider", "(" + A + "HeldLightProvider;)V"},
            {"IrohApi", "isChunkMeshView", "(" + BG + ")Z"}, {"IrohApi", "registerShadeHook", "(" + A + "ShadeHook;)V"},
            {"FaceCullVeto", "hideFace", "(" + BS + BG + BP + DIR + ")Z"},
            {"HeldLightProvider", "lightSource", "(" + PL + HAND + STACK + ")" + STACK},
            {"ShadeHook", "beforeShade", "(" + BS + BP + MQV + ")Z"},
            {"IrohSky", "setCloudsSuppressed", "(Ljava/util/function/Predicate;)" + REG},
            {"IrohSky", "overrideClouds", "(I" + A + "CloudOverride;)" + REG},
            {"IrohFrame", "addHook", "(" + A + "FrameStage;" + A + "FrameHook;)" + REG},
            {"IrohTextures", "createDynamic", "(" + RL + "II" + A + "IrohTextures$Filter;" + A + "IrohTextures$Wrap;)" + A + "DynamicTextureHandle;"},
            {"DynamicTextureHandle", "hasConsumers", "()Z"}, {"DynamicTextureHandle", "update", "(IIIILjava/nio/ByteBuffer;)V"},
            {"FrameHook", "run", "(" + CTX + ")V"}, {"CloudOverride", "draw", "(" + CTX + ")Z"},
            {"FrameContext", "partialTick", "()F"}, {"FrameContext", "cameraPos", "()Lorg/joml/Vector3dc;"},
            {"FrameContext", "viewMatrix", "()Lorg/joml/Matrix4fc;"}, {"FrameContext", "projectionMatrix", "()Lorg/joml/Matrix4fc;"},
    };

    /** One api class as read from the Iroh jar. */
    static final class ApiClass {
        int access;
        boolean functional;
        final Map<String, Integer> methods = new HashMap<>(); // name+desc -> access
        final Set<String> publicNames = new HashSet<>(), enumConstants = new HashSet<>(), abstractNames = new HashSet<>();
    }

    private static int pass, fail;

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    public static void main(String[] args) throws Exception {
        Path iroh = args.length > 0 && !args[0].isEmpty() ? Path.of(args[0]) : findIroh();
        Path bridges = args.length > 1 && !args[1].isEmpty() ? Path.of(args[1])
                : Files.isDirectory(DEFAULT_BRIDGES) ? DEFAULT_BRIDGES : Path.of("build/p16b");
        System.out.println("# iroh " + iroh + ", bridges " + bridges);
        Map<String, ApiClass> api = readApi(iroh);

        // newest jar per mod that holds an IrohBridge
        Map<String, Path> jars = new TreeMap<>();
        try (var files = Files.list(bridges)) {
            for (Path j : files.filter(f -> f.toString().endsWith(".jar")).sorted().toList()) {
                String mod = j.getFileName().toString().replaceFirst("-\\d.*$", "");
                Path old = jars.get(mod);
                if (hasBridge(j) && (old == null || Files.getLastModifiedTime(j).compareTo(Files.getLastModifiedTime(old)) > 0))
                    jars.put(mod, j);
            }
        }
        check("bridge jars found in " + bridges + ": " + jars.values().stream().map(p -> p.getFileName().toString()).toList(), !jars.isEmpty());

        Set<String> usedNames = new HashSet<>();
        for (Path jar : jars.values()) scanJar(jar, api, usedNames);

        for (String[] p : PINNED) {
            if (!usedNames.contains(p[0] + "." + p[1])) continue;
            ApiClass c = api.get(API_PKG + p[0]);
            Integer acc = c == null ? null : c.methods.get(p[1] + p[2]);
            check("pinned signature " + p[0] + "." + p[1] + p[2], acc != null && (acc & Opcodes.ACC_PUBLIC) != 0);
        }
        System.out.println("# bridgelink " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        if (fail != 0) System.exit(1);
    }

    private static boolean hasBridge(Path jar) throws Exception {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            return Collections.list(z.entries()).stream().anyMatch(e -> e.getName().matches(".*/IrohBridge(\\$.*)?\\.class"));
        }
    }

    static Map<String, ApiClass> readApi(Path iroh) throws Exception {
        Map<String, ApiClass> api = new HashMap<>();
        try (ZipFile z = new ZipFile(iroh.toFile())) {
            for (var e : Collections.list(z.entries())) {
                String n = e.getName();
                if (!n.startsWith(API_PKG) || !n.endsWith(".class")) continue;
                ApiClass c = new ApiClass();
                api.put(n.substring(0, n.length() - 6), c);
                try (InputStream in = z.getInputStream(e)) {
                    new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override public void visit(int v, int acc, String name, String sig, String sup, String[] itf) { c.access = acc; }
                        @Override public org.objectweb.asm.AnnotationVisitor visitAnnotation(String d, boolean vis) {
                            if (d.equals("Ljava/lang/FunctionalInterface;")) c.functional = true;
                            return null;
                        }
                        @Override public MethodVisitor visitMethod(int acc, String mn, String d, String s, String[] x) {
                            c.methods.put(mn + d, acc);
                            if ((acc & Opcodes.ACC_PUBLIC) != 0 && !mn.startsWith("<")) c.publicNames.add(mn);
                            if ((acc & Opcodes.ACC_ABSTRACT) != 0) c.abstractNames.add(mn);
                            return null;
                        }
                        @Override public FieldVisitor visitField(int acc, String fn, String d, String s, Object v) {
                            if ((acc & Opcodes.ACC_ENUM) != 0) c.enumConstants.add(fn);
                            return null;
                        }
                    }, ClassReader.SKIP_CODE);
                }
            }
        }
        return api;
    }

    private static void scanJar(Path jar, Map<String, ApiClass> api, Set<String> usedNames) throws Exception {
        String jn = jar.getFileName().toString();
        Set<String> bridgeStrings = new TreeSet<>(), ownMethods = new HashSet<>(), modIds = new HashSet<>();
        Set<String> enumArgs = new TreeSet<>();
        Set<String> bridgeOwners = new HashSet<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            List<java.util.zip.ZipEntry> classes = Collections.list(z.entries()).stream().filter(e -> e.getName().endsWith(".class")).map(e -> (java.util.zip.ZipEntry) e).toList();
            for (var e : classes)
                if (e.getName().matches(".*/IrohBridge(\\$.*)?\\.class"))
                    bridgeOwners.add(e.getName().substring(0, e.getName().length() - 6));
            for (var e : classes) {
                String owner = e.getName().substring(0, e.getName().length() - 6);
                boolean bridge = bridgeOwners.contains(owner);
                try (InputStream in = z.getInputStream(e)) {
                    ClassReader cr = new ClassReader(in);
                    if (bridge) {
                        // every String constant in the pool (CONSTANT_String = tag 8)
                        char[] buf = new char[cr.getMaxStringLength()];
                        for (int i = 1; i < cr.getItemCount(); i++) {
                            int off = cr.getItem(i);
                            if (off > 0 && cr.b[off - 1] == 8) bridgeStrings.add(cr.readUTF8(off, buf));
                        }
                    }
                    cr.accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override public MethodVisitor visitMethod(int a, String mn, String d, String sg, String[] x) {
                            ownMethods.add(mn);
                            return new MethodVisitor(Opcodes.ASM9) {
                                final List<String> pendingLdc = new ArrayList<>();
                                @Override public void visitLdcInsn(Object v) { if (v instanceof String s) pendingLdc.add(s); }
                                @Override public void visitMethodInsn(int op, String o, String n, String desc, boolean itf) {
                                    if (n.equals("isModLoaded") && !pendingLdc.isEmpty()) modIds.add(pendingLdc.get(pendingLdc.size() - 1));
                                    if (op == Opcodes.INVOKESTATIC && bridgeOwners.contains(o) && !bridge)
                                        for (String s : pendingLdc) if (s.matches("[A-Z][A-Z0-9_]*")) enumArgs.add(s);
                                    pendingLdc.clear();
                                }
                            };
                        }
                    }, 0);
                }
            }
        }
        // api classes this jar's bridge names
        Set<String> named = new TreeSet<>();
        for (String s : bridgeStrings) {
            if (!s.startsWith("dev.elemore.iroh.api.")) continue;
            if (s.equals("dev.elemore.iroh.api.")) continue; // prefix for a concatenated name
            String internal = s.replace('.', '/');
            boolean ok = api.containsKey(internal);
            check(jn + ": class " + s, ok);
            if (ok) named.add(internal);
        }
        // identifiers: must resolve
        for (String s : bridgeStrings) {
            if (s.startsWith("dev.elemore.iroh.api.") || !s.matches("[A-Za-z_][A-Za-z0-9_]*")) continue;
            List<String> where = new ArrayList<>();
            for (String c : named) {
                ApiClass ac = api.get(c);
                if (ac.publicNames.contains(s) || ac.enumConstants.contains(s)) {
                    String simple = c.substring(API_PKG.length());
                    where.add(simple);
                    usedNames.add(simple + "." + s);
                }
            }
            if (where.isEmpty() && s.matches("[A-Z][A-Z0-9_]*"))
                // an enum constant compared by name on a value the bridge got back (e.g. capabilities() -> "SHADER_PACKS")
                for (var en : api.entrySet())
                    if (en.getValue().enumConstants.contains(s)) {
                        String simple = en.getKey().substring(API_PKG.length());
                        where.add(simple + " (by name)");
                        usedNames.add(simple + "." + s);
                    }
            if (!where.isEmpty()) check(jn + ": member " + s + " in " + where, true);
            else if (modIds.contains(s)) System.out.println("     " + jn + ": mod id " + s);
            else if (ownMethods.contains(s)) System.out.println("     " + jn + ": own method " + s);
            else check(jn + ": name " + s + " resolves to no member of the api classes the bridge names " + named, false);
        }
        // enum constants passed by name from callers
        for (String s : enumArgs) {
            List<String> where = new ArrayList<>();
            for (String c : named) if (api.get(c).enumConstants.contains(s)) where.add(c.substring(API_PKG.length()));
            check(jn + ": enum constant " + s + " passed to IrohBridge, in " + where, !where.isEmpty());
        }
        // functional interfaces: single abstract method, and that name is the one the bridge uses
        for (String c : named) {
            ApiClass ac = api.get(c);
            if ((ac.access & Opcodes.ACC_INTERFACE) == 0 || !ac.functional) continue;
            String only = ac.abstractNames.size() == 1 ? ac.abstractNames.iterator().next() : null;
            check(jn + ": " + c.substring(API_PKG.length()) + " is a single-abstract-method interface (" + ac.abstractNames
                    + ") and the bridge passes its name", only != null && bridgeStrings.contains(only));
        }
    }
}
