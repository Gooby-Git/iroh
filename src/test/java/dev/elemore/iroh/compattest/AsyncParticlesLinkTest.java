package dev.elemore.iroh.compattest;

import dev.elemore.iroh.compat.LinkSurface;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * rc3r2 (td-c8876c): every reference AsyncParticles' Fabric classes make into net.vulkanmod (calls, fields, NEW,
 * method refs, its compat mixins' @Shadow / @Accessor / @Inject / @At targets) must resolve in the built Iroh jar exactly.
 * Reads the AsyncParticles jar from the instance mods folder at test time (never copied). Usage:
 * {@code ./gradlew asyncParticlesLinkTest [-Pmods=dir] [-Piroh=jar]}; last line "# aplink PASS n/n" (n = references).
 */
public final class AsyncParticlesLinkTest {
    public static void main(String[] args) throws Exception {
        Path mods = Paths.get(args.length > 0 && !args[0].isEmpty() ? args[0]
                : System.getProperty("user.home") + "/.local/share/atlauncher/instances/Vulkan/mods");
        Path iroh = args.length > 1 && !args[1].isEmpty() ? Paths.get(args[1]) : findIroh();
        Path ap;
        try (Stream<Path> s = Files.list(mods)) {
            ap = s.filter(p -> p.getFileName().toString().startsWith("AsyncParticles-") && p.toString().endsWith(".jar"))
                  .findFirst().orElseThrow(() -> new IllegalStateException("no AsyncParticles-*.jar in " + mods));
        }
        System.out.println("# AsyncParticles " + ap.getFileName() + " vs " + iroh);
        LinkSurface ls = new LinkSurface(iroh, o -> o.startsWith("net/vulkanmod/"));
        ls.check(ap, n -> n.startsWith("fabric/")); // the Fabric half; neoforge/ is never loaded here
        ls.problems.forEach(p -> System.out.println("MISSING " + p.where() + ": " + p.what()));
        int ok = ls.checked - ls.problems.size();
        System.out.println("# aplink " + (ls.problems.isEmpty() ? "PASS " : "FAIL ") + ok + "/" + ls.checked);
        if (!ls.problems.isEmpty()) System.exit(1);
    }

    static Path findIroh() throws Exception {
        try (Stream<Path> s = Files.list(Paths.get("build/libs"))) {
            return s.filter(p -> p.getFileName().toString().matches("iroh-.*\\.jar") && !p.toString().contains("sources"))
                    .findFirst().orElseThrow(() -> new IllegalStateException("build the Iroh jar first (./build.sh)"));
        }
    }
}
