package nl.gzmn.playerworlds.backend.world;

import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * FR-9 inside a world: OWNER and BUILDER build, VISITOR does not.
 *
 * <p>FR-9 is explicit that all three roles ship in v1 "because public worlds
 * depend on VISITOR being a real role rather than a placeholder". This is what
 * makes it one. Without it, milestone 3's isolation testing runs against worlds
 * where roles do nothing and milestone 9 inherits an untested permission model
 * at the moment it first admits strangers.
 *
 * <p>Only acts inside player worlds. Every event here also fires in the lobby and
 * in any other world on the node, and those are none of this plugin's business.
 *
 * <p>Reads roles from {@link MembershipCache} rather than the database, because
 * all of these run on the tick thread (NFR-2).
 */
public final class RoleEnforcementListener implements Listener {

    private final WorldFolders folders;
    private final MembershipCache membership;
    private final WorldSettingsCache settingsCache;

    public RoleEnforcementListener(WorldFolders folders, MembershipCache membership) {
        this(folders, membership, new WorldSettingsCache());
    }

    public RoleEnforcementListener(WorldFolders folders, MembershipCache membership, WorldSettingsCache settingsCache) {
        this.folders = Objects.requireNonNull(folders, "folders");
        this.membership = Objects.requireNonNull(membership, "membership");
        this.settingsCache = Objects.requireNonNull(settingsCache, "settingsCache");
    }

    /** FR-9: a visitor may not break blocks. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!mayBuild(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
            deny(event.getPlayer(), "You cannot break blocks here.");
        }
    }

    /** FR-9: a visitor may not place blocks. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!mayBuild(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
            deny(event.getPlayer(), "You cannot place blocks here.");
        }
    }

    /**
     * FR-9 / FR-9e: container access.
     *
     * <p>{@code InventoryOpenEvent} rather than {@code PlayerInteractEvent}
     * because it covers every route into a container — a right-click, a minecart
     * with a chest, another plugin opening one — where interact only covers the
     * first.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder == null) {
            // A plugin-created inventory with no block behind it: a menu, not a
            // container. Never this listener's business.
            return;
        }
        Location location = event.getInventory().getLocation();
        if (location == null) {
            return;
        }
        Optional<WorldFolders.PlayerWorldDimension> resolved =
                folders.resolve(location.getWorld().getName());
        if (resolved.isEmpty()) {
            return;
        }
        WorldId worldId = resolved.get().worldId();
        Role role = membership.effectiveRole(worldId, player.getUniqueId());
        boolean visitorsMayOpen = settingsCache.get(worldId).visitorsMayOpenContainers();
        if (!role.canOpenContainers(visitorsMayOpen)) {
            event.setCancelled(true);
            deny(player, "You cannot open containers here.");
        }
    }

    /**
     * Physical interaction and mechanism use (FR-9, FR-9e).
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Optional<WorldFolders.PlayerWorldDimension> resolved =
                folders.resolve(block.getWorld().getName());
        if (resolved.isEmpty()) {
            return;
        }
        WorldId worldId = resolved.get().worldId();
        Role role = membership.effectiveRole(worldId, event.getPlayer().getUniqueId());

        if (role == Role.VISITOR) {
            if (isInteractiveMechanism(block)) {
                if (!settingsCache.get(worldId).visitorsMayInteract()) {
                    event.setCancelled(true);
                    deny(event.getPlayer(), "Interactions are disabled in this world.");
                }
                return;
            }
            if (event.getAction() == org.bukkit.event.block.Action.PHYSICAL && !mayBuild(event.getPlayer(), block)) {
                event.setCancelled(true);
            }
        }
    }

    private static boolean isInteractiveMechanism(Block block) {
        org.bukkit.Material mat = block.getType();
        return org.bukkit.Tag.DOORS.isTagged(mat)
                || org.bukkit.Tag.TRAPDOORS.isTagged(mat)
                || org.bukkit.Tag.FENCE_GATES.isTagged(mat)
                || org.bukkit.Tag.BUTTONS.isTagged(mat)
                || org.bukkit.Tag.PRESSURE_PLATES.isTagged(mat)
                || mat == org.bukkit.Material.LEVER;
    }

    /**
     * Whether this player may change blocks at this location.
     *
     * <p>True for anything that is not a player world, so the lobby and every
     * other world on the node behave exactly as they would without this plugin.
     */
    private boolean mayBuild(Player player, Block block) {
        return roleIn(block.getLocation(), player).map(Role::canBuild).orElse(Boolean.TRUE);
    }

    /**
     * The player's role in the player world at this location, or empty when the
     * location is not in one.
     */
    private Optional<Role> roleIn(Location location, Player player) {
        Optional<WorldFolders.PlayerWorldDimension> resolved =
                folders.resolve(location.getWorld().getName());
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        WorldId worldId = resolved.get().worldId();
        return Optional.of(membership.effectiveRole(worldId, player.getUniqueId()));
    }

    private static void deny(Player player, String message) {
        // Action bar rather than chat: a visitor mining a wall generates one of
        // these per swing, and chat spam is its own denial of service.
        player.sendActionBar(Component.text(message, NamedTextColor.RED));
    }
}
