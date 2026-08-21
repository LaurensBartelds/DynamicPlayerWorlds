package nl.gzmn.playerworlds.backend.control;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import nl.gzmn.playerworlds.backend.platform.DimensionKind;
import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.WorldRuntime;
import nl.gzmn.playerworlds.backend.world.LoadedWorld;
import nl.gzmn.playerworlds.backend.world.WorldCacheLoader;
import nl.gzmn.playerworlds.backend.world.WorldFolders;
import nl.gzmn.playerworlds.backend.world.WorldRegistry;
import nl.gzmn.playerworlds.backend.world.WorldSettingsCache;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.control.CommandHandler;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.CommandResult;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldSettings;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@link CommandKind#APPLY_SETTINGS} (FR-9e, FR-9i, CP-6, R9).
 *
 * <p>{@code /world set} commits the new JSON to {@code player_world.settings} on
 * the proxy, then asks every relevant node to pick it up. Every FR-9e/FR-9i
 * gamerule lives in {@code level.dat} as well as the database, so a cache
 * refresh alone is not enough on a world that is already loaded — the
 * gamerules must be re-asserted on the main thread across every materialised
 * dimension. Container and interact rules only need the settings cache (see
 * {@link nl.gzmn.playerworlds.backend.world.RoleEnforcementListener}).
 *
 * <p>Idempotent (CP-5): a missing world, or a world not held here, completes
 * {@code OK} after refreshing (or dropping) the cache. Completing the row means
 * the effect has happened — the main-thread gamerule write is waited on.
 */
public final class ApplySettingsHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ApplySettingsHandler.class);

    private static final long APPLY_TIMEOUT_SECONDS = 5L;

    private final WorldCacheLoader caches;
    private final WorldSettingsCache settingsCache;
    private final @Nullable WorldRegistry registry;
    private final @Nullable WorldFolders folders;
    private final @Nullable Platform platform;
    private final @Nullable PluginExecutors executors;

    public ApplySettingsHandler(
            WorldCacheLoader caches,
            WorldSettingsCache settingsCache,
            @Nullable WorldRegistry registry,
            @Nullable WorldFolders folders,
            @Nullable Platform platform,
            @Nullable PluginExecutors executors) {
        this.caches = Objects.requireNonNull(caches, "caches");
        this.settingsCache = Objects.requireNonNull(settingsCache, "settingsCache");
        this.registry = registry;
        this.folders = folders;
        this.platform = platform;
        this.executors = executors;
    }

    @Override
    public CommandResult handle(NodeCommand command) throws Exception {
        WorldId worldId = command.worldId();
        if (worldId == null) {
            return CommandResult.error("missing world_id");
        }

        // Inline JDBC refresh (CP-5): completed means the node is already answering
        // from the new settings, not that a refresh was scheduled elsewhere.
        if (!caches.refresh(worldId)) {
            return CommandResult.ok();
        }

        WorldSettings settings = settingsCache.get(worldId);
        LoadedWorld loaded = registry != null ? registry.find(worldId).orElse(null) : null;
        if (loaded != null) {
            // Keep LoadedWorld in step so a dimension materialised later (portal)
            // applies the same FR-9e values rather than the load-time snapshot.
            loaded.updateSettingsJson(settings.toJson());
        }

        WorldFolders worldFolders = folders;
        Platform worldPlatform = platform;
        PluginExecutors pools = executors;
        if (loaded == null
                || worldFolders == null
                || worldPlatform == null
                || pools == null
                || loaded.materialised().isEmpty()) {
            return CommandResult.ok();
        }

        applyGamerulesOnMain(worldId, loaded, settings, worldFolders, worldPlatform, pools);
        return CommandResult.ok();
    }

    private void applyGamerulesOnMain(
            WorldId worldId,
            LoadedWorld loaded,
            WorldSettings settings,
            WorldFolders worldFolders,
            Platform worldPlatform,
            PluginExecutors pools)
            throws Exception {
        CompletableFuture<Void> applied = new CompletableFuture<>();
        pools.main().execute(() -> {
            try {
                WorldRuntime runtime = worldPlatform.worldRuntime();
                for (DimensionKind dimension : loaded.materialised()) {
                    String bukkitName = worldFolders.bukkitWorldName(worldId, dimension);
                    World world = Bukkit.getWorld(bukkitName);
                    if (world == null) {
                        continue;
                    }
                    runtime.setPvp(world, settings.pvp());
                    runtime.setMobGriefing(world, settings.mobGriefing());
                    runtime.setGameRule(world, GameRules.KEEP_INVENTORY, settings.keepInventory());
                    runtime.setGameRule(world, GameRules.FALL_DAMAGE, settings.fallDamage());
                    runtime.setGameRule(world, GameRules.FIRE_DAMAGE, settings.fireDamage());
                    runtime.setGameRule(world, GameRules.FREEZE_DAMAGE, settings.freezeDamage());
                    runtime.setGameRule(world, GameRules.DROWNING_DAMAGE, settings.drowningDamage());
                    runtime.setGameRule(world, GameRules.ADVANCE_TIME, settings.advanceTime());
                    runtime.setGameRule(world, GameRules.ADVANCE_WEATHER, settings.advanceWeather());
                    runtime.setGameRule(world, GameRules.SPAWN_PHANTOMS, settings.spawnPhantoms());
                    runtime.setGameRule(world, GameRules.IMMEDIATE_RESPAWN, settings.immediateRespawn());
                    runtime.setGameRule(
                            world, GameRules.NATURAL_HEALTH_REGENERATION, settings.naturalHealthRegeneration());
                    runtime.setGameRule(
                            world, GameRules.PLAYERS_SLEEPING_PERCENTAGE, settings.playersSleepingPercentage());
                    runtime.setGameRule(world, GameRules.MAX_ENTITY_CRAMMING, settings.maxEntityCramming());
                    runtime.setGameRule(world, GameRules.RESPAWN_RADIUS, settings.respawnRadius());
                    runtime.setGameRule(
                            world, GameRules.MAX_SNOW_ACCUMULATION_HEIGHT, settings.maxSnowAccumulationHeight());
                }
                applied.complete(null);
            } catch (RuntimeException e) {
                applied.completeExceptionally(e);
            }
        });
        try {
            applied.get(APPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("APPLY_SETTINGS could not re-assert gamerules for world {} (FR-9e, FR-9i)", worldId, e);
            throw e;
        }
    }
}
