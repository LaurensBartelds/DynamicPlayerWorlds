package nl.gzmn.playerworlds.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NetworkPolicyTest {

    @Test
    @DisplayName("defaults match specification sections 7 and 12.8")
    void defaultsMatchTheSpecification() {
        NetworkPolicy policy = NetworkPolicy.defaults();

        assertThat(policy.maxWorldsPerPlayer()).isEqualTo(2);
        assertThat(policy.leaseDuration()).isEqualTo(Duration.ofMinutes(3));
        assertThat(policy.deadAfter()).isEqualTo(Duration.ofSeconds(60));
        assertThat(policy.fenceSafetyMargin()).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.manifestRetentionCount()).isEqualTo(3);
        assertThat(policy.commitTimeout()).isEqualTo(Duration.ofSeconds(15));
        // 90, not the specification's 30: the holding timeout is the outer budget
        // of the join path and NFR-1's 60s cold load has to fit inside it.
        assertThat(policy.holdingTimeout()).isEqualTo(Duration.ofSeconds(90));
        assertThat(policy.coldLoadBudget()).isEqualTo(Duration.ofSeconds(60));
        assertThat(policy.snapshotQuiesceTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.archiveWarnDays()).containsExactly(14, 3);
        assertThat(policy.excludeGlobs()).containsExactly("session.lock", "uid.dat");
        assertThat(policy.defaultVisibility()).isEqualTo("PRIVATE");
        assertThat(policy.defaultStorageLimitBytes()).isEqualTo(5L * 1024 * 1024 * 1024);
    }

    @Test
    @DisplayName("fromRaw overlays stored values and keeps defaults for the rest")
    void fromRawOverlaysStoredValues() {
        NetworkPolicy policy = NetworkPolicy.fromRaw(Map.of(
                NetworkPolicy.KEY_MAX_WORLDS_PER_PLAYER, "5",
                NetworkPolicy.KEY_DEFAULT_VISIBILITY, "\"PUBLIC\"",
                NetworkPolicy.KEY_ARCHIVE_WARN_DAYS, "[7, 1]",
                NetworkPolicy.KEY_ALLOWED_COMMANDS, "[\"spawn\", \"home\"]",
                NetworkPolicy.KEY_VERIFY_REGION_STRUCTURE, "false",
                NetworkPolicy.KEY_DEFAULT_STORAGE_LIMIT_GB, "10"));

        assertThat(policy.maxWorldsPerPlayer()).isEqualTo(5);
        assertThat(policy.defaultVisibility()).isEqualTo("PUBLIC");
        assertThat(policy.archiveWarnDays()).containsExactly(7, 1);
        assertThat(policy.allowedCommands()).containsExactly("spawn", "home");
        assertThat(policy.verifyRegionStructure()).isFalse();
        assertThat(policy.defaultStorageLimitBytes()).isEqualTo(10L * 1024 * 1024 * 1024);
        // Untouched keys keep defaults.
        assertThat(policy.leaseDuration()).isEqualTo(Duration.ofMinutes(3));
        assertThat(policy.manifestRetentionCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("the rejected profiles.retain-snapshots key refuses rather than splits retention")
    void rejectedProfilesRetainSnapshotsKeyIsRefused() {
        assertThatThrownBy(() -> NetworkPolicy.fromRaw(Map.of(NetworkPolicy.REJECTED_PROFILES_RETAIN_SNAPSHOTS, "5")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(NetworkPolicy.KEY_MANIFEST_RETENTION)
                .hasMessageContaining(NetworkPolicy.REJECTED_PROFILES_RETAIN_SNAPSHOTS);
    }

    @Test
    @DisplayName("a non-integer scalar is refused with the key name")
    void nonIntegerScalarIsRefused() {
        assertThatThrownBy(() -> NetworkPolicy.fromRaw(Map.of(NetworkPolicy.KEY_MAX_WORLDS_PER_PLAYER, "\"two\"")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(NetworkPolicy.KEY_MAX_WORLDS_PER_PLAYER);
    }
}
