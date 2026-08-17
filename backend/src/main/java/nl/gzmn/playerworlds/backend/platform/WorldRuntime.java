package nl.gzmn.playerworlds.backend.platform;

import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.boss.DragonBattle;
import org.jspecify.annotations.Nullable;

/**
 * Version-sensitive operations on a loaded Bukkit world.
 *
 * <p>Callers pass a {@link World} obtained at the point of use — never a field
 * held across an unload (FR-25b). This interface is the place border, save,
 * gamerule and dragon-fight calls live so a Paper rename is one package to
 * update rather than a search across the plugin.
 */
public interface WorldRuntime {

    /**
     * Applies the FR-3 border for one dimension. Overworld and end use
     * {@code borderRadius} as the radius in blocks; nether uses
     * {@code borderRadius / netherBorderDivisor} so the two line up in world
     * coordinates. Re-asserted on every load because a border is persisted in
     * {@code level.dat} and must not be trusted after a restore.
     *
     * @param borderRadius overworld/end radius in blocks (diameter is 2×)
     * @param netherBorderDivisor typically 8
     */
    void applyBorder(World world, DimensionKind dimension, int borderRadius, int netherBorderDivisor);

    /**
     * FR-25c: ensure the world does not keep a permanent spawn-chunk tax while
     * idle. On Paper 26.2 / Minecraft 1.21.9+ vanilla no longer has always-loaded
     * spawn chunks, so the default implementation is a documented no-op; the
     * method stays so an older layout can still do real work and callers keep
     * expressing the intent.
     */
    void disableAlwaysLoadedSpawnChunks(World world);

    /** MN-5a step 1 / 6: auto-save off around a snapshot, restored afterwards. */
    void setAutoSave(World world, boolean enabled);

    /** Whether periodical auto-save is currently on. */
    boolean isAutoSave(World world);

    /**
     * MN-5a step 2: force a save on the main thread. The upload that follows
     * must read from the snapshot copy, never from the live folder.
     */
    void save(World world);

    /** FR-9e mob-griefing setting, applied as a gamerule on every load. */
    void setMobGriefing(World world, boolean allowed);

    /** FR-9e PVP flag for this world. */
    void setPvp(World world, boolean allowed);

    /**
     * Typed gamerule write. Prefer this over the string form so a renamed rule
     * is a compile failure.
     */
    <T> void setGameRule(World world, GameRule<T> rule, T value);

    /**
     * Ender dragon battle state for the end dimension (FR-3b). Null when the
     * world is not an end world or has no battle.
     */
    @Nullable
    DragonBattle dragonBattle(World world);
}
