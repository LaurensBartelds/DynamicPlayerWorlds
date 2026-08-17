package nl.gzmn.playerworlds.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ManifestCodecTest {

    @Test
    @DisplayName("roundTripsManifestSuccessfully preserves all manifest metadata and entries")
    void roundTripsManifestSuccessfully() {
        WorldId worldId = WorldId.random();
        Instant now = Instant.ofEpochMilli(1755465320000L);
        ManifestEntry e1 = new ManifestEntry(
                "pw_test/level.dat",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                1240L,
                1755465320000L);
        ManifestEntry e2 = new ManifestEntry(
                "pw_test/region/r.0.0.mca",
                "4b227777d4dd1fc61c6f884f48641d02b4d121d3fd328cb08b5531fcacdabf8a",
                10485760L,
                1755465330000L);
        Manifest manifest = new Manifest(worldId, 0L, 1, 4903, "26.2", now, Map.of(e1.path(), e1, e2.path(), e2));

        String json = ManifestCodec.encode(manifest);
        Manifest decoded = ManifestCodec.decode(json);

        assertThat(decoded).isEqualTo(manifest);
        assertThat(decoded.manifestKey()).isEqualTo("worlds/" + worldId.value() + "/manifest/0-1.json");
    }

    @Test
    @DisplayName("sortsEntriesDeterministically ensures dictionary ordering of entry keys in JSON")
    void sortsEntriesDeterministically() {
        WorldId worldId = WorldId.random();
        ManifestEntry e1 = new ManifestEntry("b.dat", "0".repeat(64), 10L, 100L);
        ManifestEntry e2 = new ManifestEntry("a.dat", "1".repeat(64), 20L, 200L);
        Manifest manifest =
                new Manifest(worldId, 0L, 1, 4903, "26.2", Instant.EPOCH, Map.of(e1.path(), e1, e2.path(), e2));

        String json = ManifestCodec.encode(manifest);
        int aIdx = json.indexOf("\"a.dat\"");
        int bIdx = json.indexOf("\"b.dat\"");
        assertThat(aIdx).isLessThan(bIdx);
    }

    @Test
    @DisplayName("roundTripsEmptyEntries handles manifests without files")
    void roundTripsEmptyEntries() {
        WorldId worldId = WorldId.random();
        Manifest manifest = new Manifest(worldId, 1L, 0, 4903, "26.2", Instant.parse("2026-08-17T12:00:00Z"), Map.of());

        String json = ManifestCodec.encode(manifest);
        Manifest decoded = ManifestCodec.decode(json);

        assertThat(decoded).isEqualTo(manifest);
        assertThat(decoded.entries()).isEmpty();
    }

    @Test
    @DisplayName("roundTripsSpecialCharactersInPaths handles escaped characters in file paths")
    void roundTripsSpecialCharactersInPaths() {
        WorldId worldId = WorldId.random();
        ManifestEntry entry = new ManifestEntry(
                "pw_test/path with \"quotes\" and \\slashes and \t tabs/file.dat", "a".repeat(64), 500L, 1000L);
        Manifest manifest = new Manifest(worldId, 2L, 5, 4903, "26.2", Instant.EPOCH, Map.of(entry.path(), entry));

        String json = ManifestCodec.encode(manifest);
        Manifest decoded = ManifestCodec.decode(json);

        assertThat(decoded).isEqualTo(manifest);
        assertThat(decoded.entries()).containsKey(entry.path());
    }

    @Test
    @DisplayName("rejectsInvalidJson throws StorageException on malformed JSON")
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> ManifestCodec.decode("{invalid-json")).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> ManifestCodec.decode("")).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> ManifestCodec.decode("null")).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> ManifestCodec.decode("{\"worldId\": \"not-a-uuid\"}"))
                .isInstanceOf(StorageException.class);
    }

    @Test
    @DisplayName("rejectsMissingRequiredFields throws StorageException when fields are missing")
    void rejectsMissingRequiredFields() {
        String missingSequence = """
                {
                  "worldId": "00000000-0000-0000-0000-000000000001",
                  "generation": 0,
                  "dataVersion": 4903,
                  "mcVersion": "26.2",
                  "createdAt": "2026-08-17T12:00:00Z",
                  "entries": {}
                }
                """;
        assertThatThrownBy(() -> ManifestCodec.decode(missingSequence)).isInstanceOf(StorageException.class);
    }

    @Test
    @DisplayName("rejectsCorruptedEntryFields throws StorageException when entry contains invalid fields")
    void rejectsCorruptedEntryFields() {
        String invalidHash = """
                {
                  "worldId": "00000000-0000-0000-0000-000000000001",
                  "generation": 0,
                  "sequence": 1,
                  "dataVersion": 4903,
                  "mcVersion": "26.2",
                  "createdAt": "2026-08-17T12:00:00Z",
                  "entries": {
                    "test.dat": {
                      "sha256": "INVALID_HASH_NOT_64_CHARS",
                      "sizeBytes": 100,
                      "lastModifiedMillis": 100
                    }
                  }
                }
                """;
        assertThatThrownBy(() -> ManifestCodec.decode(invalidHash)).isInstanceOf(StorageException.class);

        String negativeSize = """
                {
                  "worldId": "00000000-0000-0000-0000-000000000001",
                  "generation": 0,
                  "sequence": 1,
                  "dataVersion": 4903,
                  "mcVersion": "26.2",
                  "createdAt": "2026-08-17T12:00:00Z",
                  "entries": {
                    "test.dat": {
                      "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                      "sizeBytes": -10,
                      "lastModifiedMillis": 100
                    }
                  }
                }
                """;
        assertThatThrownBy(() -> ManifestCodec.decode(negativeSize)).isInstanceOf(StorageException.class);
    }

    @Test
    @DisplayName("rejectsNullArguments throws NullPointerException")
    void rejectsNullArguments() {
        assertThatThrownBy(() -> ManifestCodec.encode(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ManifestCodec.decode(null)).isInstanceOf(NullPointerException.class);
    }
}
