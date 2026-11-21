package dev.elemore.iroh.packtest;

import dev.elemore.iroh.shaderpack.FrontEnd;
import dev.elemore.iroh.shaderpack.Json;
import dev.elemore.iroh.shaderpack.PackSource;
import dev.elemore.iroh.shaderpack.StandardMacros;
import dev.elemore.iroh.shaderpack.graph.PackData;
import dev.elemore.iroh.shaderpack.graph.PackPlan;

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
 * Offline pass-graph harness (P3-2). {@code ./gradlew packGraph [-Ppack=<pack|zip|shaderpacks dir>] [-PallowFail]}:
 * runs the P3-1 front end into {@code build/packHarness/<pack>/}, reads that dump back ({@link PackData}: the graph is
 * built against the JSON contract), builds the frame plans, resource plan, uniform supply, geometry needs and the
 * upscaler cut analysis, writes {@code build/packGraph/<pack>/graph.json} + {@code graph.txt}, and prints one summary
 * line per folder. Exit 1 on any validation ERROR unless allowFail. Test packs carry {@code iroh-expect-graph.txt}
 * (same grammar as iroh-expect.txt, json paths relative to build/packGraph/<pack>/). No GPU, nothing launched.
 */
public final class PackGraphHarness {

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new LinkedHashMap<>();
        for (String a : args) {
            int eq = a.indexOf('=');
            if (a.startsWith("--"))
                opt.put(eq > 0 ? a.substring(2, eq) : a.substring(2), eq > 0 ? a.substring(eq + 1) : "true");
        }
        Path packArg = Paths.get(opt.getOrDefault("pack",
                System.getProperty("user.home") + "/.local/share/atlauncher/instances/Vulkan/shaderpacks"));
        boolean allowFail = opt.containsKey("allowFail");
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
        int errors = 0;
        List<String> summary = new ArrayList<>();
        for (Path p : packs) {
            String name = p.getFileName().toString();
            Path optFile = opt.containsKey("options") && packs.size() == 1 ? Paths.get(opt.get("options")) : p.resolveSibling(name + ".txt");
            String saved = Files.isRegularFile(optFile) ? Files.readString(optFile, StandardCharsets.UTF_8) : null;
            Path dump = Paths.get("build/packHarness").resolve(PackHarness.safe(name));
            Path out = Paths.get("build/packGraph").resolve(PackHarness.safe(name));
            PackHarness.deleteTree(dump);
            PackHarness.deleteTree(out);
            FrontEnd.PackResult r = FrontEnd.run(PackSource.open(p), new FrontEnd.Config(StandardMacros.Env.defaults(), saved, true, dump));
            if (!r.packErrors.isEmpty()) {
                System.out.println("# graph " + name + ": pack refused by the front end: " + r.packErrors);
                summary.add("# graph " + name + ": refused (" + r.packErrors.get(0) + ")");
                continue;
            }
            PackData data = PackData.load(dump);
            PackPlan plan = PackPlan.build(data);
            Files.createDirectories(out);
            Files.writeString(out.resolve("graph.json"), Json.write(plan.toJson()), StandardCharsets.UTF_8);
            Files.writeString(out.resolve("graph.txt"), plan.toText(), StandardCharsets.UTF_8);
            int e = plan.errors();
            for (String line : plan.summaryLines()) {
                System.out.println("# graph " + name + " " + line);
                summary.add("# graph " + name + " " + line);
            }
            for (String err : plan.errorLines())
                System.out.println("ERROR " + name + " " + err);
            Path expect = p.resolve("iroh-expect-graph.txt");
            if (Files.isRegularFile(expect)) {
                List<String> lines = new ArrayList<>(Files.readAllLines(expect, StandardCharsets.UTF_8));
                // "expectErrors N": the pack fails on purpose; N validation errors are the expected outcome
                for (java.util.Iterator<String> it = lines.iterator(); it.hasNext(); ) {
                    String l = it.next().strip();
                    if (l.startsWith("expectErrors")) {
                        int want = Integer.parseInt(l.substring(12).strip());
                        if (want != e)
                            System.out.println("EXPECT-WRONG expectErrors " + want + "  -> was " + e);
                        else
                            e = 0;
                        it.remove();
                    }
                }
                int[] ef = Expect.check(r, lines, out);
                System.out.println("# expect-graph " + name + ": " + ef[0] + " checked, " + ef[1] + " wrong");
                e += ef[1];
                Expect.reset();
            }
            errors += e;
        }
        if (packs.size() > 1) {
            System.out.println("# ===== summary");
            summary.forEach(System.out::println);
        }
        System.out.println("# graph total ERROR " + errors + (allowFail ? " (allowFail)" : ""));
        System.exit(errors > 0 && !allowFail ? 1 : 0);
    }
}
