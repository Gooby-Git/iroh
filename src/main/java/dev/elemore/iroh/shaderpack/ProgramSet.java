package dev.elemore.iroh.shaderpack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage (iv): dimension folders and program discovery with the documented fallback chains.
 * <ul>
 *   <li>Dimensions: {@code dimension.properties} ({@code dimension.<folder>=<ids>}, {@code *} = any other) when
 *       present, else {@code world0}, {@code world-1}, {@code world1} if they exist (OptiFine). A dimension folder
 *       REPLACES the root set (existence decides, not contents).</li>
 *   <li>Programs by extension: .vsh .fsh .gsh .tcs .tes (raster stages) and .csh (compute: {@code name.csh}
 *       first, then {@code name_a.csh .. name_z.csh}, stopping at the first missing letter).</li>
 *   <li>Fallbacks: the parent map of Vitrail's ProgramFallbacks (LGPL-3.0-only, Iris-derived; see NOTICE). A disabled
 *       program counts as missing and the walk continues. Numbered families and final have no fallback.</li>
 * </ul>
 */
public final class ProgramSet implements Json.Dumpable {

    public static final Map<String, String> FALLBACK = new LinkedHashMap<>();

    static {
        String[][] f = {
                {"gbuffers_line", "gbuffers_basic"}, {"gbuffers_textured", "gbuffers_basic"},
                {"gbuffers_textured_lit", "gbuffers_textured"}, {"gbuffers_skybasic", "gbuffers_basic"},
                {"gbuffers_skytextured", "gbuffers_textured"}, {"gbuffers_clouds", "gbuffers_textured"},
                {"gbuffers_terrain", "gbuffers_textured_lit"}, {"gbuffers_terrain_solid", "gbuffers_terrain"},
                {"gbuffers_terrain_cutout", "gbuffers_terrain"}, {"gbuffers_damagedblock", "gbuffers_terrain"},
                {"gbuffers_block", "gbuffers_terrain"}, {"gbuffers_block_translucent", "gbuffers_block"},
                {"gbuffers_beaconbeam", "gbuffers_textured"}, {"gbuffers_item", "gbuffers_textured_lit"},
                {"gbuffers_entities", "gbuffers_textured_lit"}, {"gbuffers_entities_translucent", "gbuffers_entities"},
                {"gbuffers_entities_glowing", "gbuffers_entities"}, {"gbuffers_armor_glint", "gbuffers_textured"},
                {"gbuffers_spidereyes", "gbuffers_textured"}, {"gbuffers_hand", "gbuffers_textured_lit"},
                {"gbuffers_hand_water", "gbuffers_hand"}, {"gbuffers_weather", "gbuffers_textured_lit"},
                {"gbuffers_water", "gbuffers_terrain"}, {"gbuffers_particles", "gbuffers_textured_lit"},
                {"gbuffers_particles_translucent", "gbuffers_particles"}, {"gbuffers_lightning", "gbuffers_entities"},
                {"shadow_solid", "shadow"}, {"shadow_cutout", "shadow"}, {"shadow_water", "shadow"},
                {"shadow_entities", "shadow"}, {"shadow_lightning", "shadow_entities"}, {"shadow_block", "shadow"},
                {"dh_water", "dh_terrain"}, {"dh_generic", "dh_terrain"},
        };
        for (String[] p : f)
            FALLBACK.put(p[0], p[1]);
    }

    /** Programs Iris draws (gbuffers family + shadow + LOD); everything numbered is matched by pattern. */
    public static final List<String> GBUFFERS = List.of("gbuffers_basic", "gbuffers_line", "gbuffers_textured",
            "gbuffers_textured_lit", "gbuffers_skybasic", "gbuffers_skytextured", "gbuffers_clouds", "gbuffers_terrain",
            "gbuffers_terrain_solid", "gbuffers_terrain_cutout", "gbuffers_damagedblock", "gbuffers_block",
            "gbuffers_block_translucent", "gbuffers_beaconbeam", "gbuffers_item", "gbuffers_entities",
            "gbuffers_entities_translucent", "gbuffers_entities_glowing", "gbuffers_armor_glint", "gbuffers_spidereyes",
            "gbuffers_hand", "gbuffers_hand_water", "gbuffers_weather", "gbuffers_water", "gbuffers_particles",
            "gbuffers_particles_translucent", "gbuffers_lightning",
            "shadow", "shadow_solid", "shadow_cutout", "shadow_water", "shadow_entities", "shadow_lightning", "shadow_block", "dh_terrain", "dh_water", "dh_shadow", "dh_generic");

    private static final Pattern FULLSCREEN = Pattern.compile("(begin|prepare|deferred|composite|shadowcomp)([1-9][0-9]?)?|deferred_pre|composite_pre|final|setup");

    public enum Family { GBUFFERS, SHADOW, FULLSCREEN, LOD, EXTRA }

    public static final class Program {
        public final String dir;        // "" root, "world0", ...
        public final String name;
        public final Map<String, String> stages = new TreeMap<>();   // vertex/fragment/geometry/tess_control/tess_eval -> path
        public final List<String> computes = new ArrayList<>();      // paths in dispatch order
        public final List<String> notes = new ArrayList<>();
        public boolean enabled = true;
        public String toggleKey;

        Program(String dir, String name) {
            this.dir = dir;
            this.name = name;
        }

        public String id() {
            return dir.isEmpty() ? name : dir + "/" + name;
        }

        public Family family() {
            return ProgramSet.family(name);
        }
    }

    public static Family family(String name) {
        if (name.equals("shadow") || name.startsWith("shadow_"))
            return Family.SHADOW;
        if (name.startsWith("gbuffers_"))
            return Family.GBUFFERS;
        if (name.startsWith("dh_") || name.startsWith("vista_") || name.startsWith("voxy_"))
            return Family.LOD;
        if (FULLSCREEN.matcher(name).matches())
            return Family.FULLSCREEN;
        return Family.EXTRA;
    }

    public record Dimension(String folder, List<String> ids, boolean exists) {}

    public final List<Dimension> dimensions = new ArrayList<>();
    public final Map<String, Map<String, Program>> byDir = new LinkedHashMap<>();
    public final List<String> notes = new ArrayList<>();
    public boolean dimensionProperties;

    private static final Pattern FILE = Pattern.compile("([^/]+)\\.(vsh|fsh|gsh|tcs|tes|csh)");
    private static final Pattern COMPUTE_LETTER = Pattern.compile("(.+)_([a-z])");

    public static ProgramSet discover(PackSource pack, Map<String, String> standardMacros, Map<String, Boolean> toggles) {
        ProgramSet ps = new ProgramSet();
        String dimText = pack.text("dimension.properties");
        if (dimText != null) {
            ps.dimensionProperties = true;
            PropertiesText p = PropertiesText.parse("dimension.properties", dimText, standardMacros);
            for (PropertiesText.Entry e : p.entries().values()) {
                if (!e.key().startsWith("dimension."))
                    continue;
                String folder = e.key().substring(10);
                ps.dimensions.add(new Dimension(folder, PackProperties.words(e.value()), pack.isDirectory(folder)));
            }
        } else {
            for (String[] d : new String[][]{{"world0", "minecraft:overworld"}, {"world-1", "minecraft:the_nether"}, {"world1", "minecraft:the_end"}})
                if (pack.isDirectory(d[0]))
                    ps.dimensions.add(new Dimension(d[0], List.of(d[1]), true));
        }
        List<String> dirs = new ArrayList<>();
        dirs.add("");
        for (Dimension d : ps.dimensions)
            if (d.exists() && !dirs.contains(d.folder()))
                dirs.add(d.folder());
            else if (!d.exists())
                ps.notes.add("dimension folder " + d.folder() + " named in dimension.properties does not exist: that dimension uses the root programs");

        for (String dir : dirs) {
            Map<String, Program> progs = new TreeMap<>();
            Map<String, Map<Character, String>> letters = new TreeMap<>();
            String prefix = dir.isEmpty() ? "" : dir + "/";
            for (String path : pack.paths()) {
                if (!path.startsWith(prefix))
                    continue;
                String rest = path.substring(prefix.length());
                Matcher m = FILE.matcher(rest);
                if (!m.matches())
                    continue;
                String base = m.group(1);
                String ext = m.group(2);
                if (ext.equals("csh")) {
                    Matcher l = COMPUTE_LETTER.matcher(base);
                    String prog = base;
                    char letter = '\0';
                    if (l.matches()) {
                        prog = l.group(1);
                        letter = l.group(2).charAt(0);
                    }
                    letters.computeIfAbsent(prog, k -> new TreeMap<>()).put(letter, path);
                    progs.computeIfAbsent(prog, k -> new Program(dir, k));
                    continue;
                }
                Program p = progs.computeIfAbsent(base, k -> new Program(dir, k));
                p.stages.put(switch (ext) {
                    case "vsh" -> "vertex";
                    case "fsh" -> "fragment";
                    case "gsh" -> "geometry";
                    case "tcs" -> "tess_control";
                    default -> "tess_eval";
                }, path);
            }
            letters.forEach((prog, map) -> {
                Program p = progs.get(prog);
                if (map.containsKey('\0'))
                    p.computes.add(map.get('\0'));
                char c = 'a';
                while (map.containsKey(c)) {
                    p.computes.add(map.get(c));
                    c++;
                }
                for (char x : map.keySet())
                    if (x != '\0' && x >= c)
                        p.notes.add("compute " + map.get(x) + " is not run: letter " + c + " is missing before it");
            });
            for (Program p : progs.values()) {
                String key = dir.isEmpty() ? p.name : dir + "/" + p.name;
                if (toggles.containsKey(key)) {
                    p.enabled = toggles.get(key);
                    p.toggleKey = key;
                } else if (toggles.containsKey(p.name)) {
                    p.enabled = toggles.get(p.name);
                    p.toggleKey = p.name;
                }
                if (!p.stages.isEmpty() && (!p.stages.containsKey("vertex") || !p.stages.containsKey("fragment")))
                    p.notes.add("raster program without both .vsh and .fsh: " + p.stages.keySet());
            }
            if (!progs.isEmpty() || !dir.isEmpty())
                ps.byDir.put(dir, progs);
        }
        return ps;
    }

    /** The program that draws {@code name} in {@code dir}: itself, or the first present+enabled ancestor. */
    public String resolve(String dir, String name) {
        Map<String, Program> progs = byDir.getOrDefault(dir, Map.of());
        String n = name;
        int guard = 0;
        while (n != null && guard++ < 32) {
            Program p = progs.get(n);
            if (p != null && p.enabled && !p.stages.isEmpty())
                return n;
            n = FALLBACK.get(n);
        }
        return null;
    }

    @Override
    public Object toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> dims = new ArrayList<>();
        for (Dimension d : dimensions)
            dims.add(Json.obj("folder", d.folder(), "ids", d.ids(), "exists", d.exists()));
        m.put("dimensionProperties", dimensionProperties);
        m.put("dimensions", dims);
        Map<String, Object> dirs = new LinkedHashMap<>();
        byDir.forEach((dir, progs) -> {
            Map<String, Object> d = new LinkedHashMap<>();
            Map<String, Object> ps = new LinkedHashMap<>();
            progs.forEach((n, p) -> {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("family", p.family().name());
                pm.put("stages", p.stages);
                pm.put("computes", p.computes);
                pm.put("enabled", p.enabled);
                if (p.toggleKey != null)
                    pm.put("toggleKey", p.toggleKey);
                if (!p.notes.isEmpty())
                    pm.put("notes", p.notes);
                ps.put(n, pm);
            });
            d.put("programs", ps);
            Map<String, Object> fb = new LinkedHashMap<>();
            for (String g : GBUFFERS) {
                String r = resolve(dir, g);
                fb.put(g, r);
            }
            d.put("drawnBy", fb);
            dirs.put(dir.isEmpty() ? "<root>" : dir, d);
        });
        m.put("folders", dirs);
        m.put("notes", notes);
        return m;
    }
}
