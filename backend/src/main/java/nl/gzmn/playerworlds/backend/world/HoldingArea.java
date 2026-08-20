package nl.gzmn.playerworlds.backend.world;

import java.util.Objects;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;

/**
 * FR-11's holding area: somewhere on this node that is not the world a player is
 * being taken out of.
 *
 * <p>Players sit here for the moment between leaving a world and the proxy's
 * transfer arriving. The teleport also has to happen before the world can come
 * down at all, since Bukkit refuses to unload one that still holds a player —
 * which is why every give-up path needs this and why it was written twice, once
 * in {@code SelfFencingHandler} and once in {@code WorldHandoff}, identically
 * down to the comment.
 */
public final class HoldingArea {

    private final WorldFolders folders;

    public HoldingArea(WorldFolders folders) {
        this.folders = Objects.requireNonNull(folders, "folders");
    }

    /**
     * A world on this node that is not part of {@code leaving}.
     *
     * <p>Prefers a world that is not a player world at all — the lobby, or the
     * server's own primary save — and falls back to any player world that is not
     * this one, because somewhere is better than nowhere when the alternative is
     * a world that cannot be unloaded.
     *
     * <p>Null only on a node whose every world belongs to the one being left,
     * which cannot happen while the server has a primary world.
     *
     * @param leaving the world the player is being taken out of
     */
    public @Nullable World destinationFor(WorldId leaving) {
        Objects.requireNonNull(leaving, "leaving");
        for (World candidate : Bukkit.getWorlds()) {
            if (!folders.isPlayerWorld(candidate.getName())) {
                return candidate;
            }
        }
        for (World candidate : Bukkit.getWorlds()) {
            if (!candidate.getName().startsWith(leaving.folder())) {
                return candidate;
            }
        }
        return null;
    }
}
