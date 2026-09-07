package nl.gzmn.playerworlds.proxy.permission;

import static org.assertj.core.api.Assertions.assertThat;

import com.velocitypowered.api.proxy.Player;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.model.StorageQuota;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The LuckPerms API is on this module's test classpath but no LuckPerms plugin is ever
 * registered, so {@code LuckPermsProvider.get()} throws. That is exactly the shape of a proxy
 * that has the API shaded in by some other plugin but no service behind it, and every test here
 * asserts the fallback survives it rather than propagating the failure.
 */
class StorageTiersTest {

    private static Player playerWith(UUID uuid, Predicate<String> permissions) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getUniqueId" -> uuid;
                        case "getUsername" -> "Tester";
                        case "hasPermission" -> permissions.test((String) args[0]);
                        case "toString" -> "MockPlayer";
                        default ->
                            method.getReturnType() == Optional.class
                                    ? Optional.empty()
                                    : (method.getReturnType().isPrimitive() ? false : null);
                    };
                });
    }

    /** Built through the real config path, so the new {@code storage.quota-tiers} key is exercised too. */
    private static NetworkPolicy withTiers(List<String> tiers, int defaultLimitGb) {
        String json = tiers.stream().map(tier -> '"' + tier + '"').collect(Collectors.joining(",", "[", "]"));
        return NetworkPolicy.fromRaw(Map.of(
                NetworkPolicy.KEY_STORAGE_QUOTA_TIERS,
                json,
                NetworkPolicy.KEY_DEFAULT_STORAGE_LIMIT_GB,
                Integer.toString(defaultLimitGb)));
    }

    @Test
    @DisplayName("Falls back to probing when no LuckPerms service is registered")
    void fallsBackToProbingWithoutLuckPerms() {
        UUID uuid = UUID.randomUUID();
        Player player = playerWith(uuid, "gzmn.worlds.storage.10gb"::equals);

        StorageTiers.Resolution resolved =
                new StorageTiers().evaluate(player, 0L, withTiers(List.of("1gb", "10gb"), 2));

        assertThat(resolved.source()).isEqualTo(StorageTiers.Source.PROBED);
        assertThat(resolved.quota().limitBytes()).isEqualTo(10L * 1024 * 1024 * 1024);
    }

    @Test
    @DisplayName("An operator's own tier list is what probing asks about")
    void honoursOperatorConfiguredTiers() {
        UUID uuid = UUID.randomUUID();
        Player player = playerWith(uuid, "gzmn.worlds.storage.3500mb"::equals);

        // Not in the shipped ladder, so it only resolves because the operator configured it.
        StorageTiers.Resolution configured =
                new StorageTiers().evaluate(player, 0L, withTiers(List.of("500mb", "3500mb"), 2));
        assertThat(configured.quota().limitBytes()).isEqualTo(3500L * 1024 * 1024);

        StorageTiers.Resolution unconfigured =
                new StorageTiers().evaluate(player, 0L, withTiers(List.of("500mb", "1gb"), 2));
        assertThat(unconfigured.quota().limitBytes()).isEqualTo(2L * 1024 * 1024 * 1024);
    }

    @Test
    @DisplayName("The unlimited exemption is honoured on the probing path")
    void honoursUnlimitedWhenProbing() {
        Player player = playerWith(UUID.randomUUID(), "gzmn.worlds.storage.unlimited"::equals);

        StorageQuota quota = new StorageTiers()
                .evaluate(player, 9_999_999L, withTiers(List.of("1gb"), 2))
                .quota();

        assertThat(quota.unlimited()).isTrue();
        assertThat(quota.isExceeded()).isFalse();
    }

    @Test
    @DisplayName("An empty tier list leaves everybody on the network default")
    void emptyTierListUsesDefault() {
        Player player = playerWith(UUID.randomUUID(), permission -> true);

        StorageQuota quota =
                new StorageTiers().evaluate(player, 0L, withTiers(List.of(), 2)).quota();

        // Every node answers true here, so the default is only reached because nothing was asked.
        assertThat(quota.limitBytes()).isEqualTo(2L * 1024 * 1024 * 1024);
    }
}
