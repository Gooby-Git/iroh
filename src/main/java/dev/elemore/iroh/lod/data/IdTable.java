// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.data;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A per-world table of names to small ids, kept forever: ids are baked into every stored section, so a name
 * never changes id and entries are only ever appended. One text line per id, {@code <class> <name>}, so the
 * offline tools can read it without Minecraft.
 *
 * <p>Used twice per world: block states (name = the state string, class = {@link BlockClass}) and biomes
 * (class unused, 0). When the table is full, {@link #overflowId} is returned for new names.</p>
 */
public final class IdTable implements ClassTable {
    private final Path file;
    private final int capacity;
    private final int overflowId;
    private final Map<String, Integer> ids = new HashMap<>();
    private final List<String> names = new ArrayList<>();
    private volatile byte[] classes = new byte[256];
    private volatile int count;
    private BufferedWriter writer;
    private boolean overflowLogged;
    /** Told about every id added (the native store mirrors block classes); set once, before the table is shared. */
    private volatile ClassSink sink;

    public interface ClassSink {
        void set(int id, int cls);
    }

    /** Hands the current classes to {@code sink} and every later one as it is added. */
    public synchronized void mirrorClasses(ClassSink sink, java.util.function.BiConsumer<byte[], Integer> bulk) {
        bulk.accept(Arrays.copyOf(classes, count), count);
        this.sink = sink;
    }

    public IdTable(Path file, int capacity, int overflowId) throws IOException {
        this.file = file;
        this.capacity = capacity;
        this.overflowId = overflowId;
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                int space = line.indexOf(' ');
                put(line.substring(space + 1), Integer.parseInt(line.substring(0, space)));
            }
        }
    }

    /** Read-only copy for tools; nothing is ever written. */
    public static IdTable read(Path file) throws IOException {
        return new IdTable(file, Integer.MAX_VALUE, -1);
    }

    public synchronized int idOf(String name, int cls) {
        Integer id = ids.get(name);
        if (id != null) return id;
        if (names.size() >= capacity) {
            if (!overflowLogged) {
                overflowLogged = true;
                org.apache.logging.log4j.LogManager.getLogger("Iroh").warn("Distant Terrain: {} is full ({} entries); new names share id {}", file.getFileName(), capacity, overflowId);
            }
            return overflowId;
        }
        int newId = put(name, cls);
        try {
            if (writer == null) {
                Files.createDirectories(file.getParent());
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            writer.write(cls + " " + name);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Distant Terrain: could not append to " + file, e);
        }
        return newId;
    }

    private int put(String name, int cls) {
        int id = names.size();
        names.add(name);
        ids.put(name, id);
        byte[] c = classes;
        if (id >= c.length) c = Arrays.copyOf(c, Math.max(id + 1, c.length * 2));
        c[id] = (byte) cls;
        classes = c;
        count = id + 1;
        ClassSink k = sink;
        if (k != null) k.set(id, cls);
        return id;
    }

    public synchronized String nameOf(int id) {
        return id >= 0 && id < names.size() ? names.get(id) : null;
    }

    public synchronized int size() {
        return names.size();
    }

    @Override
    public int classOf(int block) {
        if (block < 0 || block >= count) return BlockClass.SOLID;
        return classes[block];
    }

    public synchronized void close() throws IOException {
        if (writer != null) writer.close();
        writer = null;
    }
}
