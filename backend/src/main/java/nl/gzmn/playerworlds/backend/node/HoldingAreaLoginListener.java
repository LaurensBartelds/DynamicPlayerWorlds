package nl.gzmn.playerworlds.backend.node;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import java.util.Objects;
import java.util.Optional;
import nl.gzmn.playerworlds.backend.world.HoldingArea;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FR-11: every connection lands in the holding area, never inside a player
 * world.
 *
 * <p>A returning player's spawn point comes out of their {@code playerdata},
 * which is where they logged out — and if that was a player world that is still
 * loaded, the server puts them straight back into it before this plugin has any
 * say. FR-11 describes a join as arriving in the holding area and being taken
 * into a world by the transfer, and three things quietly depended on that being
 * true:
 *
 * <ul>
 *   <li>{@code ProfileListener} restores the per-world profile, and the position
 *       inside it, on {@code PlayerChangedWorldEvent} (FR-14, FR-15b). Landing
 *       already inside the destination means no world change fires, so nothing
 *       is restored and {@code TransferJoinListener}'s arrival teleport is the
 *       last word — which is the world spawn. That is the "my position resets
 *       every time I rejoin" report.
 *   <li>{@code VisibilityListener} routes the join broadcast to the group the
 *       player is standing in (FR-19). Standing in the world they logged out of
 *       announced them to <em>that</em> world's players even when the transfer
 *       was taking them somewhere else entirely.
 *   <li>A world cannot unload while a player is in it, so a login into an idle
 *       world revives it for as long as the login takes.
 * </ul>
 *
 * <p>Runs at {@code LOWEST} so a redirect is in place before anything else looks
 * at the spawn location.
 */
public final class HoldingAreaLoginListener implements Listener {

    private static final Logger log = LoggerFactory.getLogger(HoldingAreaLoginListener.class);

    private final WorldFolders folders;
    private final HoldingArea holdingArea;

    public HoldingAreaLoginListener(WorldFolders folders, HoldingArea holdingArea) {
        this.folders = Objects.requireNonNull(folders, "folders");
        this.holdingArea = Objects.requireNonNull(holdingArea, "holdingArea");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        Location redirected = holdingAreaFor(event.getSpawnLocation());
        if (redirected != null) {
            event.setSpawnLocation(redirected);
        }
    }

    /**
     * Where this login should land instead, or null to leave it alone.
     *
     * <p>Separated from the handler so the decision is testable without a login:
     * the event itself is constructed from a connection this plugin never holds.
     */
    public @Nullable Location holdingAreaFor(Location spawn) {
        Objects.requireNonNull(spawn, "spawn");
        World world = spawn.getWorld();
        if (world == null) {
            return null;
        }
        Optional<WorldFolders.PlayerWorldDimension> resolved = folders.resolve(world.getName());
        if (resolved.isEmpty()) {
            // Already outside every player world: the lobby, or the node's own
            // primary save. That is the holding area.
            return null;
        }
        WorldId worldId = resolved.get().worldId();
        World holding = holdingArea.destinationFor(worldId);
        if (holding == null) {
            // Only possible on a node whose every world belongs to this one,
            // which cannot happen while the server has a primary world. Leaving
            // them where they are beats refusing the login.
            log.error(
                    "player logging in inside world {} and this node has nowhere else to put them; "
                            + "their profile and position will not be restored (FR-11)",
                    worldId);
            return null;
        }
        log.debug("login inside player world {} redirected to the holding area {} (FR-11)", worldId, holding.getName());
        return holding.getSpawnLocation();
    }
}
