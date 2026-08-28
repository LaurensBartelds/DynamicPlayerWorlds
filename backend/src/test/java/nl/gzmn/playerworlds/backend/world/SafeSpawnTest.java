package nl.gzmn.playerworlds.backend.world;

import static org.assertj.core.api.Assertions.assertThat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Where an arriving player is put down (FR-5, FR-11).
 *
 * <p>The bug this exists for: {@code World#getSpawnLocation} is a stored
 * coordinate, and nothing keeps it above ground once the world has been played
 * in. An owner arrives seconds after generation and never notices; an invited
 * player arrives into a world somebody has been digging in, and falls.
 */
class SafeSpawnTest {

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        // WorldMock's default terrain: grass up to y=4, air above, spawn at y=5.
        world = server.addSimpleWorld("test-world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("a player lands on top of the ground, not at the stored y")
    void landsOnTheGround() {
        world.setSpawnLocation(0, 100, 0);

        Location resolved = SafeSpawn.resolve(world);

        assertThat(resolved.getY())
                .as("the stored spawn was 95 blocks above the terrain")
                .isEqualTo(5.0d);
        assertThat(resolved.getBlockX()).isZero();
        assertThat(resolved.getBlockZ()).isZero();
    }

    @Test
    @DisplayName("the player is centred on the block rather than left on its corner")
    void centresOnTheBlock() {
        Location resolved = SafeSpawn.resolve(world);

        assertThat(resolved.getX()).isEqualTo(0.5d);
        assertThat(resolved.getZ()).isEqualTo(0.5d);
    }

    @Test
    @DisplayName("a spawn surrounded by ground nobody can stand on is answered further out")
    void spreadsOutwardWhenTheColumnIsUnusable() {
        // Three blocks of magma across the spawn: the nearest column that works
        // is two out, so the search has to actually spread rather than give up.
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                world.getBlockAt(x, 4, z).setType(Material.MAGMA_BLOCK);
            }
        }

        Location resolved = SafeSpawn.resolve(world);

        assertThat(Math.max(Math.abs(resolved.getBlockX()), Math.abs(resolved.getBlockZ())))
                .as("the magma square must not be chosen")
                .isGreaterThan(1);
        assertThat(world.getBlockAt(resolved.getBlockX(), resolved.getBlockY(), resolved.getBlockZ())
                        .getType()
                        .isAir())
                .isTrue();
    }

    @Test
    @DisplayName("nobody is stood on lava or magma")
    void refusesGroundThatWouldHurt() {
        world.getBlockAt(0, 4, 0).setType(Material.MAGMA_BLOCK);

        Location resolved = SafeSpawn.resolve(world);

        assertThat(world.getBlockAt(resolved.getBlockX(), resolved.getBlockY() - 1, resolved.getBlockZ())
                        .getType())
                .isNotEqualTo(Material.MAGMA_BLOCK);
    }

    @Test
    @DisplayName("facing survives the search")
    void keepsTheFacing() {
        Location around = new Location(world, 0, 100, 0, 42.0f, -7.0f);

        Location resolved = SafeSpawn.resolve(world, around);

        assertThat(resolved.getYaw()).isEqualTo(42.0f);
        assertThat(resolved.getPitch()).isEqualTo(-7.0f);
    }

    @Test
    @DisplayName("a world with nowhere safe within reach gets the location it was given")
    void fallsBackRatherThanRefusing() {
        // Nothing to stand on anywhere the search will look. Being one block out
        // beats refusing to place a player, because the caller has nowhere else
        // to put them.
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                for (int y = 0; y <= 4; y++) {
                    world.getBlockAt(x, y, z).setType(Material.MAGMA_BLOCK);
                }
            }
        }
        Location around = new Location(world, 0.25d, 64.0d, 0.5d);

        Location resolved = SafeSpawn.resolve(world, around);

        assertThat(resolved).isEqualTo(around);
    }
}
