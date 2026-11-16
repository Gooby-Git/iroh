package dev.elemore.iroh.cache;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Small on-disk cache under {@code <game dir>/config/iroh/cache/}.
 * File layout: 32-byte SHA-256 of the payload, then the payload.
 * Every failure (missing, unreadable, corrupt, unwritable) is silent: callers fall back to recomputing.
 */
public final class DiskCache {
    private static final int HASH_SIZE = 32;

    private DiskCache() {}

    public static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("iroh").resolve("cache");
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** @return the verified payload, or null if the file is absent, unreadable or corrupt */
    public static byte[] read(String name) {
        try {
            byte[] all = Files.readAllBytes(dir().resolve(name));
            if (all.length <= HASH_SIZE)
                return null;

            byte[] payload = Arrays.copyOfRange(all, HASH_SIZE, all.length);
            if (!MessageDigest.isEqual(Arrays.copyOfRange(all, 0, HASH_SIZE), sha256().digest(payload)))
                return null;

            return payload;
        } catch (Exception e) {
            return null;
        }
    }

    /** Atomic best-effort write. */
    public static void write(String name, byte[] payload) {
        try {
            Path dir = dir();
            Files.createDirectories(dir);

            byte[] out = new byte[HASH_SIZE + payload.length];
            System.arraycopy(sha256().digest(payload), 0, out, 0, HASH_SIZE);
            System.arraycopy(payload, 0, out, HASH_SIZE, payload.length);

            Path tmp = Files.createTempFile(dir, name, ".tmp");
            try {
                Files.write(tmp, out);
                Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | RuntimeException e) {
            // cache is optional
        }
    }
}
