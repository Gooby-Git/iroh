package dev.elemore.iroh.shaderpack.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.elemore.iroh.shaderpack.graph.PackData;

import java.util.HashMap;
import java.util.Map;

/**
 * The pack's block / item / entity id maps (block.properties, item.properties, entity.properties) as lookups for the
 * mesher (mc_Entity.x), entityId, blockEntityId, heldItemId (P3-5 "id maps"). Built from the front end's dump
 * (pack.json idMaps). An entry may carry block states ("minecraft:oak_log:axis=y"): {@link #block(String)} answers
 * the first id that names the block without states, else the first with any; -1 when the pack does not list it
 * (Iris' default). Exact state matching comes with the mesher hookup.
 */
public final class PackIdMaps {

    private final Map<String, Map<String, Integer>> byKind = new HashMap<>();

    private PackIdMaps() {}

    public static PackIdMaps of(PackData data) {
        PackIdMaps m = new PackIdMaps();
        JsonObject maps = data.pack.has("idMaps") ? data.pack.getAsJsonObject("idMaps") : new JsonObject();
        for (String kind : new String[]{"block", "item", "entity"}) {
            Map<String, Integer> plain = new HashMap<>(), stated = new HashMap<>();
            if (maps.has(kind) && maps.getAsJsonObject(kind).has("ids"))
                for (Map.Entry<String, JsonElement> e : maps.getAsJsonObject(kind).getAsJsonObject("ids").entrySet()) {
                    int id;
                    try {
                        id = Integer.parseInt(e.getKey());
                    } catch (NumberFormatException ex) {
                        continue;
                    }
                    for (JsonElement n : e.getValue().getAsJsonArray()) {
                        String s = n.isJsonPrimitive() ? n.getAsString() : n.getAsJsonObject().get("id").getAsString();
                        String[] parts = s.split(":");
                        String name = parts.length >= 2 ? parts[0] + ":" + parts[1] : "minecraft:" + parts[0];
                        if (parts.length > 2)
                            stated.putIfAbsent(name, id);
                        else
                            plain.putIfAbsent(name, id);
                    }
                }
            stated.forEach(plain::putIfAbsent);
            m.byKind.put(kind, plain);
        }
        return m;
    }

    /** registry id -> mc_Entity.x, for the mesher (PackStartup.blockIds) */
    public Map<String, Integer> blockMap() {
        return java.util.Collections.unmodifiableMap(new HashMap<>(byKind.get("block")));
    }

    public int block(String id) {
        return byKind.get("block").getOrDefault(id, -1);
    }

    public int item(String id) {
        return byKind.get("item").getOrDefault(id, -1);
    }

    public int entity(String id) {
        return byKind.get("entity").getOrDefault(id, -1);
    }

    public int size(String kind) {
        return byKind.getOrDefault(kind, Map.of()).size();
    }
}
