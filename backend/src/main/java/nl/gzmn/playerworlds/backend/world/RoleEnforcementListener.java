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

    public RoleEnforcementListener(WorldFolders folders, MembershipCache membership) {
        this.folders = Objects.requireNonNull(folders, "folders");
        this.membership = Objects.requireNonNull(membership, "membership");
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
     * first. Interact stays permitted: FR-9 gives visitors "interact only", which
     * is buttons, levers and doors.
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
        Optional<Role> role = roleIn(location, player);
        if (role.isEmpty()) {
            return;
        }
        // Per-world visitor container access is FR-9e and lives in
        // player_world.settings, which arrives with milestone 9. Until then the
        // specification's stated default applies: containers are locked to
        // BUILDER and above.
        if (!role.get().canOpenContainers(false)) {
            event.setCancelled(true);
            deny(player, "You cannot open containers here.");
        }
    }

    /**
     * Physical interaction that damages the world without being a break — trampling
     * farmland, and anything else a visitor should not be able to do to a world
     * they are only visiting.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || event.getAction() != org.bukkit.event.block.Action.PHYSICAL) {
            return;
        }
        if (!mayBuild(event.getPlayer(), block)) {
            event.setCancelled(true);
        }
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
