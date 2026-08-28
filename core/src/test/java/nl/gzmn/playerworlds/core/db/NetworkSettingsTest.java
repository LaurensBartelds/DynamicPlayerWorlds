package nl.gzmn.playerworlds.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import nl.gzmn.playerworlds.core.config.ConfigException;
import nl.gzmn.playerworlds.core.config.MessageCatalog;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Cache, invalidation and typed policy loading against a real PostgreSQL. */
class NetworkSettingsTest {

    private Database database;
    private NetworkSettings settings;

    @BeforeEach
    void openDatabase() throws Exception {
        database = TestPostgres.freshDatabase();
        Schema.migrate(database);
        settings = new NetworkSettings(database);
    }

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("an empty table yields specification defaults")
    void emptyTableYieldsDefaults() throws Exception {
        NetworkPolicy policy = settings.policy();

        assertThat(policy.maxWorldsPerPlayer()).isEqualTo(NetworkPolicy.DEFAULT_MAX_WORLDS_PER_PLAYER);
        assertThat(policy.manifestRetentionCount()).isEqualTo(NetworkPolicy.DEFAULT_MANIFEST_RETENTION);
        assertThat(policy.leaseDuration()).isEqualTo(NetworkPolicy.DEFAULT_LEASE_DURATION);
    }

    @Test
    @DisplayName("putAndReload surfaces the new value through policy()")
    void putAndReloadSurfacesTheNewValue() throws Exception {
        settings.putAndReload(NetworkPolicy.KEY_MAX_WORLDS_PER_PLAYER, "7", "staff");

        assertThat(settings.policy().maxWorldsPerPlayer()).isEqualTo(7);
        assertThat(settings.get(NetworkPolicy.KEY_MAX_WORLDS_PER_PLAYER)).contains("7");
    }

    @Test
    @DisplayName("invalidate drops the cache so the next read sees committed writes")
    void invalidateDropsTheCache() throws Exception {
        settings.putAndReload(NetworkPolicy.KEY_INVITE_EXPIRY_MINUTES, "42", "system");
        assertThat(settings.policy().inviteExpiry().toMinutes()).isEqualTo(42);

        // Write behind the cache, the way another node (or a raw SQL admin) would.
        database.inTransaction(connection -> {
            settings.put(connection, NetworkPolicy.KEY_INVITE_EXPIRY_MINUTES, "99", "other-node");
            return null;
        });
        // Cache still holds 42 until invalidated.
        assertThat(settings.policy().inviteExpiry().toMinutes()).isEqualTo(42);

        settings.invalidate();
        assertThat(settings.policy().inviteExpiry().toMinutes()).isEqualTo(99);
    }

    @Test
    @DisplayName("a rolled-back put never reaches the cache")
    void aRolledBackPutNeverReachesTheCache() throws Exception {
        assertThatThrownBy(() -> database.inTransaction(connection -> {
                    settings.put(connection, NetworkPolicy.KEY_BROWSE_PAGE_SIZE, "50", "staff");
                    throw new java.sql.SQLException("deliberate");
                }))
                .isInstanceOf(java.sql.SQLException.class);

        settings.reload();
        assertThat(settings.get(NetworkPolicy.KEY_BROWSE_PAGE_SIZE)).isEmpty();
        assertThat(settings.policy().browsePageSize()).isEqualTo(NetworkPolicy.DEFAULT_BROWSE_PAGE_SIZE);
    }

    @Test
    @DisplayName("arrays and strings round-trip through JSONB")
    void arraysAndStringsRoundTrip() throws Exception {
        settings.putAndReload(NetworkPolicy.KEY_ALLOWED_COMMANDS, "[\"spawn\",\"msg\"]", "system");
        settings.putAndReload(NetworkPolicy.KEY_DEFAULT_VISIBILITY, "\"PUBLIC\"", "system");
        settings.putAndReload(NetworkPolicy.KEY_ARCHIVE_WARN_DAYS, "[30, 7]", "system");

        NetworkPolicy policy = settings.policy();
        assertThat(policy.allowedCommands()).containsExactly("spawn", "msg");
        assertThat(policy.defaultVisibility()).isEqualTo("PUBLIC");
        assertThat(policy.archiveWarnDays()).containsExactly(30, 7);
    }

    @Test
    @DisplayName("the rejected profiles.retain-snapshots row refuses policy load")
    void rejectedRetentionAliasRefusesPolicyLoad() throws Exception {
        settings.putAndReload(NetworkPolicy.REJECTED_PROFILES_RETAIN_SNAPSHOTS, "3", "legacy");

        assertThatThrownBy(settings::policy)
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(NetworkPolicy.KEY_MANIFEST_RETENTION);
    }

    @Test
    @DisplayName("get returns empty for an unknown key")
    void getReturnsEmptyForUnknownKey() throws Exception {
        Optional<String> value = settings.get("does.not.exist");
        assertThat(value).isEmpty();
    }

    @Test
    @DisplayName("messages() surfaces an admin-written override the same way policy() does (NFR-5)")
    void messagesSurfacesTheNewValue() throws Exception {
        String key = "messages.notice.invite";
        settings.putAndReload(key, "\"<red>overridden</red>\"", "staff");

        MessageCatalog catalog = settings.messages();

        assertThat(catalog.get(key)).isEqualTo("<red>overridden</red>");
    }

    @Test
    @DisplayName("messages() and policy() share the same cache, invalidated together")
    void messagesAndPolicyShareTheCache() throws Exception {
        settings.putAndReload(NetworkPolicy.KEY_MAX_WORLDS_PER_PLAYER, "7", "staff");
        settings.putAndReload("messages.notice.invite", "\"<red>overridden</red>\"", "staff");

        assertThat(settings.policy().maxWorldsPerPlayer()).isEqualTo(7);
        assertThat(settings.messages().get("messages.notice.invite")).isEqualTo("<red>overridden</red>");

        database.inTransaction(connection -> {
            settings.put(connection, "messages.notice.invite", "\"<green>updated</green>\"", "other-node");
            return null;
        });
        // Cache still holds the old value until invalidated, same as policy().
        assertThat(settings.messages().get("messages.notice.invite")).isEqualTo("<red>overridden</red>");

        settings.invalidate();
        assertThat(settings.messages().get("messages.notice.invite")).isEqualTo("<green>updated</green>");
    }
}
