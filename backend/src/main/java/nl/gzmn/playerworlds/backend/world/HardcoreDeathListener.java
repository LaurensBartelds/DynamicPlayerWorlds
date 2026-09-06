package nl.gzmn.playerworlds.backend.world;

import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.gzmn.playerworlds.backend.gui.Messages;
import nl.gzmn.playerworlds.backend.gui.Placeholders;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.MessageCatalog;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.ControlChannels;
import nl.gzmn.playerworlds.core.control.EjectPayload;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.NodeCommandRepository;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FR-5b: a death in a hardcore world is the end of that world for the player who
 * died.
 *
 * <p>Three things happen, in this order and for these reasons.
 *
 * <ol>
 *   <li><b>The death is recorded</b> on {@code PlayerDeathEvent}, off the tick
 *       thread (NFR-2). This is the part that must not be missed: the refusal to
 *       come back is enforced on the proxy from {@code player_world_member
 *       .died_at}, so until that write lands the death has not really happened.
 *       It is started before the player is moved, so a node that dies between the
 *       two leaves a player who is dead and still standing there rather than one
 *       who was quietly let off.
 *   <li><b>The player is taken out of the world</b> a tick after they respawn.
 *       Not on the death event, where they are still looking at the death screen
 *       and have no location to move yet, and not inside the respawn event
 *       either: the respawn location is settled there by {@code PortalListener}
 *       (FR-3a) and two handlers writing it decide the outcome by registration
 *       order. A tick later there is nothing left to race.
 *   <li><b>The proxy is told</b> over the control plane, the same {@code
 *       EJECT_PLAYER} path {@code /world leave} and a ban already use, which is
 *       what actually returns them to the lobby server.
 * </ol>
 *
 * <p>Who this applies to is FR-5b's rule and not this class's: OWNER and BUILDER
 * are subject, VISITOR is exempt. A public world (FR-9c) hands VISITOR to anyone
 * who walks in, and a stranger who dies once must not be locked out of somebody
 * else's world forever.
 *
 * <p>Roles come from {@link MembershipCache} and the hardcore flag from {@link
 * WorldRegistry}, because both are read on the tick thread where a query is
 * banned. A cache miss reads as "not a member", which here means no death is
 * recorded — the safe direction: the alternative locks somebody out of a world
 * on the strength of a cache that had not been filled yet.
 */
public final class HardcoreDeathListener implements Listener {

    private static final Logger log = LoggerFactory.getLogger(HardcoreDeathListener.class);

    private final Plugin plugin;
    private final WorldFolders folders;
    private final WorldRegistry registry;
    private final MembershipCache membershipCache;
    private final MembershipRepository membership;
    private final PluginExecutors executors;
    private final NodeCommandRepository nodeCommands;
    private final Supplier<NetworkPolicy> policy;
    private final Messages messages;

    /**
     * Players whose respawn owes them an ejection, and the world they died in.
     *
     * <p>Recorded at death rather than re-derived at respawn: by then the
     * respawn location may be anywhere — a bed in another dimension, the lobby if
     * something else moved them — and the world they died in is the only one this
     * is about.
     *
     * <p>Node-local and deliberately not durable. If the node dies between the
     * death and the respawn the player is still dead in the database and the
     * proxy refuses their next entry (FR-5b), which is the outcome this map
     * exists to produce, reached the slower way.
     */
    private final Map<UUID, DeathSite> pendingEjection = new ConcurrentHashMap<>();

    /** Where a pending ejection came from: the world id for the control-plane row, the name for the player. */
    private record DeathSite(WorldId worldId, String worldName) {}

    public HardcoreDeathListener(
            Plugin plugin,
            WorldFolders folders,
            WorldRegistry registry,
            MembershipCache membershipCache,
            MembershipRepository membership,
            PluginExecutors executors,
            NodeCommandRepository nodeCommands,
            Supplier<NetworkPolicy> policy,
            @Nullable Supplier<MessageCatalog> messageCatalog) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.folders = Objects.requireNonNull(folders, "folders");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.membershipCache = Objects.requireNonNull(membershipCache, "membershipCache");
        this.membership = Objects.requireNonNull(membership, "membership");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.nodeCommands = Objects.requireNonNull(nodeCommands, "nodeCommands");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.messages = new Messages(messageCatalog);
    }

    /**
     * Records the death (FR-5b).
     *
     * <p>{@code MONITOR} because this changes nothing about the death itself:
     * drops, {@code keepInventory} (FR-9i) and the death message (FR-19) are all
     * decided by the time it runs. A hardcore world is one whose consequences are
     * heavier than vanilla's, not one that dies differently.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Optional<LoadedWorld> world = subjectWorld(player);
        if (world.isEmpty()) {
            return;
        }

        WorldId worldId = world.get().id();
        UUID uuid = player.getUniqueId();
        // Recorded before the database write is dispatched rather than after it
        // completes: the respawn can arrive on the very next tick, long before a
        // round trip finishes, and a player who respawns first would stay put.
        pendingEjection.put(uuid, new DeathSite(worldId, world.get().name()));

        executors.db().execute(() -> {
            try {
                if (membership.markDied(worldId, uuid)) {
                    log.info("{} died permanently in hardcore world {} (FR-5b)", uuid, worldId);
                } else {
                    // Already dead, or no longer a member. Neither is an error:
                    // the write is idempotent by design (FR-5b), and a member
                    // removed mid-death has lost the world another way already.
                    log.debug("hardcore death for {} in {} recorded nothing", uuid, worldId);
                }
            } catch (SQLException e) {
                // The player is ejected either way. A death the database missed
                // is a player who may come back, which is wrong but recoverable;
                // a player left standing in the world they just died in is not.
                log.error("could not record hardcore death for {} in {} (FR-5b)", uuid, worldId, e);
            }
        });
    }

    /**
     * Takes the player out of the world once they have respawned (FR-5b).
     *
     * <p>Scheduled a tick out rather than done here, for the reason in the class
     * comment: the respawn location belongs to whoever the respawn event settles
     * it with, and this has no business being one of them.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        DeathSite site = pendingEjection.remove(player.getUniqueId());
        if (site == null) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> ejectOnMain(player, site));
    }

    /**
     * Drops a pending ejection for a player who logged out on the death screen.
     *
     * <p>Their death is already recorded, so the proxy refuses their next entry
     * (FR-5b). Keeping the entry would leave this map holding a player who is not
     * coming back to be ejected.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        pendingEjection.remove(event.getPlayer().getUniqueId());
    }

    /**
     * The world this player has just died in, when FR-5b applies to them there.
     *
     * <p>Empty for a death outside a player world, in a world this node does not
     * hold, in one that is not hardcore, or by a VISITOR.
     */
    private Optional<LoadedWorld> subjectWorld(Player player) {
        Optional<WorldFolders.PlayerWorldDimension> resolved =
                folders.resolve(player.getWorld().getName());
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        WorldId worldId = resolved.get().worldId();
        Optional<LoadedWorld> loaded = registry.find(worldId);
        if (loaded.isEmpty() || !loaded.get().isHardcore()) {
            return Optional.empty();
        }
        Optional<Role> role = membershipCache.roleOf(worldId, player.getUniqueId());
        if (role.isEmpty() || role.get() == Role.VISITOR) {
            return Optional.empty();
        }
        return loaded;
    }

    /**
     * Moves the player out of the world and asks the proxy to send them home.
     *
     * <p>Both halves, in the order {@code /world leave} already uses: the local
     * teleport gets them out of the player world on this node even if the control
     * plane is slow, and the {@code EJECT_PLAYER} command is what returns them to
     * the lobby server.
     */
    private void ejectOnMain(Player player, DeathSite site) {
        if (!player.isOnline()) {
            return;
        }

        Component notice =
                messages.render("messages.notice.hardcore-death", Placeholders.text("world", site.worldName()));
        player.sendMessage(notice);

        // Resolved through the server on this tick and not held (FR-25b).
        World fallback = null;
        for (World world : plugin.getServer().getWorlds()) {
            if (!folders.isPlayerWorld(world.getName())) {
                fallback = world;
                break;
            }
        }
        if (fallback != null && folders.isPlayerWorld(player.getWorld().getName())) {
            player.teleport(fallback.getSpawnLocation());
        }

        String reason = PlainTextComponentSerializer.plainText().serialize(notice);
        UUID uuid = player.getUniqueId();
        executors.db().execute(() -> {
            try {
                nodeCommands.enqueue(
                        "proxy",
                        site.worldId(),
                        null,
                        CommandKind.EJECT_PLAYER.name(),
                        EjectPayload.format(uuid, reason),
                        policy.get().holdingTimeout(),
                        ControlChannels.PROXY);
            } catch (SQLException e) {
                log.warn("could not enqueue EJECT_PLAYER for hardcore death of {}", uuid, e);
            }
        });
    }
}
