package dev.elemore.iroh.shaderpack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stage (ii), id maps: {@code block.properties}, {@code item.properties}, {@code entity.properties}, read with
 * the same conditional preprocessing as shaders.properties (standard + option macros).
 * Entry syntax: {@code name}, {@code ns:name}, {@code ns:name:prop=value:prop2=value} (a bare first segment is
 * minecraft:), several values of one property as {@code prop=a,b}. {@code layer.<solid|cutout|translucent>}
 * keys are kept apart. A name mapped twice keeps its first id (recorded as a conflict).
 */
public final class IdMaps implements Json.Dumpable {

    public record Entry(String id, Map<String, String> states) {}

    public static final class Map1 implements Json.Dumpable {
        public final String kind;
        public final Map<Integer, List<Entry>> ids = new TreeMap<>();
        public final Map<String, List<String>> layers = new LinkedHashMap<>();
        public final List<String> conflicts = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
        public boolean present;

        Map1(String kind) {
            this.kind = kind;
        }

        @Override
        public Object toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("present", present);
            int entries = 0;
            for (List<Entry> l : ids.values())
                entries += l.size();
            m.put("idCount", ids.size());
            m.put("entryCount", entries);
            Map<String, Object> ix = new LinkedHashMap<>();
            ids.forEach((id, l) -> {
                List<String> s = new ArrayList<>();
                for (Entry e : l) {
                    StringBuilder sb = new StringBuilder(e.id());
                    e.states().forEach((k, v) -> sb.append(':').append(k).append('=').append(v));
                    s.add(sb.toString());
                }
                ix.put(String.valueOf(id), s);
            });
            m.put("ids", ix);
            m.put("layers", layers);
            m.put("conflicts", conflicts);
            m.put("notes", notes);
            return m;
        }
    }

    public final Map1 blocks = new Map1("block");
    public final Map1 items = new Map1("item");
    public final Map1 entities = new Map1("entity");

    public static IdMaps read(PackSource pack, String dir, Map<String, String> macros) {
        IdMaps m = new IdMaps();
        for (Map1 map : List.of(m.blocks, m.items, m.entities)) {
            String file = map.kind + ".properties";
            String path = dir.isEmpty() ? file : dir + "/" + file;
            String text = pack.text(path);
            if (text == null && !dir.isEmpty()) {
                path = file;
                text = pack.text(path);
            }
            if (text == null)
                continue;
            map.present = true;
            PropertiesText p = PropertiesText.parse(path, text, macros);
            map.notes.addAll(p.notes());
            Map<String, Integer> seen = new LinkedHashMap<>();
            for (PropertiesText.Entry e : p.entries().values()) {
                String k = e.key();
                if (k.startsWith("layer.")) {
                    map.layers.put(k.substring(6), PackProperties.words(e.value()));
                    continue;
                }
                if (!k.startsWith(map.kind + ".")) {
                    map.notes.add(e.file() + ":" + e.line() + ": unknown key " + k);
                    continue;
                }
                int id;
                try {
                    id = Integer.parseInt(k.substring(map.kind.length() + 1));
                } catch (NumberFormatException ex) {
                    map.notes.add(e.file() + ":" + e.line() + ": id is not a number: " + k);
                    continue;
                }
                List<Entry> list = map.ids.computeIfAbsent(id, x -> new ArrayList<>());
                for (String w : PackProperties.words(e.value())) {
                    Entry en = parseEntry(w);
                    String key = w;
                    Integer prev = seen.putIfAbsent(key, id);
                    if (prev != null && prev != id)
                        map.conflicts.add(w + " in " + prev + " and " + id + " (first kept)");
                    else
                        list.add(en);
                }
            }
        }
        return m;
    }

    static Entry parseEntry(String w) {
        String[] seg = w.split(":");
        List<String> name = new ArrayList<>();
        Map<String, String> states = new LinkedHashMap<>();
        for (String s : seg) {
            int eq = s.indexOf('=');
            if (eq > 0)
                states.put(s.substring(0, eq), s.substring(eq + 1));
            else
                name.add(s);
        }
        String id = name.size() == 1 ? "minecraft:" + name.get(0) : String.join(":", name);
        return new Entry(id, states);
    }

    @Override
    public Object toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("block", blocks);
        m.put("item", items);
        m.put("entity", entities);
        return m;
    }
}
