package nl.gzmn.playerworlds.backend.profile;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.ProfileRepository;
import nl.gzmn.playerworlds.core.db.ProfileRepository.Snapshot;
import nl.gzmn.playerworlds.core.db.ProfileRepository.StoredProfile;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.profile.ProfileCodec;
import nl.gzmn.playerworlds.core.profile.ProfileEnvelope;
import nl.gzmn.playerworlds.core.profile.ProfileFormatException;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FR-15's commit triggers, and the load side of FR-15b.
 *
 * <p>Crossing into a player world restores that world's profile; crossing out of
 * one commits it. Moving between a world's own dimensions is neither — FR-2
 * treats the three as a single unit, and a nether portal is not a change of
 * profile any more than it is a change of visibility group.
 *
 * <p>Milestone 4 leaves one window open and it is worth naming: a player is in
 * the world for the tick or two it takes the database read to come back, holding
 * whatever they arrived with. FR-11's holding area is what closes it, and it
 * arrives with the transfer path in milestone 5. Until then the inventory is
 * cleared the moment they cross, so the window contains an empty inventory
 * rather than the wrong one — which can lose nothing into the world.
 */
public final class ProfileListener implements Listener {

    private static final Logger log = LoggerFactory.getLogger(ProfileListener.class);

    private final WorldFolders folders;
    private final ProfileService profiles;
    private final ProfileRepository repository;
    private final @Nullable PlayerWorldRepository playerWorlds;
    private final WorldCommitService commits;
    private final PluginExecutors executors;

    public ProfileListener(
            WorldFolders folders,
            ProfileService profiles,
            ProfileRepository repository,
            @Nullable PlayerWorldRepository playerWorlds,
            WorldCommitService commits,
            PluginExecutors executors) {
        this.folders = Objects.requireNonNull(folders, "folders");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.playerWorlds = playerWorlds;
        this.commits = Objects.requireNonNull(commits, "commits");
        this.executors = Objects.requireNonNull(executors, "executors");
    }

    public ProfileListener(
            WorldFolders folders,
            ProfileService profiles,
            ProfileRepository repository,
            WorldCommitService commits,
            PluginExecutors executors) {
        this(folders, profiles, repository, null, commits, executors);
    }

    /**
     * Crossing a world boundary: commit the one being left, restore the one being
     * entered (FR-15, FR-15b).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        Optional<WorldId> from =
                folders.resolve(event.getFrom().getName()).map(WorldFolders.PlayerWorldDimension::worldId);
        Optional<WorldId> to =
                folders.resolve(player.getWorld().getName()).map(WorldFolders.PlayerWorldDimension::worldId);

        if (from.equals(to)) {
            // Within one world's three dimensions. FR-2 treats them as a unit.
            return;
        }
        from.ifPresent(worldId -> {
            // Captured on this tick, while their state is still the world's.
            var _ = commits.commitDeparture(worldId, player, event.getFrom().getName());
        });
        to.ifPresent(worldId -> enter(player, worldId));
    }

    /** Disconnecting inside a world is one of FR-15's triggers. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        folders.resolve(player.getWorld().getName())
                .map(WorldFolders.PlayerWorldDimension::worldId)
                .ifPresent(worldId -> {
                    var _ = commits.commitDeparture(
                            worldId, player, player.getWorld().getName());
                });
    }

    /**
     * Loads the profile for the snapshot this world is at (FR-15b).
     *
     * <p>Clears first, on the tick the player crosses, so the gap before the
     * database answers holds an empty inventory rather than the one they walked
     * in with — an empty one can lose nothing into the world.
     *
     * <p>R10: {@link ProfileRepository#latestSnapshot} is only for the
     * no-object-storage mode ({@code manifest_key IS NULL}). A present key is
     * FR-15b's sole source; an unparseable key or a missing row when older
     * profiles exist refuses rather than inventing a fresh inventory (§7).
     */
    private void enter(Player player, WorldId worldId) {
        profiles.applyFresh(player);

        executors.db().execute(() -> {
            final Optional<StoredProfile> stored;
            try {
                SnapshotResolution resolution = resolveSnapshot(worldId);
                if (resolution.unparseableManifest()) {
                    log.error(
                            "manifest_key for world {} is present but unparseable; refusing profile load "
                                    + "rather than falling back to latestSnapshot (R10 / FR-15b)",
                            worldId);
                    executors.main().execute(() -> refuse(player, "your inventory for this world could not be loaded"));
                    return;
                }
                Optional<Snapshot> snapshot = resolution.snapshot();
                stored = snapshot.isEmpty()
                        ? Optional.empty()
                        : repository.load(worldId, player.getUniqueId(), snapshot.get());

                if (stored.isEmpty()
                        && resolution.fromManifestKey()
                        && repository.hasAnyProfile(worldId, player.getUniqueId())) {
                    // Named snapshot has no row, but older ones do — FR-5 fresh would
                    // be the silent wipe §7 warns about (pruned profiles, missed re-key).
                    log.error(
                            "profile of {} in world {} missing for manifest snapshot {} but older rows exist; "
                                    + "refusing rather than granting a fresh inventory (R10 / FR-15b / §7)",
                            player.getUniqueId(),
                            worldId,
                            snapshot.orElse(null));
                    executors.main().execute(() -> refuse(player, "your inventory for this world could not be loaded"));
                    return;
                }
            } catch (SQLException e) {
                log.error("could not read the profile of {} for world {}", player.getUniqueId(), worldId, e);
                executors.main().execute(() -> refuse(player, "your inventory for this world could not be loaded"));
                return;
            }

            if (stored.isEmpty()) {
                // FR-15b: no row for this snapshot means they have never played
                // here, and FR-5 says that is a fresh profile — which they already
                // have from the clear above.
                return;
            }

            final ProfileEnvelope envelope;
            try {
                envelope = ProfileCodec.decode(stored.get().data(), stored.get().formatVersion());
            } catch (ProfileFormatException e) {
                // FR-16: refuse rather than grant an empty inventory. An empty one
                // is indistinguishable from a wipe, and the player would spend it
                // before anybody realised. FR-16a's admin rollback is the repair.
                log.error(
                        "profile of {} in world {} cannot be deserialised; refusing rather than granting an empty "
                                + "inventory (FR-16). An admin rollback under FR-16a is the repair.",
                        player.getUniqueId(),
                        worldId,
                        e);
                executors.main().execute(() -> refuse(player, "your inventory for this world could not be read"));
                return;
            }

            executors.main().execute(() -> {
                if (player.isOnline()) {
                    profiles.restore(player, envelope);
                }
            });
        });
    }

    /**
     * Resolves which profile snapshot to load (R10 / FR-15b).
     *
     * <ul>
     *   <li>{@code manifest_key} present and parseable → that snapshot only
     *   <li>{@code manifest_key} present and unparseable → refuse (no latest fallback)
     *   <li>{@code manifest_key} null → {@link ProfileRepository#latestSnapshot}
     *       (no-object-storage / generation-0 profiles)
     * </ul>
     */
    SnapshotResolution resolveSnapshot(WorldId worldId) throws SQLException {
        if (playerWorlds == null) {
            return SnapshotResolution.latest(repository.latestSnapshot(worldId));
        }
        Optional<PlayerWorld> pw = playerWorlds.findById(worldId);
        if (pw.isEmpty()) {
            return SnapshotResolution.latest(Optional.empty());
        }
        String manifestKey = pw.get().manifestKey();
        if (manifestKey == null) {
            return SnapshotResolution.latest(repository.latestSnapshot(worldId));
        }
        Optional<Snapshot> parsed = parseSnapshotFromManifestKey(manifestKey);
        if (parsed.isEmpty()) {
            return SnapshotResolution.unparseable();
        }
        return SnapshotResolution.named(parsed.get());
    }

    /** Outcome of {@link #resolveSnapshot} (R10). */
    record SnapshotResolution(Optional<Snapshot> snapshot, boolean fromManifestKey, boolean unparseableManifest) {
        static SnapshotResolution named(Snapshot snapshot) {
            return new SnapshotResolution(Optional.of(snapshot), true, false);
        }

        static SnapshotResolution latest(Optional<Snapshot> snapshot) {
            return new SnapshotResolution(snapshot, false, false);
        }

        static SnapshotResolution unparseable() {
            return new SnapshotResolution(Optional.empty(), true, true);
        }
    }

    /**
     * Parses snapshot generation and sequence from a standard manifest key.
     * Manifest key format: {@code worlds/<worldId>/manifest/<gen>-<seq>.json}
     */
    static Optional<Snapshot> parseSnapshotFromManifestKey(String manifestKey) {
        if (manifestKey == null || manifestKey.isBlank()) {
            return Optional.empty();
        }
        int lastSlash = manifestKey.lastIndexOf('/');
        String filename = lastSlash >= 0 ? manifestKey.substring(lastSlash + 1) : manifestKey;
        if (filename.endsWith(".json")) {
            filename = filename.substring(0, filename.length() - 5);
        }
        int dash = filename.lastIndexOf('-');
        if (dash <= 0 || dash >= filename.length() - 1) {
            return Optional.empty();
        }
        try {
            long gen = Long.parseLong(filename.substring(0, dash));
            int seq = Integer.parseInt(filename.substring(dash + 1));
            return Optional.of(new Snapshot(gen, seq));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * FR-16's refusal path.
     *
     * <p>Sending the player to lobby is milestone 5's transfer; until it exists
     * the honest thing is to say so loudly and leave them with the empty
     * inventory they already have, rather than silently handing them a world's
     * worth of nothing and calling it their profile.
     */
    private void refuse(Player player, String reason) {
        player.sendMessage(Component.text(
                reason + ". Nothing has been overwritten — ask an admin to check the server log (FR-16).",
                NamedTextColor.RED));
    }
}
