package dev.elemore.iroh.packtest;

import dev.elemore.iroh.shaderpack.FrontEnd;
import dev.elemore.iroh.shaderpack.PackDump;
import dev.elemore.iroh.shaderpack.PackSource;
import dev.elemore.iroh.shaderpack.StandardMacros;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Offline Iris-pack front end harness (P3-1). {@code ./gradlew packHarness [-Ppack=<pack dir|zip|shaderpacks dir>]
 * [-Poptions=<file>] [-PallowFail] [-Pout=<dir>] [-Pnovista] [-Pshow=<unit id>]}. Reads packs read-only, writes
 * JSON / GLSL / SPIR-V under {@code build/packHarness/<pack>/}, prints one line per unit (PASS / FAIL stage:
 * message) and a summary with failures grouped by class. Exit 1 on any FAIL unless allowFail. No GPU.
 * <p>
 * The options file defaults to {@code <pack>.txt} beside the pack (the file Iris saves). Packs are never copied
 * into the repository (Complementary licence): they are read where they lie.
 */
public final class PackHarness {

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new LinkedHashMap<>();
        for (String a : args) {
            int eq = a.indexOf('=');
            if (a.startsWith("--"))
                opt.put(eq > 0 ? a.substring(2, eq) : a.substring(2), eq > 0 ? a.substring(eq + 1) : "true");
        }
        Path packArg = Paths.get(opt.getOrDefault("pack",
                System.getProperty("user.home") + "/.local/share/atlauncher/instances/Vulkan/shaderpacks"));
        Path out = Paths.get(opt.getOrDefault("out", "build/packHarness"));
        boolean allowFail = opt.containsKey("allowFail") && !opt.get("allowFail").equals("false");
        String show = opt.get("show");

        List<Path> packs = new ArrayList<>();
        if (Files.isDirectory(packArg) && !Files.isDirectory(packArg.resolve("shaders"))) {
            try (Stream<Path> s = Files.list(packArg)) {
                s.sorted().forEach(p -> {
                    if ((Files.isDirectory(p) && Files.isDirectory(p.resolve("shaders"))) || p.toString().endsWith(".zip"))
                        packs.add(p);
                });
            }
        } else {
            packs.add(packArg);
        }
        if (packs.isEmpty()) {
            System.out.println("# no packs under " + packArg);
            System.exit(1);
        }

        StandardMacros.Env d = StandardMacros.Env.defaults();
        StandardMacros.Env env = new StandardMacros.Env(d.vendor(), d.renderer(), d.os(), !opt.containsKey("novista"),
                d.labPbr(), d.features());
        int totalFail = 0;
        List<String> summaries = new ArrayList<>();
        for (Path p : packs) {
            String name = p.getFileName().toString();
            Path optFile = opt.containsKey("options") && packs.size() == 1 ? Paths.get(opt.get("options"))
                    : p.resolveSibling(name + ".txt");
            String saved = Files.isRegularFile(optFile) ? Files.readString(optFile, StandardCharsets.UTF_8) : null;
            PackSource src;
            try {
                src = PackSource.open(p);
            } catch (Exception e) {
                System.out.println("FAIL " + name + " pack: " + e.getMessage());
                totalFail++;
                summaries.add("# pack " + name + ": not readable (" + e.getMessage() + ")");
                continue;
            }
            Path dir = out.resolve(safe(name));
            deleteTree(dir);
            FrontEnd.PackResult r = FrontEnd.run(src, new FrontEnd.Config(env, saved, true, dir));
            System.out.println("# pack " + name + (saved != null ? " (options " + optFile.getFileName() + ", "
                    + r.options.savedApplied().size() + " applied, " + r.options.savedUnknown().size() + " unknown)" : " (default options)"));
            for (String e : r.packErrors)
                System.out.println("FAIL " + name + " pack: " + e);
            int pass = 0;
            int fail = 0;
            int off = 0;
            List<FrontEnd.Unit> sorted = new ArrayList<>(r.units);
            sorted.sort((a, b) -> a.id.compareTo(b.id));
            for (FrontEnd.Unit u : sorted) {
                String tag = u.enabled ? "" : " (off: " + u.offReason + ")";
                if (u.passed()) {
                    pass++;
                    System.out.println("PASS " + u.id + tag);
                } else {
                    if (u.enabled)
                        fail++;
                    else
                        off++;
                    System.out.println((u.enabled ? "FAIL " : "FAIL-OFF ") + u.id + tag + " " + u.failStage + ": " + u.failMessage);
                }
                if (show != null && show.equals(u.id) && u.result != null)
                    u.result.glsl.forEach((s, g) -> System.out.println("----- " + u.id + " " + s.key + "\n" + g));
            }
            fail += r.packErrors.size();
            // self-made test packs carry iroh-expect.txt: expected failures and facts about the dump
            Path expect = p.resolve("iroh-expect.txt");
            if (Files.isRegularFile(expect)) {
                int[] ef = Expect.check(r, Files.readAllLines(expect, StandardCharsets.UTF_8), dir);
                System.out.println("# expect " + name + ": " + ef[0] + " checked, " + ef[1] + " wrong");
                // expected unit failures are not failures of the front end; wrong expectations are
                fail = ef[1] + (int) r.units.stream().filter(u -> !u.passed() && u.enabled && !Expect.expectedFail(u.id)).count()
                        + (r.packErrors.isEmpty() || Expect.expectedPackError() ? 0 : r.packErrors.size());
                Expect.reset();
            }
            Map<String, List<FrontEnd.Unit>> classes = FrontEnd.failureClasses(r);
            StringBuilder sb = new StringBuilder();
            sb.append("# pack ").append(name).append(": units ").append(r.units.size()).append("  PASS ").append(pass)
              .append("  FAIL ").append(fail).append(off > 0 ? "  FAIL-OFF " + off : "").append("  classes ").append(classes.size())
              .append("  ").append(r.millis).append(" ms  -> ").append(dir);
            for (Map.Entry<String, List<FrontEnd.Unit>> c : classes.entrySet())
                sb.append("\n#   ").append(c.getValue().size()).append("x ").append(c.getKey())
                  .append("\n#      e.g. ").append(c.getValue().get(0).id).append(": ").append(trim(c.getValue().get(0).failMessage));
            Map<String, Object> rt = PackDump.renderTargets(r, 2560, 1440);
            for (Map.Entry<String, Object> e : rt.entrySet()) {
                @SuppressWarnings("unchecked") Map<String, Object> v = (Map<String, Object>) ((Map<String, Object>) e.getValue()).get("vram");
                sb.append("\n#   vram@1440p ").append(e.getKey()).append(": ").append(v);
            }
            summaries.add(sb.toString());
            System.out.println(sb);
            totalFail += fail;
        }
        if (packs.size() > 1) {
            System.out.println("# ===== summary");
            for (String s : summaries)
                System.out.println(s.lines().findFirst().orElse(s));
        }
        System.out.println("# total FAIL " + totalFail + (allowFail ? " (allowFail)" : ""));
        System.exit(totalFail > 0 && !allowFail ? 1 : 0);
    }

    static String trim(String s) {
        return s == null ? "" : s.length() > 220 ? s.substring(0, 220) + "..." : s;
    }

    static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._+-]", "_");
    }

    static void deleteTree(Path p) throws java.io.IOException {
        if (!Files.exists(p))
            return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(java.util.Comparator.reverseOrder()).toList())
                Files.delete(x);
        }
    }
}
