package nl.gzmn.playerworlds.backend;

import nl.gzmn.playerworlds.backend.platform.Platform;
import nl.gzmn.playerworlds.backend.platform.ServerIdentity;
import nl.gzmn.playerworlds.backend.platform.UnsupportedPlatformException;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * The Paper plugin entry point. Runs on every {@code worlds} node.
 *
 * <p>No gameplay behaviour yet: the foundation provides the seams that milestone
 * 1 onwards land in. Enable selects the Minecraft-version platform (plan section
 * 5.2), reports the chunk data version every section 12.9 decision is taken
 * against, and refuses to start on a server older than this build supports.
 */
public final class GzmnWorldsPlugin extends JavaPlugin {

    private @Nullable Platform platform;

    @Override
    public void onEnable() {
        ServerIdentity identity = ServerIdentity.detect();
        final Platform selected;
        try {
            selected = Platform.create(identity);
        } catch (UnsupportedPlatformException e) {
            getLogger().severe(() -> e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.platform = selected;

        // Concatenation rather than a format string: %d formats through the
        // default locale, which forbidden-apis bans and which would render the
        // data version in non-ASCII digits on some hosts. The one number an
        // operator reads out of this line has to be readable everywhere.
        getLogger()
                .info(() -> "enabled: minecraft "
                        + identity.minecraftVersion()
                        + ", data version "
                        + identity.dataVersion()
                        + ", world layout "
                        + selected.worldLayout().id());

        if (selected.unknownNewerVersion()) {
            getLogger()
                    .warning(() -> "server data version "
                            + identity.dataVersion()
                            + " is newer than this build's verified version "
                            + Platform.BUILD_DATA_VERSION
                            + "; using layout "
                            + selected.worldLayout().id()
                            + ". Check for a gzmn-worlds release built against this Paper line.");
        }
    }

    /**
     * The selected platform seam, or {@code null} when enable refused.
     * Milestone code takes dependencies from here rather than constructing
     * adapters itself.
     */
    public @Nullable Platform platform() {
        return platform;
    }

    @Override
    public void onDisable() {
        platform = null;
        getLogger().info("disabled");
    }
}
