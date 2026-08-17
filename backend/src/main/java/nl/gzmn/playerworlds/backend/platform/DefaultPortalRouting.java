package nl.gzmn.playerworlds.backend.platform;

import java.util.Objects;

/**
 * FR-3a portal destination rules for the current layout.
 *
 * <ul>
 *   <li>Nether portal in the overworld → nether at {@code (x/scale, y, z/scale)}
 *   <li>Nether portal in the nether → overworld at {@code (x*scale, y, z*scale)}
 *   <li>End portal from the overworld → end at the same X/Z (vanilla search
 *       still runs inside that world once the target is set)
 *   <li>End portal / return from the end → overworld spawn coordinates are not
 *       decided here; the caller supplies the overworld spawn as x/y/z
 *   <li>End gateway stays inside the end
 * </ul>
 */
public final class DefaultPortalRouting implements PortalRouting {

    public static final DefaultPortalRouting INSTANCE = new DefaultPortalRouting();

    private DefaultPortalRouting() {}

    @Override
    public PortalTarget resolve(PortalRequest request, WorldLayout layout) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(layout, "layout");

        return switch (request.portalType()) {
            case NETHER -> resolveNether(request, layout);
            case END -> resolveEnd(request, layout);
            case END_GATEWAY -> resolveEndGateway(request, layout);
        };
    }

    private static PortalTarget resolveNether(PortalRequest request, WorldLayout layout) {
        return switch (request.sourceDimension()) {
            case OVERWORLD -> {
                double scale = request.netherScale();
                yield new PortalTarget(
                        layout.bukkitWorldName(request.baseFolder(), DimensionKind.NETHER),
                        DimensionKind.NETHER,
                        request.x() / scale,
                        request.y(),
                        request.z() / scale);
            }
            case NETHER -> {
                double scale = request.netherScale();
                yield new PortalTarget(
                        layout.bukkitWorldName(request.baseFolder(), DimensionKind.OVERWORLD),
                        DimensionKind.OVERWORLD,
                        request.x() * scale,
                        request.y(),
                        request.z() * scale);
            }
            case END ->
                throw new IllegalArgumentException(
                        "nether portal is not valid in the end (baseFolder=" + request.baseFolder() + ")");
        };
    }

    private static PortalTarget resolveEnd(PortalRequest request, WorldLayout layout) {
        return switch (request.sourceDimension()) {
            case OVERWORLD, NETHER ->
                new PortalTarget(
                        layout.bukkitWorldName(request.baseFolder(), DimensionKind.END),
                        DimensionKind.END,
                        request.x(),
                        request.y(),
                        request.z());
            case END ->
                new PortalTarget(
                        layout.bukkitWorldName(request.baseFolder(), DimensionKind.OVERWORLD),
                        DimensionKind.OVERWORLD,
                        request.x(),
                        request.y(),
                        request.z());
        };
    }

    private static PortalTarget resolveEndGateway(PortalRequest request, WorldLayout layout) {
        if (request.sourceDimension() != DimensionKind.END) {
            throw new IllegalArgumentException(
                    "end gateway is only valid in the end (baseFolder=" + request.baseFolder() + ")");
        }
        return new PortalTarget(
                layout.bukkitWorldName(request.baseFolder(), DimensionKind.END),
                DimensionKind.END,
                request.x(),
                request.y(),
                request.z());
    }
}
