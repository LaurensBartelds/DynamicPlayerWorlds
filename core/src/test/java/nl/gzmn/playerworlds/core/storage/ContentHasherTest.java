package nl.gzmn.playerworlds.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContentHasherTest {

    private static final byte[] CHUNK_PAYLOAD = new byte[] {2, 1, 2, 3, 4};

    @TempDir
    Path temp;

    @Test
    @DisplayName("hashBytes matches MessageDigest SHA-256 and MD5")
    void hashMatchesJdk() throws Exception {
        byte[] data = "content-addressed".getBytes(StandardCharsets.UTF_8);
        HashedContent hashed = ContentHasher.hashBytes(data);

        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        String expectedSha256 = HexFormat.of().formatHex(sha256.digest(data));
        assertThat(hashed.sha256Hex()).isEqualTo(expectedSha256);
        assertThat(hashed.sizeBytes()).isEqualTo(data.length);
        assertThat(hashed.objectKeySuffix()).isEqualTo(expectedSha256);

        MessageDigest md5 = MessageDigest.getInstance("MD5");
        String expectedMd5 = java.util.Base64.getEncoder().encodeToString(md5.digest(data));
        assertThat(hashed.md5Base64()).isEqualTo(expectedMd5);
    }

    @Test
    @DisplayName("hashAndValidate accepts a valid region and returns its digest")
    void hashAndValidateValidRegion() throws Exception {
        byte[] region = RegionStructure.minimalValidRegion(CHUNK_PAYLOAD);
        Path file = temp.resolve("r.0.0.mca");
        Files.write(file, region);

        HashedContent hashed = ContentHasher.hashAndValidate(file, true);
        assertThat(hashed.sizeBytes()).isEqualTo(region.length);
        assertThat(hashed.sha256Hex()).isEqualTo(ContentHasher.hashBytes(region).sha256Hex());
    }

    @Test
    @DisplayName("hashAndValidate rejects a corrupt region before returning a hash")
    void hashAndValidateRejectsCorruptRegion() throws Exception {
        byte[] region = RegionStructure.minimalValidRegion(CHUNK_PAYLOAD);
        region[3] = 0; // sectors=0 with non-zero offset → inconsistent
        Path file = temp.resolve("r.0.0.mca");
        Files.write(file, region);

        assertThatThrownBy(() -> ContentHasher.hashAndValidate(file, true))
                .isInstanceOf(RegionStructureException.class);
    }

    @Test
    @DisplayName("verify kill-switch skips region checks")
    void killSwitchSkipsValidation() throws Exception {
        byte[] region = RegionStructure.minimalValidRegion(CHUNK_PAYLOAD);
        region[2] ^= 0x7F; // corrupt offset
        Path file = temp.resolve("r.0.0.mca");
        Files.write(file, region);

        // Off: hash still produced (operator accepted the risk).
        HashedContent hashed = ContentHasher.hashAndValidate(file, false);
        assertThat(hashed.sizeBytes()).isEqualTo(region.length);

        // On: refused.
        assertThatThrownBy(() -> ContentHasher.hashAndValidate(file, true))
                .isInstanceOf(RegionStructureException.class);
    }

    @Test
    @DisplayName("non-region files are hashed without structural checks")
    void nonRegionSkipped() throws Exception {
        Path file = temp.resolve("level.dat");
        Files.writeString(file, "not-a-region", StandardCharsets.UTF_8);
        HashedContent hashed = ContentHasher.hashAndValidate(file, true);
        assertThat(hashed.sizeBytes()).isEqualTo("not-a-region".length());
    }
}
