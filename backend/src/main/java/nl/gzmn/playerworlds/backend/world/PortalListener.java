package nl.gzmn.playerworlds.backend.world;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.PortalRouting;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FR-3a: portal transit inside one player world, in both directions.
 *
 * <p>This exists because Bukkit's default portal search resolves against the
 * server's <em>primary</em> world. Without an explicit destination, a player
 * stepping into a nether portal in their own world arrives in the lobby's nether
 * — or in somebody else's world, which is the same isolation failure specification
 * section 5.5 spends a page preventing.
 *
 * <p>It is also where FR-2's lazy materialisation happens. The nether and end are
 * not created at {@code /world create}; they are created here, on the tick that a
 * player first walks into the portal, with the world's stored seed so the result
 * is identical to having created them up front. That stall is FR-4's accepted
 * trade and the reason {@link LoadedWorld} caches the seed — this runs on the main
 * thread and cannot read the database (NFR-2).
 */
public final class PortalListener implements Listener {

    private static final Logger log = LoggerFactory.getLogger(PortalListener.class);

    /**
     * Where a player arrives in the end, matching vanilla's end spawn point.
     *
     * <p>Vanilla generates an obsidian platform here on arrival. Whether it still
     * does when the destination world is supplied by a plugin rather than resolved
     * by the server's own search is the one thing in this milestone that cannot be
     * verified without a live node — see plan 01 section 5.4. If it does not, a
     * player entering the end falls into the void.
     */
    private static final double END_SPAWN_X = 100.0;

    private static final double END_SPAWN_Y = 50.0;
    private static final double END_SPAWN_Z = 0.0;

    private final Platform platform;
    private final WorldFolders folders;
    private final WorldRegistry registry;
    private final WorldLifecycleService lifecycle;
    private final Supplier<NetworkPolicy> policy;

    public PortalListener(
            Platform platform,
            WorldFolders folders,
            WorldRegistry registry,
            WorldLifecycleService lifecycle,
            Supplier<NetworkPolicy> policy) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.folders = Objects.requireNonNull(folders, "folders");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Player transit. Runs at {@code HIGHEST} so a destination set here is the one
     * that survives, but still before {@code MONITOR} observers.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        Location from = event.getFrom();
        PortalRouting.PortalType portalType = portalTypeOf(event.getCause());
        if (portalType == null) {
            return;
        }

        Location target = resolveTarget(from, portalType, event.getTo());
        if (target == null) {
            return;
        }

        event.setTo(target);
        // The server still builds or finds a portal at the destination; it just
        // does so in the world we named rather than in the primary world.
        event.setCanCreatePortal(portalType == PortalRouting.PortalType.NETHER);
        log.debug(
                "routed {} portal for {} from {} to {}",
                portalType,
                event.getPlayer().getName(),
                from.getWorld().getName(),
                target.getWorld().getName());
    }

    /**
     * Entity transit — minecarts, items and mobs pushed through a nether portal.
     *
     * <p>Same routing, and it matters as much as the player case: an item that
     * takes the default search lands in the lobby's nether, where its owner will
     * never find it.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        Location from = event.getFrom();
        PortalRouting.PortalType portalType = portalTypeOf(event.getPortalType());
        if (portalType == null) {
            return;
        }

        Location target = resolveTarget(from, portalType, event.getTo());
        if (target == null) {
            return;
        }

        event.setTo(target);
        event.setCanCreatePortal(portalType == PortalRouting.PortalType.NETHER);
    }

    /**
     * Player respawn. Runs at {@code HIGHEST} so a destination set here survives,
     * but still before {@code MONITOR} observers.
     *
     * <p>Bukkit's default respawn search resolves against the server's primary
     * world. Without explicit routing, a player dying in their own world (or its
     * nether or end) respawns in the server lobby rather than in their world's
     * overworld.
     *
     * <p>If the player has a valid bed or respawn anchor inside this player world,
     * that spawn point is preserved. Otherwise, the player respawns at the world's
     * overworld spawn point.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        World deathWorld = player.getWorld();
        Optional<WorldFolders.PlayerWorldDimension> source = folders.resolve(deathWorld.getName());
        if (source.isEmpty()) {
            return;
        }

        WorldFolders.PlayerWorldDimension origin = source.get();
        Optional<LoadedWorld> found = registry.find(origin.worldId());
        if (found.isEmpty()) {
            log.warn(
                    "respawn for {} from {} but world {} is not registered on this node; leaving the event alone",
                    player.getName(),
                    deathWorld.getName(),
                    origin.worldId());
            return;
        }
        LoadedWorld world = found.get();

        if (event.isBedSpawn() || event.isAnchorSpawn()) {
            Location current = event.getRespawnLocation();
            if (current.getWorld() != null) {
                Optional<WorldFolders.PlayerWorldDimension> target =
                        folders.resolve(current.getWorld().getName());
                if (target.isPresent() && target.get().worldId().equals(origin.worldId())) {
                    return;
                }
            }
        }

        World overworld = materialise(world, DimensionKind.OVERWORLD);
        if (overworld == null) {
            return;
        }

        // A respawn is an arrival like any other, and the stored spawn point
        // is not a promise that there is ground under it (FR-3a).
        Location target = SafeSpawn.resolve(overworld);
        event.setRespawnLocation(target);
        log.debug(
                "routed respawn for {} from {} to {} spawn",
                player.getName(),
                deathWorld.getName(),
                overworld.getName());
    }

    /**
     * The destination for a transit, or {@code null} to leave the event alone.
     *
     * <p>Null means "not our business or not resolvable": the source is not a
     * player world, the world is somehow not registered, or the target dimension
     * could not be materialised. In the last case the event is left with the
     * server's own destination rather than cancelled, because cancelling leaves a
     * player standing in a portal that does nothing, which is harder to diagnose
     * than arriving somewhere wrong.
     */
    private @Nullable Location resolveTarget(
            Location from, PortalRouting.PortalType portalType, @Nullable Location serverTarget) {
        World fromWorld = from.getWorld();
        Optional<WorldFolders.PlayerWorldDimension> source = folders.resolve(fromWorld.getName());
        if (source.isEmpty()) {
            // The lobby, or any other world on this node. Vanilla behaviour.
            return null;
        }

        WorldFolders.PlayerWorldDimension origin = source.get();
        Optional<LoadedWorld> found = registry.find(origin.worldId());
        if (found.isEmpty()) {
            log.warn(
                    "portal transit from {} but world {} is not registered on this node; leaving the event alone",
                    fromWorld.getName(),
                    origin.worldId());
            return null;
        }
        LoadedWorld world = found.get();

        // An end gateway stays inside the end and the server has already computed
        // a sensible destination; all that can be wrong is which end it is in.
        if (portalType == PortalRouting.PortalType.END_GATEWAY) {
            return retargetWithinEnd(world, serverTarget, from);
        }

        PortalRouting.PortalRequest request = buildRequest(world, origin.dimension(), portalType, from);
        if (request == null) {
            return null;
        }

        PortalRouting.PortalTarget resolved;
        try {
            resolved = platform.portalRouting().resolve(request, platform.worldLayout());
        } catch (IllegalArgumentException e) {
            // A portal type that is not valid from this dimension — a nether
            // portal fired inside the end. Vanilla cannot produce it; another
            // plugin can. Leave it alone rather than guessing.
            log.warn("unroutable portal transit in world {}: {}", origin.worldId(), e.getMessage());
            return null;
        }

        World destination = materialise(world, resolved.dimension());
        if (destination == null) {
            return null;
        }
        if (resolved.dimension() == DimensionKind.END) {
            log.info(
                    "player world {} end entered at the vanilla end spawn; "
                            + "verify the arrival platform exists (plan 01 section 5.4)",
                    world.id());
        }
        return new Location(destination, resolved.x(), resolved.y(), resolved.z(), from.getYaw(), from.getPitch());
    }

    /**
     * The request the routing seam resolves.
     *
     * <p>The coordinates differ by case, which is what {@code PortalRouting}'s
     * contract asks of the caller: the nether cases scale the source position,
     * while the end cases supply the destination the game defines — the end spawn
     * platform going in, the world's own overworld spawn coming back out.
     */
    private PortalRouting.@Nullable PortalRequest buildRequest(
            LoadedWorld world, DimensionKind sourceDimension, PortalRouting.PortalType portalType, Location from) {
        // FR-3's divisor, not a separate constant. FR-3 sets the nether border to
        // border_radius / divisor "so the two line up in world coordinates", which
        // is only true if portal scaling uses the same number. The default of 8 is
        // vanilla's dimension scale; changing it moves both together, on purpose.
        int scale = policy.get().netherBorderDivisor();

        return switch (portalType) {
            case NETHER ->
                new PortalRouting.PortalRequest(
                        world.id().folder(),
                        sourceDimension,
                        PortalRouting.PortalType.NETHER,
                        from.getX(),
                        from.getY(),
                        from.getZ(),
                        scale);
            case END -> {
                if (sourceDimension == DimensionKind.END) {
                    // Coming home. The destination is this world's overworld spawn,
                    // which means the overworld has to be loaded to be asked.
                    World overworld = materialise(world, DimensionKind.OVERWORLD);
                    if (overworld == null) {
                        yield null;
                    }
                    Location spawn = overworld.getSpawnLocation();
                    yield new PortalRouting.PortalRequest(
                            world.id().folder(),
                            DimensionKind.END,
                            PortalRouting.PortalType.END,
                            spawn.getX(),
                            spawn.getY(),
                            spawn.getZ(),
                            scale);
                }
                yield new PortalRouting.PortalRequest(
                        world.id().folder(),
                        sourceDimension,
                        PortalRouting.PortalType.END,
                        END_SPAWN_X,
                        END_SPAWN_Y,
                        END_SPAWN_Z,
                        scale);
            }
            case END_GATEWAY -> null;
        };
    }

    /** Keeps a gateway's computed destination but forces it into this world's end. */
    private @Nullable Location retargetWithinEnd(
            LoadedWorld world, @Nullable Location serverTarget, Location fallback) {
        World end = materialise(world, DimensionKind.END);
        if (end == null) {
            return null;
        }
        Location basis = serverTarget != null ? serverTarget : fallback;
        if (basis.getWorld().getName().equals(end.getName())) {
            // Already inside the right end. Nothing to correct.
            return null;
        }
        return new Location(end, basis.getX(), basis.getY(), basis.getZ(), basis.getYaw(), basis.getPitch());
    }

    /**
     * The Bukkit world for a dimension, creating it on this tick if this is the
     * first transit into it (FR-2).
     */
    private @Nullable World materialise(LoadedWorld world, DimensionKind dimension) {
        if (!lifecycle.materialiseOnMain(world, dimension)) {
            return null;
        }
        String bukkitName = folders.bukkitWorldName(world.id(), dimension);
        World resolved = platform.worldLifecycle().loaded(bukkitName);
        if (resolved == null) {
            log.error("dimension {} of world {} reported materialised but is not loaded", dimension, world.id());
        }
        return resolved;
    }

    /** Maps a player teleport cause to the routing seam's portal type. */
    static PortalRouting.@Nullable PortalType portalTypeOf(TeleportCause cause) {
        return switch (cause) {
            case NETHER_PORTAL -> PortalRouting.PortalType.NETHER;
            case END_PORTAL -> PortalRouting.PortalType.END;
            case END_GATEWAY -> PortalRouting.PortalType.END_GATEWAY;
            default -> null;
        };
    }

    /** Maps the entity-side portal type to the routing seam's. */
    static PortalRouting.@Nullable PortalType portalTypeOf(org.bukkit.PortalType portalType) {
        return switch (portalType) {
            case NETHER -> PortalRouting.PortalType.NETHER;
            case ENDER -> PortalRouting.PortalType.END;
            case END_GATEWAY -> PortalRouting.PortalType.END_GATEWAY;
            default -> null;
        };
    }
}
