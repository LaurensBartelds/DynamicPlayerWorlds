package nl.gzmn.playerworlds.backend.gui;

import com.destroystokyo.paper.profile.PlayerProfile;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolved player-head textures for the menu screens.
 *
 * <p>A head built from {@code Bukkit.getOfflinePlayer(uuid)} alone carries no skin: the
 * profile it hands back has a UUID and nothing else unless this node happens to have seen
 * that player before. A world owner usually has not been seen here — MN-15 puts many
 * owners' worlds on one node, and none of them need ever have logged into it — so the head
 * rendered as the default skin, which is what every skull in the menus was doing for
 * anyone but the viewer.
 *
 * <p>Filling a profile in means asking Mojang, which is HTTP and therefore banned on the
 * tick thread (NFR-2, CONTRIBUTING rule 3). Resolution runs on {@code io()} while a
 * screen's rows are being fetched — a wait that is already happening — and the render on
 * the main thread reads only what is already in the map. A miss is not an error: the head
 * falls back to the plain {@code OfflinePlayer}, exactly as before, and the next open of
 * the screen has the texture.
 *
 * <p>Negative results are remembered too. A UUID with no account behind it — a test
 * fixture, a cracked login, a member of a world whose account was deleted — would
 * otherwise be looked up again on every single screen open, forever.
 */
public final class HeadProfiles {

    private static final Logger log = LoggerFactory.getLogger(HeadProfiles.class);

    /**
     * How long a screen may wait on Mojang before it opens without textures.
     *
     * <p>Deliberately short. The menu opening late is worse than the menu opening with the
     * skin it had last time, and the second open is already correct because the lookup it
     * started carries on and populates the map.
     */
    private static final Duration LOOKUP_BUDGET = Duration.ofSeconds(2);

    private final Map<UUID, PlayerProfile> resolved = new ConcurrentHashMap<>();

    /** UUIDs Mojang has no account for, so they are not asked about twice. */
    private final Set<UUID> unresolvable = ConcurrentHashMap.newKeySet();

    /** In-flight lookups, so eight heads for one owner make one request. */
    private final Map<UUID, CompletableFuture<@Nullable PlayerProfile>> inFlight = new ConcurrentHashMap<>();

    /**
     * Resolves any of {@code uuids} not already known, and completes when they are done or
     * when {@link #LOOKUP_BUDGET} runs out, whichever is first.
     *
     * <p>Never completes exceptionally: a screen that cannot show a skin still opens.
     *
     * @param uuids the head owners about to be rendered; duplicates are tolerated
     * @param executor the pool to run lookups on, which must not be the main thread
     * @return a future that completes when it is worth rendering
     */
    public CompletableFuture<Void> prefetch(Collection<UUID> uuids, Executor executor) {
        Objects.requireNonNull(uuids, "uuids");
        Objects.requireNonNull(executor, "executor");

        List<CompletableFuture<@Nullable PlayerProfile>> pending = uuids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .filter(uuid -> !resolved.containsKey(uuid) && !unresolvable.contains(uuid))
                .map(uuid -> lookup(uuid, executor))
                .toList();

        if (pending.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                .completeOnTimeout(null, LOOKUP_BUDGET.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(e -> null);
    }

    /**
     * The completed profile for a player, or {@code null} when none has been resolved.
     *
     * @param uuid the player
     * @return a profile carrying textures, or {@code null} to fall back
     */
    public @Nullable PlayerProfile cached(@Nullable UUID uuid) {
        return uuid == null ? null : resolved.get(uuid);
    }

    private CompletableFuture<@Nullable PlayerProfile> lookup(UUID uuid, Executor executor) {
        return inFlight.computeIfAbsent(
                uuid,
                id -> CompletableFuture.supplyAsync(
                                () -> {
                                    PlayerProfile profile = Bukkit.createProfile(id);
                                    // Blocking — a cache hit or a session-server round trip — which is
                                    // exactly why this runs on io() and never on main().
                                    if (profile.complete(true) && profile.isComplete()) {
                                        resolved.put(id, profile);
                                        return profile;
                                    }
                                    unresolvable.add(id);
                                    return null;
                                },
                                executor)
                        .exceptionally(e -> {
                            // An offline-mode network, a Mojang outage, or a server whose API does
                            // not implement profiles at all (MockBukkit). None of them is worth a
                            // stack trace on every menu open, and none of them stops the menu.
                            //
                            // Deliberately not recorded as unresolvable: a failure here says
                            // something about the lookup, not about the account, and an outage
                            // that permanently blanked every head on the node until a restart
                            // would be a worse bug than the one this class fixes.
                            log.debug("Could not resolve the head texture for {}", id, e);
                            return null;
                        })
                        .whenComplete((profile, e) -> inFlight.remove(id)));
    }
}
