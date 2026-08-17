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
        // Concatenation rather than a format string: %d formats through the
        // default locale, which forbidden-apis bans and which would render the
        // data version in non-ASCII digits on some hosts. The one number an
        // operator reads out of this line has to be readable everywhere.
        getLogger()
                .info(() -> "enabled: minecraft " + identity.minecraftVersion() + ", data version "
                        + identity.dataVersion());
    }

    @Override
    public void onDisable() {
        getLogger().info("disabled");
    }
}
