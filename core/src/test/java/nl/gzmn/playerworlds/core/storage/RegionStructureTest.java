package nl.gzmn.playerworlds.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionStructureTest {

    /** zlib compression type + four payload bytes. */
    private static final byte[] CHUNK_PAYLOAD = new byte[] {2, 0x10, 0x20, 0x30, 0x40};

    @TempDir
    Path temp;

    @Test
    @DisplayName("minimal synthetic region is accepted")
    void minimalValidIsAccepted() {
        byte[] region = RegionStructure.minimalValidRegion(CHUNK_PAYLOAD);
        assertThatCode(() -> RegionStructure.validate(region, "minimal")).doesNotThrowAnyException();
        assertThat(region.length).isEqualTo(3 * RegionStructure.SECTOR_BYTES);
    }

    @Test
    @DisplayName("empty header-only region (no chunks) is accepted")
    void emptyRegionAccepted() {
        byte[] empty = new byte[RegionStructure.HEADER_BYTES];
        assertThatCode(() -> RegionStructure.validate(empty, "empty")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("file shorter than 8 KiB header is rejected")
    void shortFileRejected() {
        byte[] shortFile = new byte[100];
        assertThatThrownBy(() -> RegionStructure.validate(shortFile, "short"))
                .isInstanceOf(RegionStructureException.class)
                .hasMessageContaining("8 KiB");
    }

    @Test
    @DisplayName("overlapping sector allocations are rejected")
    void overlappingSectorsRejected() {
        byte[] region = RegionStructure.minimalValidRegion(CHUNK_PAYLOAD);
        // chunk 1 claims the same sector as chunk 0 (offset=2, sectors=1)
        region[4] = 0;
        region[5] = 0;
        region[6] = 2;
        region[7] = 1;
        assertThatThrownBy(() -> RegionStructure.validate(region, "overlap"))
                .isInstanceOf(RegionStructureException.class)
                .hasMessageContaining("overlap");
    }

    @Test
    @DisplayName("chunk length exceeding its sector budget is rejected")
    void lengthExceedsSectorBudget() {
        byte[] region = RegionStructure.minimalValidRegion(CHUNK_PAYLOAD);
        int payloadStart = 2 * RegionStructure.SECTOR_BYTES;
        // sectors=1 → budget 4096; claim length that needs more than that
        ByteBuffer.wrap(region).order(ByteOrder.BIG_ENDIAN).putInt(payloadStart, 4096);
        // compression type still at payloadStart+4
        region[payloadStart + 4] = 2;
        assertThatThrownBy(() -> RegionStructure.validate(region, "long-length"))
                .isInstanceOf(RegionStructureException.class)
                .hasMessageContaining("sector budget");
    }

    @Test
    @DisplayName("offset inside the 8 KiB header is rejected")
    void headerOverlapRejected() {
        byte[] region = new byte[RegionStructure.HEADER_BYTES + RegionStructure.SECTOR_BYTES];
        // offset=1 (inside timestamps), sectors=1
        region[0] = 0;
        region[1] = 0;
        region[2] = 1;
        region[3] = 1;
        assertThatThrownBy(() -> RegionStructure.validate(region, "hdr"))
                .isInstanceOf(RegionStructureException.class)
                .hasMessageContaining("header");
    }

    @Test
    @DisplayName("inconsistent location entry (offset without sectors) is rejected")
    void inconsistentEntryRejected() {
        byte[] region = new byte[RegionStructure.HEADER_BYTES];
        region[0] = 0;
        region[1] = 0;
        region[2] = 2;
        region[3] = 0; // sectors=0 with non-zero offset
        assertThatThrownBy(() -> RegionStructure.validate(region, "inconsistent"))
                .isInstanceOf(RegionStructureException.class)
                .hasMessageContaining("inconsistent");
    }

    @Test
    @DisplayName("every single-byte corruption of the location table is rejected")
    void everyLocationTableByteCorruptionIsRejected() {
        byte[] baseline = RegionStructure.minimalValidRegion(CHUNK_PAYLOAD);
        RegionStructure.validate(baseline, "baseline");

        List<Integer> acceptedCorruptions = new ArrayList<>();
        // Location table only — timestamps are free-form and not structurally
        // constrained (MN-5c lists offsets, sector counts, overlaps and lengths).
        for (int i = 0; i < RegionStructure.SECTOR_BYTES; i++) {
            byte[] corrupted = RegionStructure.copyOf(baseline);
            corrupted[i] = (byte) (corrupted[i] ^ 0xFF);
            try {
                RegionStructure.validate(corrupted, "corrupt@" + i);
                acceptedCorruptions.add(i);
            } catch (RegionStructureException expected) {
                // required outcome
            }
        }

        assertThat(acceptedCorruptions)
                .as("single-byte XOR 0xFF corruptions of the location table that were still accepted")
                .isEmpty();
    }

    @Test
    @DisplayName("path-based validate rejects a corrupted on-disk region")
    void pathValidateRejectsCorruptFile() throws Exception {
        byte[] baseline = RegionStructure.minimalValidRegion(CHUNK_PAYLOAD);
        baseline[2] ^= 0x01; // bump offset from 2 toward garbage
        Path file = temp.resolve("r.0.0.mca");
        Files.write(file, baseline);
        assertThatThrownBy(() -> RegionStructure.validate(file)).isInstanceOf(RegionStructureException.class);
    }

    @Test
    @DisplayName("isRegionFileName recognises .mca and .mcr only")
    void regionFileName() {
        assertThat(RegionStructure.isRegionFileName(Path.of("r.0.0.mca"))).isTrue();
        assertThat(RegionStructure.isRegionFileName(Path.of("R.1.1.MCA"))).isTrue();
        assertThat(RegionStructure.isRegionFileName(Path.of("r.0.0.mcr"))).isTrue();
        assertThat(RegionStructure.isRegionFileName(Path.of("level.dat"))).isFalse();
        assertThat(RegionStructure.isRegionFileName(Path.of("region"))).isFalse();
    }
}
