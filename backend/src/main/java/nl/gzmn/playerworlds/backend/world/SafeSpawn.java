package nl.gzmn.playerworlds.backend.world;

import java.util.Objects;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * Somewhere in a world a player can be put down and be standing on something
 * (FR-5, FR-11).
 *
 * <p>{@code World#getSpawnLocation} is a stored point, not a promise about the
 * blocks around it. Its Y is whatever was written when the world was generated,
 * and it survives untouched through terrain edits, an archive round trip and a
 * restore — so a world whose spawn column has since been dug out, or whose
 * stored Y was never on the surface in the first place, drops every arrival into
 * the air. The owner rarely sees it because they arrive once, seconds after
 * generation; an invited player arrives into a world that has been lived in.
 *
 * <p>The search is deliberately small. It starts in the spawn column, walks down
 * to the first solid block, and only spreads outward when that column cannot
 * hold a player. The radius is bounded so it never asks for chunks far outside
 * the ones the teleport is about to load anyway — this runs on the tick thread
 * and a wide search would be a stall, not a safety feature.
 *
 * <p>Falls back to the location it was given. Being one block out is better than
 * refusing to place a player at all, and every caller here has nowhere else to
 * put them.
 */
public final class SafeSpawn {

    /** Rings searched outward from the spawn column, in blocks. */
    private static final int MAX_RADIUS = 8;

    /** Head room a player needs: the block at their feet and the one above it. */
    private static final int CLEARANCE = 2;

    /**
     * How far below the height map a column is followed down.
     *
     * <p>The height map counts leaves, snow and water, so the first hit is often
     * not something to stand on and a short walk down finds the real surface.
     * Bounded because this runs on the tick thread: an unbounded walk over a deep
     * ocean would read every block from the waves to the sea floor, in every
     * column of the search, for a spot nobody wants to arrive in anyway.
     */
    private static final int MAX_DESCENT = 16;

    /** Ground that is solid but that nobody should be stood on. */
    private static final Set<Material> UNSAFE_GROUND = Set.of(
            Material.MAGMA_BLOCK,
            Material.CACTUS,
            Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE,
            Material.SWEET_BERRY_BUSH,
            Material.WITHER_ROSE,
            Material.POWDER_SNOW);

    /** Passable blocks that would still hurt or drown somebody standing in them. */
    private static final Set<Material> UNSAFE_SPACE =
            Set.of(Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.POWDER_SNOW);

    private SafeSpawn() {}

    /** A standing position at or near this world's spawn point. */
    public static Location resolve(World world) {
        Objects.requireNonNull(world, "world");
        return resolve(world, world.getSpawnLocation());
    }

    /**
     * A standing position at or near {@code around}, keeping its facing.
     *
     * @return a location whose feet are on solid ground, or {@code around} when
     *     the search found nothing better
     */
    public static Location resolve(World world, Location around) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(around, "around");

        int originX = around.getBlockX();
        int originZ = around.getBlockZ();
        for (int radius = 0; radius <= MAX_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // Only the ring itself: the inner squares were searched by the
                    // smaller radii, and re-reading them re-reads chunks.
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    Location found = standingSpotAt(world, originX + dx, originZ + dz);
                    if (found != null) {
                        found.setYaw(around.getYaw());
                        found.setPitch(around.getPitch());
                        return found;
                    }
                }
            }
        }
        return around.clone();
    }

    /**
     * The standing spot in one column, or null when this column cannot hold a
     * player.
     */
    private static @Nullable Location standingSpotAt(World world, int x, int z) {
        int ceiling = world.getMaxHeight() - CLEARANCE - 1;
        int top = Math.min(world.getHighestBlockYAt(x, z), ceiling);
        int floor = Math.max(world.getMinHeight(), top - MAX_DESCENT);

        for (int y = top; y >= floor; y--) {
            Block ground = world.getBlockAt(x, y, z);
            Material type = ground.getType();
            if (type.isAir() || !type.isSolid()) {
                // The height map counts leaves, snow layers and water as the top
                // block, so the first hit is often not something to stand on.
                // Keep descending rather than giving up on the column.
                continue;
            }
            if (UNSAFE_GROUND.contains(type)) {
                return null;
            }
            if (!hasClearance(world, x, y, z)) {
                return null;
            }
            return new Location(world, x + 0.5d, y + 1.0d, z + 0.5d);
        }
        return null;
    }

    private static boolean hasClearance(World world, int x, int groundY, int z) {
        for (int offset = 1; offset <= CLEARANCE; offset++) {
            Block space = world.getBlockAt(x, groundY + offset, z);
            Material type = space.getType();
            if (UNSAFE_SPACE.contains(type)) {
                return false;
            }
            if (!type.isAir() && type.isSolid()) {
                return false;
            }
        }
        return true;
    }
}
