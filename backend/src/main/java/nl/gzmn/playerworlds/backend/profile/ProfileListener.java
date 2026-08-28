package nl.gzmn.playerworlds.backend.profile;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.core.concurrent.MainThread;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.ControlChannels;
import nl.gzmn.playerworlds.core.control.EjectPayload;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.ProfileRepository;
import nl.gzmn.playerworlds.core.db.ProfileRepository.Snapshot;
import nl.gzmn.playerworlds.core.db.ProfileRepository.StoredProfile;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.profile.ProfileCodec;
import nl.gzmn.playerworlds.core.profile.ProfileEnvelope;
import nl.gzmn.playerworlds.core.profile.ProfileFormatException;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
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
 * <p>R11 / FR-16: inventory is not cleared until a successful read. An
 * unreadable profile never mutates the player and ejects them to lobby via
 * {@code EJECT_PLAYER}, matching {@link nl.gzmn.playerworlds.backend.node.TransferJoinListener}.
 * FR-11's holding area is what keeps the brief window before the database
 * answers from carrying a previous world's inventory into this one.
 */
public final class ProfileListener implements Listener {

    /** Kept off the border itself, which is a wall a player can be pushed through. */
    private static final double BORDER_INSET = 2.0;

    private static final Logger log = LoggerFactory.getLogger(ProfileListener.class);

    private final WorldFolders folders;
    private final ProfileService profiles;
    private final ProfileRepository repository;
    private final @Nullable PlayerWorldRepository playerWorlds;
    private final WorldCommitService commits;
    private final PluginExecutors executors;
    private final NodeCommandRepository nodeCommands;
    private final Supplier<NetworkPolicy> policy;

    public ProfileListener(
            WorldFolders folders,
            ProfileService profiles,
            ProfileRepository repository,
            @Nullable PlayerWorldRepository playerWorlds,
            WorldCommitService commits,
            PluginExecutors executors,
            NodeCommandRepository nodeCommands,
            Supplier<NetworkPolicy> policy) {
        this.folders = Objects.requireNonNull(folders, "folders");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.playerWorlds = playerWorlds;
        this.commits = Objects.requireNonNull(commits, "commits");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.nodeCommands = Objects.requireNonNull(nodeCommands, "nodeCommands");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public ProfileListener(
            WorldFolders folders,
            ProfileService profiles,
            ProfileRepository repository,
            WorldCommitService commits,
            PluginExecutors executors,
            NodeCommandRepository nodeCommands,
            Supplier<NetworkPolicy> policy) {
        this(folders, profiles, repository, null, commits, executors, nodeCommands, policy);
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
     * <p>R11 / FR-16: does <em>not</em> clear inventory first. A failed read must
     * leave the player untouched and send them to lobby; clearing up-front made
     * the old "Nothing has been overwritten" message false. Successful empty
     * (never played) still applies FR-5's fresh profile after the read.
     *
     * <p>R10: {@link ProfileRepository#latestSnapshot} is only for the
     * no-object-storage mode ({@code manifest_key IS NULL}). A present key is
     * FR-15b's sole source; an unparseable key or a missing row when older
     * profiles exist refuses rather than inventing a fresh inventory (§7).
     */
    private void enter(Player player, WorldId worldId) {
        executors.db().execute(() -> {
            final Optional<StoredProfile> stored;
            try {
                SnapshotResolution resolution = resolveSnapshot(worldId);
                if (resolution.unparseableManifest()) {
                    log.error(
                            "manifest_key for world {} is present but unparseable; refusing profile load "
                                    + "rather than falling back to latestSnapshot (R10 / FR-15b)",
                            worldId);
                    executors
                            .main()
                            .execute(() -> refuse(
                                    player,
                                    worldId,
                                    "your inventory for this world could not be loaded",
                                    "Profile load refused: unparseable manifest_key (FR-16)"));
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
                    executors
                            .main()
                            .execute(() -> refuse(
                                    player,
                                    worldId,
                                    "your inventory for this world could not be loaded",
                                    "Profile load refused: snapshot row missing (FR-16)"));
                    return;
                }
            } catch (SQLException e) {
                log.error("could not read the profile of {} for world {}", player.getUniqueId(), worldId, e);
                executors
                        .main()
                        .execute(() -> refuse(
                                player,
                                worldId,
                                "your inventory for this world could not be loaded",
                                "Profile load refused: database error (FR-16)"));
                return;
            }

            if (stored.isEmpty()) {
                // FR-15b: no row for this snapshot means they have never played
                // here, and FR-5 says that is a fresh profile — applied only after
                // the successful empty read (R11).
                executors.main().execute(() -> {
                    if (player.isOnline()) {
                        profiles.applyFresh(player);
                    }
                });
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
                executors
                        .main()
                        .execute(() -> refuse(
                                player,
                                worldId,
                                "your inventory for this world could not be read",
                                "Profile could not be deserialised (FR-16)"));
                return;
            }

            executors.main().execute(() -> {
                if (player.isOnline()) {
                    profiles.restore(player, envelope);
                    returnToStoredLocation(player, envelope);
                }
            });
        });
    }

    /**
     * FR-14: puts the player back where they were, not at spawn.
     *
     * <p>{@code lastLocation} has been captured, encoded and decoded since
     * milestone 4 and read by nothing, so {@code capture}'s javadoc — "stored so
     * a rejoin returns them where they were rather than to spawn" — described
     * behaviour that did not exist. The two javadocs disagreed: {@code restore}'s
     * says it deliberately does not teleport, which stays true, because where the
     * player ends up is the caller's decision and this is the caller.
     *
     * <p>Main thread, after the restore, so the arrival teleport
     * {@code TransferJoinListener} performs has already happened and this is the
     * last word on where they stand.
     */
    private void returnToStoredLocation(Player player, ProfileEnvelope envelope) {
        MainThread.assertOn();
        ProfileEnvelope.StoredLocation stored = envelope.lastLocation();
        if (stored == null) {
            return;
        }
        // Every dimension present on disk is materialised by the load, so a
        // stored nether or end is already here — unless the world was archived
        // and restored without one, in which case spawn is the honest answer.
        World world = Bukkit.getWorld(stored.dimension());
        if (world == null) {
            log.debug(
                    "player {} was last in {}, which is not loaded; leaving them where they arrived",
                    player.getUniqueId(),
                    stored.dimension());
            return;
        }
        Location destination = clampToBorder(world, stored);
        var _ = player.teleport(destination);
    }

    /**
     * Brings a stored position inside the world's border (FR-3, FR-9e).
     *
     * <p>The border is read from the live world rather than recomputed: FR-25c
     * has the database value applied on every load, so this is that value, and a
     * border that has been shrunk since the player left would otherwise strand
     * them outside it — which is a player stuck in the void, not a cosmetic
     * problem.
     */
    private static Location clampToBorder(World world, ProfileEnvelope.StoredLocation stored) {
        WorldBorder border = world.getWorldBorder();
        double half = Math.max(0.0, border.getSize() / 2.0 - BORDER_INSET);
        Location centre = border.getCenter();
        double x = clamp(stored.x(), centre.getX() - half, centre.getX() + half);
        double z = clamp(stored.z(), centre.getZ() - half, centre.getZ() + half);
        return new Location(world, x, stored.y(), z, stored.yaw(), stored.pitch());
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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
     * FR-16's refusal path (R11): message the player and eject to lobby.
     *
     * <p>Must not mutate inventory — the player still holds whatever they arrived
     * with (holding-area state under FR-11). Reuses the same {@code EJECT_PLAYER}
     * enqueue as {@link nl.gzmn.playerworlds.backend.node.TransferJoinListener}.
     */
    private void refuse(Player player, @Nullable WorldId worldId, String message, String ejectReason) {
        if (player.isOnline()) {
            player.sendMessage(Component.text(
                    message + ". Returning you to the lobby — ask an admin to check the server log (FR-16).",
                    NamedTextColor.RED));
        }
        executors.db().execute(() -> {
            try {
                nodeCommands.enqueue(
                        "proxy",
                        worldId,
                        null,
                        CommandKind.EJECT_PLAYER.name(),
                        EjectPayload.format(player.getUniqueId(), ejectReason),
                        policy.get().holdingTimeout(),
                        ControlChannels.PROXY);
            } catch (SQLException e) {
                log.warn("could not enqueue EJECT_PLAYER for {} after FR-16 profile refusal", player.getUniqueId(), e);
            }
        });
    }
}
