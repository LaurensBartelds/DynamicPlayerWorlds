package nl.gzmn.playerworlds.core.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import nl.gzmn.playerworlds.core.db.DatabaseSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("specification defaults are valid together with a normal node config")
    void specificationDefaultsAreValid() throws Exception {
        NodeConfig node = sampleNode(Duration.ofSeconds(30), 0);
        assertThatCode(() -> ConfigValidator.validate(node, NetworkPolicy.defaults()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("dead-after must stay strictly below the lease (MN-18)")
    void deadAfterMustStayStrictlyBelowTheLease() {
        NetworkPolicy policy = policyWith(Map.of(
                NetworkPolicy.KEY_DEAD_AFTER_SECONDS, "180",
                NetworkPolicy.KEY_LEASE_SECONDS, "180"));

        assertThatThrownBy(() -> ConfigValidator.validatePolicy(policy))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("MN-18");
    }

    @Test
    @DisplayName("three heartbeats must fit inside the lease (MN-9)")
    void threeHeartbeatsMustFitInsideTheLease() throws Exception {
        NodeConfig node = sampleNode(Duration.ofSeconds(90), 0);
        NetworkPolicy policy = NetworkPolicy.defaults(); // lease 180s

        assertThatThrownBy(() -> ConfigValidator.validate(node, policy))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("MN-9");
    }

    @Test
    @DisplayName("fence margin below one heartbeat interval is refused")
    void fenceMarginBelowOneHeartbeatIsRefused() throws Exception {
        // heartbeat 30s, margin 10s — fences on a single missed beat
        NodeConfig node = sampleNode(Duration.ofSeconds(30), 0);
        NetworkPolicy policy = policyWith(Map.of(NetworkPolicy.KEY_FENCE_SAFETY_MARGIN_SECONDS, "10"));

        assertThatThrownBy(() -> ConfigValidator.validate(node, policy))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(NetworkPolicy.KEY_FENCE_SAFETY_MARGIN_SECONDS);
    }

    @Test
    @DisplayName("fence margin at or above the lease is refused")
    void fenceMarginAtOrAboveTheLeaseIsRefused() {
        NetworkPolicy policy = policyWith(Map.of(NetworkPolicy.KEY_FENCE_SAFETY_MARGIN_SECONDS, "180"));

        assertThatThrownBy(() -> ConfigValidator.validatePolicy(policy))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(NetworkPolicy.KEY_FENCE_SAFETY_MARGIN_SECONDS);
    }

    @Test
    @DisplayName("quiesce timeout must leave room inside the commit budget")
    void quiesceTimeoutMustLeaveRoomInsideTheCommitBudget() {
        NetworkPolicy policy = policyWith(Map.of(
                NetworkPolicy.KEY_SNAPSHOT_QUIESCE_TIMEOUT_MS, "15000",
                NetworkPolicy.KEY_COMMIT_TIMEOUT_SECONDS, "15"));

        assertThatThrownBy(() -> ConfigValidator.validatePolicy(policy))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(NetworkPolicy.KEY_SNAPSHOT_QUIESCE_TIMEOUT_MS);
    }

    @Test
    @DisplayName("commit timeout must stay strictly below the holding timeout")
    void commitTimeoutMustStayBelowHoldingTimeout() {
        NetworkPolicy policy = policyWith(Map.of(
                NetworkPolicy.KEY_COMMIT_TIMEOUT_SECONDS, "30",
                NetworkPolicy.KEY_HOLDING_TIMEOUT_SECONDS, "30"));

        assertThatThrownBy(() -> ConfigValidator.validatePolicy(policy))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(NetworkPolicy.KEY_COMMIT_TIMEOUT_SECONDS);
    }

    @Test
    @DisplayName("a non-writable scratch path refuses enable")
    void nonWritableScratchPathRefusesEnable() throws Exception {
        Path file = tempDir.resolve("not-a-dir");
        Files.writeString(file, "x");
        NodeConfig node = new NodeConfig(
                "node-a",
                "127.0.0.1:25565",
                Duration.ofSeconds(30),
                DatabaseSettings.of("jdbc:postgresql://localhost/db", "u", "p"),
                StorageClientSettings.of(URI.create("http://127.0.0.1:9000"), "k", "s", "bucket"),
                file,
                tempDir.resolve("cache"),
                tempDir.resolve("quarantine"),
                0);

        assertThatThrownBy(() -> ConfigValidator.validate(node, NetworkPolicy.defaults()))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("storage.local-scratch-path");
    }

    private static NetworkPolicy policyWith(Map<String, String> overrides) {
        Map<String, String> raw = new HashMap<>();
        raw.putAll(overrides);
        return NetworkPolicy.fromRaw(raw);
    }

    private NodeConfig sampleNode(Duration heartbeat, long minFree) throws Exception {
        Path scratch = tempDir.resolve("scratch");
        Path cache = tempDir.resolve("cache");
        Path quarantine = tempDir.resolve("quarantine");
        Files.createDirectories(scratch);
        Files.createDirectories(cache);
        Files.createDirectories(quarantine);
        return new NodeConfig(
                "node-a",
                "127.0.0.1:25565",
                heartbeat,
                DatabaseSettings.of("jdbc:postgresql://localhost/db", "u", "p"),
                StorageClientSettings.of(URI.create("http://127.0.0.1:9000"), "k", "s", "bucket"),
                scratch,
                cache,
                quarantine,
                minFree);
    }
}
