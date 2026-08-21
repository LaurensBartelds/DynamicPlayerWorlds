package nl.gzmn.playerworlds.backend.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.bukkit.entity.Player;

/**
 * Who can see whom on this node (§5.5).
 *
 * <p>A visibility group is one player world — all three dimensions as a single
 * unit — so walking into your own nether never changes who is visible to you
 * (FR-18).
 *
 * <p>A player who is <em>not</em> in a player world is a group of one. The
 * specification leaves that case implicit, and FR-11 answers it in passing: the
 * holding area "is not a world they can interact with or see anyone else from".
 * It is also the safe direction to be wrong in — grouping everyone outside a
 * player world together would put two mid-join strangers in each other's tab
 * list, which is the leak this exists to close.
 *
 * <p>Pure functions over world names, so the rule is testable without a server
 * and reads the same everywhere it is applied.
 */
public final class VisibilityGroups {

    private final WorldFolders folders;

    public VisibilityGroups(WorldFolders folders) {
        this.folders = Objects.requireNonNull(folders, "folders");
    }

    /**
     * The group a Bukkit world belongs to.
     *
     * <p>Empty means "not a player world", which callers must treat as a group of
     * one rather than as a shared group.
     */
    public Optional<WorldId> groupOf(String bukkitWorldName) {
        Objects.requireNonNull(bukkitWorldName, "bukkitWorldName");
        return folders.resolve(bukkitWorldName).map(WorldFolders.PlayerWorldDimension::worldId);
    }

    /** The group this player is currently in. */
    public Optional<WorldId> groupOf(Player player) {
        Objects.requireNonNull(player, "player");
        return groupOf(player.getWorld().getName());
    }

    /**
     * Whether two worlds are the same visibility group.
     *
     * <p>False when either is not a player world, <em>including when both are the
     * same non-player world</em>: two players sitting in the holding area are not
     * a group.
     */
    public boolean sameGroup(String firstWorldName, String secondWorldName) {
        Optional<WorldId> first = groupOf(firstWorldName);
        Optional<WorldId> second = groupOf(secondWorldName);
        return first.isPresent() && second.isPresent() && first.get().equals(second.get());
    }

    /** Whether these two players may see each other (FR-18). */
    public boolean mutuallyVisible(Player first, Player second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        return sameGroup(first.getWorld().getName(), second.getWorld().getName());
    }

    /**
     * Everyone {@code viewer} may see, out of {@code candidates}, excluding
     * themselves.
     *
     * <p>The single place a recipient set is narrowed, so FR-19's rule — a
     * broadcast reaching every player on the server is a defect unless it went
     * through the group filter — has one implementation to audit.
     */
    public List<Player> visibleTo(Player viewer, Iterable<? extends Player> candidates) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(candidates, "candidates");
        List<Player> visible = new ArrayList<>();
        for (Player candidate : candidates) {
            if (!candidate.getUniqueId().equals(viewer.getUniqueId()) && mutuallyVisible(viewer, candidate)) {
                visible.add(candidate);
            }
        }
        return List.copyOf(visible);
    }

    /**
     * Everyone standing in {@code group} right now, except {@code exclude}.
     *
     * <p>{@link #visibleTo} answers "who can this player see", which is the wrong
     * question for a player who has just left a group: by the time the world
     * change has been observed they are no longer in it, so the people who should
     * be told they left are exactly the ones they can no longer see. This asks
     * about the group instead of about the mover, so both halves of a transition
     * reach the right room.
     */
    public List<Player> inGroup(WorldId group, Iterable<? extends Player> candidates, Player exclude) {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(exclude, "exclude");
        List<Player> members = new ArrayList<>();
        for (Player candidate : candidates) {
            if (candidate.getUniqueId().equals(exclude.getUniqueId())) {
                continue;
            }
            if (groupOf(candidate).filter(group::equals).isPresent()) {
                members.add(candidate);
            }
        }
        return List.copyOf(members);
    }

    /** The group's online population, counting the viewer (FR-23). */
    public int groupCount(Player viewer, Iterable<? extends Player> candidates) {
        return visibleTo(viewer, candidates).size() + 1;
    }
}
