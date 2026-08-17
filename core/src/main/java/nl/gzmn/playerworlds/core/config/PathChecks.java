package nl.gzmn.playerworlds.core.config;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Startup checks on the directories a node writes to.
 *
 * <p>Scratch, cache and quarantine must exist, be writable, and sit on the same
 * filesystem when reflink copies are expected between them (plan section 8.2,
 * section 10.3). Free space is measured on the scratch volume against the NFR-3
 * threshold. Failures throw {@link ConfigException} so enable refuses rather
 * than discovering a read-only mount on the first world create.
 */
public final class PathChecks {

    private PathChecks() {}

    /**
     * Ensures {@code path} exists as a directory and is writable.
     *
     * <p>Creates the directory (and parents) when absent. A node that cannot
     * create its own scratch on first boot is misconfigured; failing here names
     * the path instead of failing deep inside a world load.
     */
    public static void requireWritableDirectory(Path path, String configKey) {
        try {
            if (Files.exists(path)) {
                if (!Files.isDirectory(path)) {
                    throw new ConfigException(configKey + " exists and is not a directory: " + path);
                }
            } else {
                Files.createDirectories(path);
            }
            Path probe = path.resolve(".gzmn-write-probe");
            try {
                Files.writeString(probe, "ok");
            } finally {
                Files.deleteIfExists(probe);
            }
        } catch (ConfigException e) {
            throw e;
        } catch (IOException e) {
            throw new ConfigException(configKey + " is not a writable directory: " + path, e);
        }
    }

    /**
     * Requires every path to resolve onto the same {@link FileStore}.
     *
     * <p>Reflink copies ({@code cp --reflink=auto}) only work within one
     * filesystem. Scratch, cache and quarantine on different mounts silently
     * degrade every snapshot to a full copy, which is the expensive way to learn
     * the layout is wrong (plan section 10.4).
     */
    public static void requireSameFilesystem(Path first, Path second, String firstKey, String secondKey) {
        try {
            FileStore a = Files.getFileStore(first);
            FileStore b = Files.getFileStore(second);
            if (!a.equals(b)) {
                throw new ConfigException(firstKey + " ("
                        + first + ", " + a + ") and " + secondKey + " (" + second + ", " + b
                        + ") are on different filesystems; reflink snapshots require the same one");
            }
        } catch (ConfigException e) {
            throw e;
        } catch (IOException e) {
            throw new ConfigException(
                    "could not determine filesystem for " + firstKey + " / " + secondKey + ": " + e.getMessage(), e);
        }
    }

    /**
     * Refuses when usable free space on {@code path}'s volume is below the floor.
     *
     * @param minFreeBytes NFR-3 threshold; {@code 0} disables the check (tests)
     */
    public static void requireFreeSpace(Path path, long minFreeBytes, String configKey) {
        if (minFreeBytes <= 0) {
            return;
        }
        try {
            long usable = Files.getFileStore(path).getUsableSpace();
            if (usable < minFreeBytes) {
                throw new ConfigException(configKey + " has "
                        + usable + " bytes free, below the configured floor of " + minFreeBytes
                        + " (NFR-3); free space or lower storage.min-free-space-bytes");
            }
        } catch (ConfigException e) {
            throw e;
        } catch (IOException e) {
            throw new ConfigException("could not read free space for " + configKey + " at " + path, e);
        }
    }
}
