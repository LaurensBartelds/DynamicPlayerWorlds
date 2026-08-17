package nl.gzmn.playerworlds.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import org.slf4j.Logger;

/**
 * The Velocity plugin entry point. Owns every management command in section 6
 * except {@code /world leave} and {@code /world report}, membership lookups,
 * placement and the handoff into a node.
 *
 * <p>Commands live here rather than on the backend because a world is unloaded
 * most of the time (FR-25), so its owner is usually somewhere else on the
 * network and a backend-registered command would be unreachable exactly when it
 * is most needed.
 *
 * <p>No behaviour yet; see the foundation plan.
 */
@Plugin(
        id = "gzmn-worlds-proxy",
        name = "gzmn-worlds-proxy",
        version = "0.1.0-SNAPSHOT",
        description = "Private per-player worlds for the GZMN network",
        authors = {"GZMN"})
public final class GzmnWorldsProxyPlugin {

    private final Logger logger;

    @Inject
    public GzmnWorldsProxyPlugin(Logger logger) {
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logger.info("enabled");
    }
}
