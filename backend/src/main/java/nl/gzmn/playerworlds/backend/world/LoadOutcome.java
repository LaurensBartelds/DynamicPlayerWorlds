package nl.gzmn.playerworlds.backend.world;

import java.util.Objects;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;

/** What loading a world onto this node did. */
public sealed interface LoadOutcome {

    /** Loaded, or already loaded — both give the caller a world to send a player to. */
    record Loaded(LoadedWorld world) implements LoadOutcome {
        public Loaded {
            Objects.requireNonNull(world, "world");
        }
    }

    /** No such row. */
    record NotFound(WorldId id) implements LoadOutcome {
        public NotFound {
            Objects.requireNonNull(id, "id");
        }
    }

    /** Archived, archiving or restoring — FR-35 and FR-36 own the world right now. */
    record WrongState(WorldId id, WorldState state) implements LoadOutcome {
        public WrongState {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(state, "state");
        }
    }

    /**
     * MN-26: the world was last written by a newer Minecraft version than this
     * node runs.
     *
     * <p>Chunk {@code DataVersion} only moves forward and Minecraft has no
     * supported chunk downgrade, so this is a refusal rather than an attempt. The
     * player is told the world needs a newer server; the node logs
     * {@code version.refused}.
     */
    record TooNew(WorldId id, int worldDataVersion, int nodeDataVersion) implements LoadOutcome {
        public TooNew {
            Objects.requireNonNull(id, "id");
        }
    }

    /** FR-26: this node already holds {@code nodes.max-worlds}. */
    record NodeFull(int loaded, int cap) implements LoadOutcome {}

    /** The world folder is on disk but the server refused to load a dimension. */
    record Failed(WorldId id, String reason) implements LoadOutcome {
        public Failed {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
