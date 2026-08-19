package nl.gzmn.playerworlds.backend.control;

import java.util.Objects;
import java.util.concurrent.Executor;
import nl.gzmn.playerworlds.backend.world.LoadedWorld;
import nl.gzmn.playerworlds.backend.world.WorldCacheLoader;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.core.control.CommandHandler;
import nl.gzmn.playerworlds.core.control.CommandResult;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import nl.gzmn.playerworlds.core.db.NetworkSettings;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@link nl.gzmn.playerworlds.core.control.CommandKind#INVALIDATE_CACHE}.
 *
 * <p>A <em>refresh</em>, not an eviction. The two world caches answer a miss with
 * a value rather than an absence, so dropping an entry for a world that is
 * loaded does not make the node re-read — it makes the node answer VISITOR and
 * {@code WorldSettings.defaults()} until the world unloads. Every producer of
 * this command ({@code /world promote}, {@code kick}, {@code ban},
 * {@code public}, {@code set}, and the eject path) wants the node to pick up a
 * change, which is a re-read. See {@link WorldCacheLoader}.
 */
public final class InvalidateCacheHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(InvalidateCacheHandler.class);

    private final @Nullable NetworkSettings networkSettings;
    private final WorldCacheLoader caches;
    private final @Nullable WorldRegistry registry;
    private final Executor dbExecutor;

    public InvalidateCacheHandler(
            @Nullable NetworkSettings networkSettings,
            WorldCacheLoader caches,
            @Nullable WorldRegistry registry,
            Executor dbExecutor) {
        this.networkSettings = networkSettings;
        this.caches = Objects.requireNonNull(caches, "caches");
        this.registry = registry;
        this.dbExecutor = Objects.requireNonNull(dbExecutor, "dbExecutor");
    }

    @Override
    public CommandResult handle(NodeCommand command) {
        if (networkSettings != null) {
            networkSettings.invalidate();
            dbExecutor.execute(() -> {
                try {
                    networkSettings.reload();
                } catch (Exception e) {
                    log.warn("could not reload network settings after cache invalidation", e);
                }
            });
        }

        // The refresh runs inline rather than on the db executor. This method is
        // already off the tick thread, and CP-5 makes a completed command mean
        // the effect has happened — a refresh dispatched elsewhere would let the
        // row be marked complete while the node was still answering from the old
        // membership.
        WorldId worldId = command.worldId();
        if (worldId != null) {
            // Refreshed even when this node does not hold the world: the loader
            // drops both entries if the row is gone, which is the correct answer
            // for a world that was deleted rather than changed.
            caches.refreshQuietly(worldId);
            return CommandResult.ok();
        }

        // Whole-node invalidation. Only the worlds this node actually holds are
        // worth re-reading; anything else has no tick thread to serve.
        if (registry != null) {
            for (LoadedWorld world : registry.loadedWorlds()) {
                caches.refreshQuietly(world.id());
            }
        }
        return CommandResult.ok();
    }
}
