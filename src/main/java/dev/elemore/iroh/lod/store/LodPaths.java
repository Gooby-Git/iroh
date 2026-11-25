// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.store;

import java.nio.file.Path;

/**
 * Where LOD stores live. The paths are Vista's, unchanged, so existing worlds and caches are read in place (the format and
 * the folder names do not change; only the code that reads them did). No Minecraft types: callers pass the plain parts.
 * <pre>
 *   &lt;world&gt;/vista/&lt;namespace&gt;/&lt;dimension path&gt;/{L0..L4, blocks.dat, biomes.dat, visited.bin, structures.bin}
 *   &lt;world&gt;/vista/server-id
 *   &lt;world&gt;/vista/seed/&lt;namespace&gt;/&lt;dimension path&gt;/          seed LODs
 *   &lt;gameDir&gt;/vista-cache/&lt;server id&gt;/&lt;namespace&gt;/&lt;dimension path&gt;/     client cache of a remote server
 *   &lt;gameDir&gt;/vista-cache/local-&lt;server&gt;/&lt;namespace&gt;/&lt;dimension path&gt;/  client-only capture
 * </pre>
 */
public final class LodPaths {
    private LodPaths() {}

    /** The LOD folder of a world save (its root directory is the server's {@code LevelResource.ROOT}). */
    public static Path worldRoot(Path worldDir) {
        return worldDir.resolve("vista");
    }

    public static Path dimension(Path worldDir, String namespace, String path) {
        return worldRoot(worldDir).resolve(namespace).resolve(path);
    }

    public static Path serverIdFile(Path worldDir) {
        return worldRoot(worldDir).resolve("server-id");
    }

    public static Path seedDimension(Path worldDir, String namespace, String path) {
        return worldRoot(worldDir).resolve("seed").resolve(namespace).resolve(path);
    }

    public static Path clientCache(Path gameDir) {
        return gameDir.resolve("vista-cache");
    }

    public static Path remoteDimension(Path gameDir, String serverId, String namespace, String path) {
        return clientCache(gameDir).resolve(serverId).resolve(namespace).resolve(path);
    }

    /** Client-only capture of a server that has no LOD data of its own. */
    public static Path localCaptureDimension(Path gameDir, String server, String namespace, String path) {
        return clientCache(gameDir).resolve("local-" + server.replaceAll("[^A-Za-z0-9.-]", "_")).resolve(namespace).resolve(path);
    }
}
