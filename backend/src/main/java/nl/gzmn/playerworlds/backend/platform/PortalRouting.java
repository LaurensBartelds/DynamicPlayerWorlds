package nl.gzmn.playerworlds.backend.platform;

/**
 * Destination math for portals inside one player world (FR-3a).
 *
 * <p>Bukkit's default portal search resolves against the server's primary world,
 * so without an explicit target a nether portal in a player world lands in the
 * wrong dimension or another player's world. Coordinate scaling (8:1 overworld
 * to nether) and the end/return portal targets are computed here; event handlers
 * in a later milestone only supply the request and apply the result to
 * {@code PlayerPortalEvent} / {@code EntityPortalEvent}.
 *
 * <p>Pure functions on purpose: portal surfaces have changed shape before, and
 * the routing rules are unit-testable without booting a server.
 */
public interface PortalRouting {

    /** Kind of portal transit FR-3a has to route. */
    enum PortalType {
        NETHER,
        END,
        END_GATEWAY
    }

    /**
     * Where a portal transit should land.
     *
     * @param bukkitWorldName target Bukkit world name (not yet required to be loaded)
     * @param dimension target dimension kind
     * @param x scaled X
     * @param y Y unchanged by nether scaling
     * @param z scaled Z
     */
    record PortalTarget(String bukkitWorldName, DimensionKind dimension, double x, double y, double z) {
        public PortalTarget {
            java.util.Objects.requireNonNull(bukkitWorldName, "bukkitWorldName");
            java.util.Objects.requireNonNull(dimension, "dimension");
        }
    }

    /**
     * A portal transit to resolve.
     *
     * @param baseFolder overworld folder ({@code WorldId#folder()})
     * @param sourceDimension dimension the entity is leaving
     * @param portalType which portal was used
     * @param x source X
     * @param y source Y
     * @param z source Z
     * @param netherScale overworld-to-nether divisor (default 8)
     */
    record PortalRequest(
            String baseFolder,
            DimensionKind sourceDimension,
            PortalType portalType,
            double x,
            double y,
            double z,
            int netherScale) {
        public PortalRequest {
            java.util.Objects.requireNonNull(baseFolder, "baseFolder");
            java.util.Objects.requireNonNull(sourceDimension, "sourceDimension");
            java.util.Objects.requireNonNull(portalType, "portalType");
            if (baseFolder.isBlank()) {
                throw new IllegalArgumentException("baseFolder must not be blank");
            }
            if (netherScale < 1) {
                throw new IllegalArgumentException("netherScale must be at least 1, was: " + netherScale);
            }
        }
    }

    /**
     * Resolves the target world name and coordinates for a portal transit.
     *
     * @throws IllegalArgumentException if the portal type is not valid from the
     *     source dimension (for example a nether portal fired inside the end)
     */
    PortalTarget resolve(PortalRequest request, WorldLayout layout);
}
