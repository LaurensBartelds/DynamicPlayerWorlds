package nl.gzmn.playerworlds.core.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * Structural validation of Anvil {@code .mca} region files (MN-5c).
 *
 * <p>Checks the 8 KiB header and each allocated chunk payload without decompressing
 * NBT:
 *
 * <ul>
 *   <li>file is at least the 8 KiB header;
 *   <li>each location entry's sector offset and count lie inside the file;
 *   <li>no two chunks claim overlapping sectors;
 *   <li>each chunk's big-endian length prefix is consistent with its sector count
 *       and does not run past the end of the file.
 * </ul>
 *
 * <p>A file that fails must abort the snapshot rather than be uploaded. Content
 * addressing already reads every byte to hash it, so this rides on that pass
 * via {@link ContentHasher#hashAndValidate(Path, boolean)}.
 */
public final class RegionStructure {

    /** Location table + timestamp table. */
    public static final int HEADER_BYTES = 8192;

    /** Anvil sector size. */
    public static final int SECTOR_BYTES = 4096;

    /** Chunks addressed by one region file (32×32). */
    public static final int CHUNK_COUNT = 1024;

    private RegionStructure() {}

    /** Whether {@code path}'s file name looks like an Anvil region file. */
    public static boolean isRegionFileName(Path path) {
        Objects.requireNonNull(path, "path");
        Path name = path.getFileName();
        if (name == null) {
            return false;
        }
        String lower = name.toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".mca") || lower.endsWith(".mcr");
    }

    /**
     * Validates the region file at {@code path}.
     *
     * @throws RegionStructureException if the structure is invalid
     * @throws StorageException on IO failure
     */
    public static void validate(Path path) {
        Objects.requireNonNull(path, "path");
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new StorageException("could not read region file " + path, e);
        }
        validate(bytes, path.toString());
    }

    /**
     * Validates region bytes already in memory (shared with the hash pass).
     *
     * @param bytes complete file contents
     * @param label path or other identity for error messages
     * @throws RegionStructureException if the structure is invalid
     */
    public static void validate(byte[] bytes, String label) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(label, "label");
        if (bytes.length < HEADER_BYTES) {
            throw new RegionStructureException(label + ": shorter than 8 KiB header (" + bytes.length + " bytes)");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        // Paper / modern Anvil writers often leave the final sector unpadded, so
        // the file length is not a multiple of 4096. Location entries still claim
        // whole sectors; the last claimed sector may only be partially present on
        // disk. Use a ceiling sector count for allocation bounds and the exact
        // byte length for payload bounds below.
        int sectorCeiling = (bytes.length + SECTOR_BYTES - 1) / SECTOR_BYTES;

        // occupied[s] = chunk index + 1 that owns sector s, or 0 if free.
        // Header sectors 0 and 1 are reserved.
        int[] occupied = new int[Math.max(sectorCeiling, 2)];
        occupied[0] = -1;
        occupied[1] = -1;

        for (int chunk = 0; chunk < CHUNK_COUNT; chunk++) {
            int entryOffset = chunk * 4;
            int b0 = bytes[entryOffset] & 0xFF;
            int b1 = bytes[entryOffset + 1] & 0xFF;
            int b2 = bytes[entryOffset + 2] & 0xFF;
            int sectors = bytes[entryOffset + 3] & 0xFF;
            int sectorOffset = (b0 << 16) | (b1 << 8) | b2;

            if (sectorOffset == 0 && sectors == 0) {
                continue;
            }
            if (sectorOffset == 0 || sectors == 0) {
                throw new RegionStructureException(label + ": chunk " + chunk
                        + " has inconsistent location entry offset=" + sectorOffset + " sectors=" + sectors);
            }
            if (sectorOffset < 2) {
                throw new RegionStructureException(
                        label + ": chunk " + chunk + " offset " + sectorOffset + " overlaps the 8 KiB header");
            }
            long endSector = (long) sectorOffset + (long) sectors;
            if (endSector > sectorCeiling) {
                throw new RegionStructureException(
                        label + ": chunk " + chunk + " sectors [" + sectorOffset + "," + endSector
                                + ") exceed file sector ceiling " + sectorCeiling
                                + " (" + bytes.length + " bytes)");
            }

            for (int s = sectorOffset; s < sectorOffset + sectors; s++) {
                if (occupied[s] != 0) {
                    throw new RegionStructureException(label + ": chunk " + chunk + " overlaps sector " + s
                            + " already claimed by chunk " + (occupied[s] - 1));
                }
                occupied[s] = chunk + 1;
            }

            int payloadStart = sectorOffset * SECTOR_BYTES;
            if (payloadStart + 5 > bytes.length) {
                throw new RegionStructureException(label + ": chunk " + chunk + " payload starts past end of file");
            }
            int length = buffer.getInt(payloadStart);
            if (length < 1) {
                throw new RegionStructureException(
                        label + ": chunk " + chunk + " declared length " + length + " is not positive");
            }
            // length counts the compression-type byte plus compressed payload.
            long payloadBytes = 4L + (length & 0xFFFFFFFFL);
            long sectorBudget = (long) sectors * (long) SECTOR_BYTES;
            if (payloadBytes > sectorBudget) {
                throw new RegionStructureException(label + ": chunk " + chunk + " declared length " + length
                        + " (+4-byte prefix = " + payloadBytes
                        + " bytes) exceeds sector budget " + sectorBudget);
            }
            if ((long) payloadStart + payloadBytes > bytes.length) {
                throw new RegionStructureException(label + ": chunk " + chunk + " payload runs past end of file (start="
                        + payloadStart + " length=" + length + " file=" + bytes.length + ")");
            }
            // compression type byte — accept any non-insane value; unknown schemes
            // are still structurally fine as long as length bounds hold.
            int compression = bytes[payloadStart + 4] & 0xFF;
            if (compression == 0) {
                throw new RegionStructureException(label + ": chunk " + chunk + " has compression type 0 (invalid)");
            }
        }
    }

    /**
     * Builds a minimal valid region file with a single chunk at index 0 for tests
     * and fixtures. Not used in production paths.
     *
     * @param chunkPayload compression-type byte followed by payload bytes; must
     *     be non-empty
     */
    public static byte[] minimalValidRegion(byte[] chunkPayload) {
        Objects.requireNonNull(chunkPayload, "chunkPayload");
        if (chunkPayload.length < 1) {
            throw new IllegalArgumentException("chunkPayload must include a compression type byte");
        }
        int lengthField = chunkPayload.length; // compression + data
        int totalPayload = 4 + lengthField;
        int sectors = (totalPayload + SECTOR_BYTES - 1) / SECTOR_BYTES;
        if (sectors > 255) {
            throw new IllegalArgumentException("chunkPayload too large for one location entry");
        }
        int fileSectors = 2 + sectors;
        byte[] bytes = new byte[fileSectors * SECTOR_BYTES];
        // location entry 0: offset=2, sector count
        bytes[0] = 0;
        bytes[1] = 0;
        bytes[2] = 2;
        bytes[3] = (byte) sectors;
        // timestamp entry 0 — arbitrary non-zero so the timestamp half is touched
        bytes[4096] = 0x01;
        bytes[4097] = 0x02;
        bytes[4098] = 0x03;
        bytes[4099] = 0x04;
        int payloadStart = 2 * SECTOR_BYTES;
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(payloadStart, lengthField);
        System.arraycopy(chunkPayload, 0, bytes, payloadStart + 4, chunkPayload.length);
        return bytes;
    }

    /** Defensive copy helper for tests that mutate a valid baseline. */
    public static byte[] copyOf(byte[] region) {
        return Arrays.copyOf(region, region.length);
    }
}
