package dev.elemore.iroh.shadertest;

import dev.elemore.iroh.glshim.GlPrograms;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Raw-GL program cases (P1-2 slice 2, item 2): the sources mods hand to glShaderSource, linked through
 * {@link GlPrograms.LinkResult#link} (GlslTranslator + shaderc, the code the game runs). Cases come from
 * {@code src/test/resources/glprograms/index.txt}, plus every program a Proctor run dumped with
 * {@code -Diroh.glshim.dumpPrograms=<dir>} when {@code -Pprograms=<dir>} is given (expected PASS).
 */
final class GlProgramCases {
    private GlProgramCases() {}

    static List<String> run(Path mods, String dumpDir, String show, StringBuilder detail) {
        List<String> out = new ArrayList<>();
        try (InputStream idx = GlProgramCases.class.getResourceAsStream("/glprograms/index.txt")) {
            if (idx == null) return out;
            for (String line : new String(idx.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split("\\s+", 4);
                String vs = read(f[1], mods), fs = read(f[2], mods);
                if (vs == null || fs == null) {
                    out.add("FAIL glprogram " + f[0] + " iroh missing-source " + (vs == null ? f[1] : f[2]));
                    continue;
                }
                out.add(one(f[0], vs, fs, f[3], show, detail));
            }
        } catch (IOException e) {
            out.add("FAIL glprogram index iroh io " + e);
        }
        if (dumpDir != null) {
            try (Stream<Path> s = Files.list(Path.of(dumpDir))) {
                for (Path v : s.filter(p -> p.toString().endsWith(".vsh")).sorted().toList()) {
                    Path fr = Path.of(v.toString().replaceAll("\\.vsh$", ".fsh"));
                    if (!Files.exists(fr)) continue;
                    String id = "dump/" + v.getFileName().toString().replaceAll("\\.vsh$", "");
                    out.add(one(id, Files.readString(v), Files.readString(fr), "PASS", show, detail));
                }
            } catch (IOException e) {
                out.add("FAIL glprogram dump iroh io " + e);
            }
        }
        return out;
    }

    private static String one(String id, String vs, String fs, String expect, String show, StringBuilder detail) {
        GlPrograms.LinkResult r = GlPrograms.LinkResult.link(vs, fs, new HashMap<>(), true);
        if (r.vertex != null) r.vertex.free();
        if (r.fragment != null) r.fragment.free();
        if (id.equals(show) && r.output != null)
            detail.append("\n==== ").append(id).append(" vertex\n").append(r.output.vertex())
                  .append("\n==== ").append(id).append(" fragment\n").append(r.output.fragment());
        String msg = r.ok ? "linked, attributes " + r.attributes + ", " + r.output.uniforms().size() + " block uniforms, "
                + r.output.samplers().size() + " samplers" : r.log.split("\n")[0];
        boolean pass = expect.equals("PASS") ? r.ok : (!r.ok && r.log.contains(expect.substring(5)));
        return (pass ? "PASS" : "FAIL") + " glprogram " + id + " iroh " + (expect.equals("PASS") ? "" : "expected-fail ") + msg;
    }

    private static String read(String src, Path mods) throws IOException {
        if (!src.startsWith("jar:")) {
            try (InputStream in = GlProgramCases.class.getResourceAsStream("/glprograms/" + src)) {
                return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        String[] p = src.substring(4).split("!", 2);
        try (Stream<Path> s = Files.list(mods)) {
            Path jar = s.filter(x -> x.getFileName().toString().startsWith(p[0])).findFirst().orElse(null);
            if (jar == null) return null;
            try (ZipFile z = new ZipFile(jar.toFile())) {
                ZipEntry e = z.getEntry(p[1]);
                if (e == null) return null;
                try (InputStream in = z.getInputStream(e)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
    }
}
