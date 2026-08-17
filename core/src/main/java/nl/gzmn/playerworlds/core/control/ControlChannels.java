package nl.gzmn.playerworlds.core.control;

import java.util.Objects;

/**
 * PostgreSQL {@code LISTEN}/{@code NOTIFY} channel names (CP-2, CP-7).
 *
 * <p>Node-directed traffic is per target ({@code gzmn_node_<id>}) so only the
 * addressed node wakes. Proxy-directed traffic shares {@link #PROXY} so every
 * proxy can wake and then filter on {@code target_node}; the durable row still
 * names one proxy.
 */
public final class ControlChannels {

    /** Reverse direction: node → proxy (CP-7). */
    public static final String PROXY = "gzmn_proxy";

    private static final String NODE_PREFIX = "gzmn_node_";

    private ControlChannels() {}

    /** Channel a node listens on and producers notify for that node. */
    public static String forNode(String nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        if (nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        return NODE_PREFIX + nodeId;
    }

    /** Whether {@code channel} is the shared proxy channel. */
    public static boolean isProxy(String channel) {
        return PROXY.equals(channel);
    }
}
