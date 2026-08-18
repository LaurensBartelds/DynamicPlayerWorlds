package nl.gzmn.playerworlds.backend.control;

import java.util.Objects;
import java.util.concurrent.Executor;
import nl.gzmn.playerworlds.backend.world.MembershipCache;
import nl.gzmn.playerworlds.backend.world.WorldSettingsCache;
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
 */
public final class InvalidateCacheHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(InvalidateCacheHandler.class);

    private final @Nullable NetworkSettings networkSettings;
    private final MembershipCache membershipCache;
    private final @Nullable WorldSettingsCache settingsCache;
    private final Executor dbExecutor;

    public InvalidateCacheHandler(
            @Nullable NetworkSettings networkSettings, MembershipCache membershipCache, Executor dbExecutor) {
        this(networkSettings, membershipCache, null, dbExecutor);
    }

    public InvalidateCacheHandler(
            @Nullable NetworkSettings networkSettings,
            MembershipCache membershipCache,
            @Nullable WorldSettingsCache settingsCache,
            Executor dbExecutor) {
        this.networkSettings = networkSettings;
        this.membershipCache = Objects.requireNonNull(membershipCache, "membershipCache");
        this.settingsCache = settingsCache;
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
        WorldId worldId = command.worldId();
        if (worldId != null) {
            membershipCache.invalidate(worldId);
            if (settingsCache != null) {
                settingsCache.invalidate(worldId);
            }
        } else {
            membershipCache.clear();
            if (settingsCache != null) {
                settingsCache.clear();
            }
        }
        return CommandResult.ok();
    }
}
