package nl.gzmn.playerworlds.backend.platform;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/** {@link WorldLifecycle} against the Paper API this build compiles with. */
public final class PaperWorldLifecycle implements WorldLifecycle {

    public static final PaperWorldLifecycle INSTANCE = new PaperWorldLifecycle();

    private PaperWorldLifecycle() {}

    @Override
    public @Nullable World createOrLoad(CreationRequest request) {
        Objects.requireNonNull(request, "request");
        WorldCreator creator = new WorldCreator(request.bukkitWorldName())
                .environment(environment(request.dimension()))
                .seed(request.seed())
                .generateStructures(request.generateStructures());
        // Loads rather than creates when the folder already exists, which is what
        // makes this one method rather than two: a world materialised on a
        // previous boot and one materialised on first transit take the same path.
        return Bukkit.createWorld(creator);
    }

    @Override
    public @Nullable World loaded(String bukkitWorldName) {
        Objects.requireNonNull(bukkitWorldName, "bukkitWorldName");
        return Bukkit.getWorld(bukkitWorldName);
    }

    @Override
    public boolean unload(String bukkitWorldName, boolean save) {
        Objects.requireNonNull(bukkitWorldName, "bukkitWorldName");
        World world = Bukkit.getWorld(bukkitWorldName);
        if (world == null) {
            // Already down. Reported as false so the caller's "is this world still
            // loaded" question gets an honest answer; FR-25a's retry treats an
            // absent dimension as nothing to do rather than as a failure.
            return false;
        }
        return Bukkit.unloadWorld(world, save);
    }

    @Override
    public CompletableFuture<Void> pregenerateSpawn(String bukkitWorldName, int chunkSide) {
        Objects.requireNonNull(bukkitWorldName, "bukkitWorldName");
        if (chunkSide < 1) {
            return CompletableFuture.completedFuture(null);
        }
        World world = Bukkit.getWorld(bukkitWorldName);
        if (world == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("world is not loaded: " + bukkitWorldName));
        }

        Location spawn = world.getSpawnLocation();
        int centreX = spawn.getBlockX() >> 4;
        int centreZ = spawn.getBlockZ() >> 4;
        // A side of 3 means the spawn chunk plus one ring: -1..+1. An even side
        // is biased one chunk towards the negative axis, which is harmless and
        // keeps the count exactly side².
        int low = -(chunkSide / 2);
        int high = low + chunkSide - 1;

        List<CompletableFuture<Chunk>> pending = new ArrayList<>(chunkSide * chunkSide);
        for (int x = low; x <= high; x++) {
            for (int z = low; z <= high; z++) {
                // gen = true so the chunks are actually generated; urgent = false
                // so this queues behind real player chunk loads rather than ahead
                // of them (FR-4: the point is to stop competing with the tick).
                pending.add(world.getChunkAtAsync(centreX + x, centreZ + z, true, false));
            }
        }
        return CompletableFuture.allOf(pending.toArray(new CompletableFuture<?>[0]));
    }

    @Override
    public List<String> unloadBlockers(String bukkitWorldName) {
        Objects.requireNonNull(bukkitWorldName, "bukkitWorldName");
        World world = Bukkit.getWorld(bukkitWorldName);
        if (world == null) {
            return List.of();
        }

        List<String> blockers = new ArrayList<>();

        List<Player> players = world.getPlayers();
        if (!players.isEmpty()) {
            List<String> names = new ArrayList<>(players.size());
            for (Player player : players) {
                names.add(player.getName());
            }
            blockers.add("players present: " + String.join(", ", names));
        }

        Collection<Chunk> forced = world.getForceLoadedChunks();
        if (!forced.isEmpty()) {
            blockers.add("force-loaded chunks: " + forced.size());
        }

        Map<Plugin, Collection<Chunk>> tickets = world.getPluginChunkTickets();
        for (Map.Entry<Plugin, Collection<Chunk>> entry : tickets.entrySet()) {
            blockers.add("chunk tickets held by " + entry.getKey().getName() + ": "
                    + entry.getValue().size());
        }

        return List.copyOf(blockers);
    }

    /**
     * Bukkit environment for one of FR-2's three dimensions.
     *
     * <p>The mapping is the reason this lives behind the seam: {@code THE_END} and
     * {@code NETHER} decide whether the server runs the dragon fight (FR-3b) and
     * the 8:1 portal scaling, so getting it wrong produces a world that looks
     * right and behaves like the wrong dimension.
     */
    private static World.Environment environment(DimensionKind dimension) {
        return switch (dimension) {
            case OVERWORLD -> World.Environment.NORMAL;
            case NETHER -> World.Environment.NETHER;
            case END -> World.Environment.THE_END;
        };
    }
}
