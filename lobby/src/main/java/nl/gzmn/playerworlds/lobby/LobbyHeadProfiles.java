package nl.gzmn.playerworlds.lobby;

import com.destroystokyo.paper.profile.PlayerProfile;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Resolved player-head textures for the lobby's rendered menus.
 *
 * <p>The same problem, and the same answer, as the backend's {@code HeadProfiles}: a head
 * built from {@code Bukkit.getOfflinePlayer(uuid)} carries no skin unless this server has
 * seen that player, and the lobby has seen almost nobody whose head it draws — the world
 * owners and members in a menu are on other nodes, or offline entirely. Filling a profile
 * in is an HTTP call to Mojang and must not happen on the tick thread.
 *
 * <p>Deliberately a second copy rather than a shared class. {@code :core} is the only module
 * both plugins can share and it may not depend on Bukkit (CONTRIBUTING rule 2), and neither
 * plugin may depend on the other: they are separate deployables that need not be the same
 * version on a live network.
 */
public final class LobbyHeadProfiles {

    private static final Logger log = Logger.getLogger(LobbyHeadProfiles.class.getName());

    private final Plugin plugin;
    private final Map<UUID, PlayerProfile> resolved = new ConcurrentHashMap<>();

    /** UUIDs with no account behind them, so they are not looked up on every menu open. */
    private final Set<UUID> unresolvable = ConcurrentHashMap.newKeySet();

    public LobbyHeadProfiles(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * Whether every one of {@code uuids} is already decided, so a menu can be built now.
     *
     * @param uuids the head owners a screen is about to draw
     * @return true when nothing needs resolving, and the menu can open on this tick
     */
    public boolean allKnown(Collection<UUID> uuids) {
        Objects.requireNonNull(uuids, "uuids");
        return uuids.stream().allMatch(uuid -> resolved.containsKey(uuid) || unresolvable.contains(uuid));
    }

    /**
     * Resolves whatever of {@code uuids} is not yet known, off the main thread, then runs
     * {@code onMain} back on it.
     *
     * <p>{@code onMain} runs whether or not the lookups succeeded — a menu that cannot show
     * a skin still has to open.
     *
     * @param uuids the head owners a screen is about to draw
     * @param onMain what to do once they are resolved, run on the main thread
     */
    public void resolveThen(Collection<UUID> uuids, Runnable onMain) {
        Objects.requireNonNull(uuids, "uuids");
        Objects.requireNonNull(onMain, "onMain");

        List<UUID> missing = uuids.stream()
                .distinct()
                .filter(uuid -> !resolved.containsKey(uuid) && !unresolvable.contains(uuid))
                .toList();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            for (UUID uuid : missing) {
                resolve(uuid);
            }
            Bukkit.getScheduler().runTask(plugin, onMain);
        });
    }

    /**
     * The completed profile for a player, or {@code null} when none has been resolved.
     *
     * @param uuid the player, or null
     * @return a profile carrying textures, or {@code null} to fall back
     */
    public @Nullable PlayerProfile cached(@Nullable UUID uuid) {
        return uuid == null ? null : resolved.get(uuid);
    }

    private void resolve(UUID uuid) {
        try {
            PlayerProfile profile = Bukkit.createProfile(uuid);
            if (profile.complete(true) && profile.isComplete()) {
                resolved.put(uuid, profile);
            } else {
                unresolvable.add(uuid);
            }
        } catch (RuntimeException e) {
            // An offline-mode network, a Mojang outage, or an API without profile support.
            // Not recorded as unresolvable: that says something about the lookup, not about
            // the account, and a blanked cache until restart would be worse than the bug.
            log.log(Level.FINE, e, () -> "Could not resolve the head texture for " + uuid);
        }
    }
}
