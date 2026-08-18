package nl.gzmn.playerworlds.proxy.permission;

import com.velocitypowered.api.proxy.Player;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The LuckPerms half of {@link StorageTiers}, isolated so the API can be absent at runtime.
 *
 * <p>Every reference to a {@code net.luckperms} type lives in this class and nowhere else.
 * {@link StorageTiers} reaches it only after {@code Class.forName} has confirmed the API is
 * loaded, so on a proxy without LuckPerms this class is never initialised and its missing
 * types are never resolved.
 */
final class LuckPermsTiers {

    private static final Logger log = LoggerFactory.getLogger(LuckPermsTiers.class);

    private LuckPermsTiers() {}

    /**
     * Whether the LuckPerms service is actually registered, not merely on the classpath.
     *
     * <p>{@code LuckPermsProvider.get()} throws until the plugin has loaded, and a proxy is free
     * to start our plugin first.
     */
    static boolean usable() {
        try {
            var _ = LuckPermsProvider.get();
            return true;
        } catch (IllegalStateException | NoClassDefFoundError e) {
            return false;
        }
    }

    /**
     * Every permission the player resolves to, inherited nodes included.
     *
     * @return the held permissions, or empty when LuckPerms could not answer for this player
     */
    static Optional<Collection<String>> heldPermissions(Player player) {
        try {
            LuckPerms luckPerms = LuckPermsProvider.get();
            Map<String, Boolean> resolved = luckPerms
                    .getPlayerAdapter(Player.class)
                    .getPermissionData(player)
                    .getPermissionMap();
            // A node mapped to false is an explicit denial and is not held.
            List<String> granted = resolved.entrySet().stream()
                    .filter(Map.Entry::getValue)
                    .map(Map.Entry::getKey)
                    .toList();
            return Optional.of(granted);
        } catch (RuntimeException e) {
            log.warn(
                    "LuckPerms could not resolve permissions for {}; falling back to probing the"
                            + " configured storage tiers",
                    player.getUsername(),
                    e);
            return Optional.empty();
        }
    }
}
