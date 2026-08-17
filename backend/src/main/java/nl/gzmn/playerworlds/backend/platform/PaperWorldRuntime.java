package nl.gzmn.playerworlds.backend.platform;

import java.util.Objects;
import org.bukkit.GameRule;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.boss.DragonBattle;
import org.jspecify.annotations.Nullable;

/**
 * {@link WorldRuntime} against the Paper API this build compiles with.
 *
 * <p>Border size is the full diameter ({@code 2 * radius}) centred on spawn,
 * matching how operators read {@code border_radius} in the database. Spawn-chunk
 * disabling is a no-op on this API line — see
 * {@link #disableAlwaysLoadedSpawnChunks(World)}.
 */
public final class PaperWorldRuntime implements WorldRuntime {

    public static final PaperWorldRuntime INSTANCE = new PaperWorldRuntime();

    private PaperWorldRuntime() {}

    @Override
    public void applyBorder(World world, DimensionKind dimension, int borderRadius, int netherBorderDivisor) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(dimension, "dimension");
        if (borderRadius < 1) {
            throw new IllegalArgumentException("borderRadius must be at least 1, was: " + borderRadius);
        }
        if (netherBorderDivisor < 1) {
            throw new IllegalArgumentException("netherBorderDivisor must be at least 1, was: " + netherBorderDivisor);
        }

        int radius = dimension == DimensionKind.NETHER ? borderRadius / netherBorderDivisor : borderRadius;
        if (radius < 1) {
            radius = 1;
        }

        WorldBorder border = world.getWorldBorder();
        // Centre on the current spawn so a restore that moved spawn still gets a
        // border around where players actually arrive (FR-3).
        border.setCenter(world.getSpawnLocation());
        border.setSize(radius * 2.0d);
    }

    @Override
    public void disableAlwaysLoadedSpawnChunks(World world) {
        Objects.requireNonNull(world, "world");
        // No-op since Minecraft 1.21.9 / Paper 26.x: World#setKeepSpawnInMemory is
        // deprecated and empty because vanilla dropped always-loaded spawn chunks.
        // FR-25c's goal ("idle world costs no meaningful tick time") is therefore
        // the platform default. Kept as an explicit step so a future API that
        // reintroduces the tax is one method to fill in.
    }

    @Override
    public void setAutoSave(World world, boolean enabled) {
        Objects.requireNonNull(world, "world");
        world.setAutoSave(enabled);
    }

    @Override
    public boolean isAutoSave(World world) {
        Objects.requireNonNull(world, "world");
        return world.isAutoSave();
    }

    @Override
    public void save(World world) {
        Objects.requireNonNull(world, "world");
        world.save();
    }

    @Override
    public void setMobGriefing(World world, boolean allowed) {
        setGameRule(world, GameRules.MOB_GRIEFING, allowed);
    }

    @Override
    public void setPvp(World world, boolean allowed) {
        // GameRules.PVP replaced World#setPVP in 1.21.9; keep the intent on the
        // seam so a further rename is one call site.
        setGameRule(world, GameRules.PVP, allowed);
    }

    @Override
    public <T> void setGameRule(World world, GameRule<T> rule, T value) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(value, "value");
        world.setGameRule(rule, value);
    }

    @Override
    public @Nullable DragonBattle dragonBattle(World world) {
        Objects.requireNonNull(world, "world");
        return world.getEnderDragonBattle();
    }
}
