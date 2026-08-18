package nl.gzmn.playerworlds.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.StorageQuota;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StorageQuotaResolverTest {

    @Test
    @DisplayName("parses numeric storage permissions with various units and case insensitivity")
    void parsesNumericStoragePermissions() {
        assertThat(StorageQuotaResolver.parsePermissionLimit("gzmn.worlds.storage.10gb"))
                .isEqualTo(10L * 1024 * 1024 * 1024);
        assertThat(StorageQuotaResolver.parsePermissionLimit("GZMN.WORLDS.STORAGE.10GB"))
                .isEqualTo(10L * 1024 * 1024 * 1024);
        assertThat(StorageQuotaResolver.parsePermissionLimit("gzmn.worlds.storage.5000mb"))
                .isEqualTo(5000L * 1024 * 1024);
        assertThat(StorageQuotaResolver.parsePermissionLimit("gzmn.worlds.storage.500kb"))
                .isEqualTo(500L * 1024);
        assertThat(StorageQuotaResolver.parsePermissionLimit("gzmn.worlds.storage.1tb"))
                .isEqualTo(1024L * 1024 * 1024 * 1024);
        assertThat(StorageQuotaResolver.parsePermissionLimit("gzmn.worlds.storage.500b"))
                .isEqualTo(500L);
        // Unspecified unit defaults to MB
        assertThat(StorageQuotaResolver.parsePermissionLimit("gzmn.worlds.storage.500"))
                .isEqualTo(500L * 1024 * 1024);
    }

    @Test
    @DisplayName("returns -1 for invalid or non-numeric permissions")
    void returnsNegativeForInvalidPermissions() {
        assertThat(StorageQuotaResolver.parsePermissionLimit("invalid.permission"))
                .isEqualTo(-1L);
        assertThat(StorageQuotaResolver.parsePermissionLimit("gzmn.worlds.storage.unlimited"))
                .isEqualTo(-1L);
        assertThat(StorageQuotaResolver.parsePermissionLimit("gzmn.worlds.storage.abc"))
                .isEqualTo(-1L);
        assertThat(StorageQuotaResolver.parsePermissionLimit("gzmn.worlds.storage."))
                .isEqualTo(-1L);
        assertThat(StorageQuotaResolver.parsePermissionLimit("gzmn.worlds.storage.-5gb"))
                .isEqualTo(-1L);
        assertThat(StorageQuotaResolver.parsePermissionLimit("")).isEqualTo(-1L);
        assertThat(StorageQuotaResolver.parsePermissionLimit(null)).isEqualTo(-1L);
    }

    @Test
    @DisplayName("selects highest permission limit among multiple granted permissions")
    void selectsHighestPermissionLimit() {
        List<String> perms = List.of("gzmn.worlds.storage.5gb", "gzmn.worlds.storage.20gb", "gzmn.worlds.storage.10gb");
        long resolved = StorageQuotaResolver.resolveLimitBytes(perms, false, 5L * 1024 * 1024 * 1024);
        assertThat(resolved).isEqualTo(20L * 1024 * 1024 * 1024);
    }

    @Test
    @DisplayName("falls back to default limit when no storage permission is granted")
    void fallsBackToDefaultLimit() {
        long defaultBytes = 5L * 1024 * 1024 * 1024;
        assertThat(StorageQuotaResolver.resolveLimitBytes(List.of(), false, defaultBytes))
                .isEqualTo(defaultBytes);
        assertThat(StorageQuotaResolver.resolveLimitBytes(List.of("gzmn.worlds.other"), false, defaultBytes))
                .isEqualTo(defaultBytes);
        assertThat(StorageQuotaResolver.resolveLimitBytes(null, false, defaultBytes))
                .isEqualTo(defaultBytes);
    }

    @Test
    @DisplayName("admin and unlimited permission players are exempt from quotas")
    void adminAndUnlimitedAreExempt() {
        StorageQuota quotaAdmin = StorageQuotaResolver.evaluate(UUID.randomUUID(), 1000L, List.of(), true, 5000L);
        assertThat(quotaAdmin.unlimited()).isTrue();
        assertThat(quotaAdmin.isExceeded()).isFalse();
        assertThat(quotaAdmin.percentage()).isEqualTo(0.0);

        StorageQuota quotaPerm = StorageQuotaResolver.evaluate(
                UUID.randomUUID(), 1000L, List.of("gzmn.worlds.storage.unlimited"), false, 5000L);
        assertThat(quotaPerm.unlimited()).isTrue();
        assertThat(quotaPerm.isExceeded()).isFalse();
        assertThat(quotaPerm.percentage()).isEqualTo(0.0);

        StorageQuota quotaAdminPerm =
                StorageQuotaResolver.evaluate(UUID.randomUUID(), 1000L, List.of("gzmn.worlds.admin"), false, 5000L);
        assertThat(quotaAdminPerm.unlimited()).isTrue();
        assertThat(quotaAdminPerm.isExceeded()).isFalse();
    }

    @Test
    @DisplayName("evaluates normal player quota correctly")
    void evaluatesNormalQuota() {
        UUID player = UUID.randomUUID();
        long limit = 10L * 1024 * 1024 * 1024; // 10 GB
        long used = 2560L * 1024 * 1024; // 2.5 GB

        StorageQuota quota = StorageQuotaResolver.evaluate(
                player, used, List.of("gzmn.worlds.storage.10gb"), false, 5L * 1024 * 1024 * 1024);

        assertThat(quota.playerUuid()).isEqualTo(player);
        assertThat(quota.usedBytes()).isEqualTo(used);
        assertThat(quota.limitBytes()).isEqualTo(limit);
        assertThat(quota.unlimited()).isFalse();
        assertThat(quota.isExceeded()).isFalse();
        assertThat(quota.percentage()).isEqualTo(25.0);
    }

    @Test
    @DisplayName("detects quota exceeded when used exceeds or equals limit")
    void detectsQuotaExceeded() {
        UUID player = UUID.randomUUID();
        long limit = 10L * 1024 * 1024 * 1024;

        StorageQuota quotaExact = StorageQuotaResolver.evaluate(
                player, limit, List.of("gzmn.worlds.storage.10gb"), false, 5L * 1024 * 1024 * 1024);
        assertThat(quotaExact.isExceeded()).isTrue();
        assertThat(quotaExact.percentage()).isEqualTo(100.0);

        StorageQuota quotaOver = StorageQuotaResolver.evaluate(
                player, limit + 1024L, List.of("gzmn.worlds.storage.10gb"), false, 5L * 1024 * 1024 * 1024);
        assertThat(quotaOver.isExceeded()).isTrue();
        assertThat(quotaOver.percentage()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("formats bytes into human readable representations")
    void formatsHumanReadableBytes() {
        assertThat(StorageQuotaResolver.formatBytes(0L)).isEqualTo("0 B");
        assertThat(StorageQuotaResolver.formatBytes(500L)).isEqualTo("500 B");
        assertThat(StorageQuotaResolver.formatBytes(1024L)).isEqualTo("1.00 KB");
        assertThat(StorageQuotaResolver.formatBytes(524288000L)).isEqualTo("500.00 MB");
        assertThat(StorageQuotaResolver.formatBytes(1073741824L)).isEqualTo("1.00 GB");
        assertThat(StorageQuotaResolver.formatBytes(1342177280L)).isEqualTo("1.25 GB");
        assertThat(StorageQuotaResolver.formatBytes(1099511627776L)).isEqualTo("1.00 TB");
    }

    @Test
    @DisplayName("evaluates throws on null UUID")
    void evaluatesThrowsOnNullUuid() {
        assertThatThrownBy(() -> StorageQuotaResolver.evaluate(null, 0L, List.of(), false, 5000L))
                .isInstanceOf(NullPointerException.class);
    }
}
