package nl.gzmn.playerworlds.backend;

import nl.gzmn.playerworlds.backend.platform.ServerIdentity;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Paper plugin entry point. Runs on every {@code worlds} node.
 *
 * <p>No behaviour yet: the foundation provides the seams that milestone 1
 * onwards land in. What this class does do is report the node's chunk data
 * version at startup, because every version decision in section 12.9 is taken
 * against that number and an operator needs to see it before anything goes
 * wrong rather than afterwards.
 */
public final class GzmnWorldsPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        ServerIdentity identity = ServerIdentity.detect();
        getLogger()
                .info(() -> "enabled: minecraft %s, data version %d"
                        .formatted(identity.minecraftVersion(), identity.dataVersion()));
    }

    @Override
    public void onDisable() {
        getLogger().info("disabled");
    }
}
