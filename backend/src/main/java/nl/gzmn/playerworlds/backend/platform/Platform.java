package nl.gzmn.playerworlds.backend.platform;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The selected Minecraft-version seam for this node.
 *
 * <p>Constructed once at enable from {@link ServerIdentity}. Holds the layout,
 * codecs and runtime adapters that the rest of the plugin talks to, so version
 * knowledge stays inside {@code backend.platform} (plan section 5.2).
 *
 * <p>Selection rules:
 *
 * <ul>
 *   <li>data version &lt; {@link #MIN_SUPPORTED_DATA_VERSION} → refuse to enable
 *   <li>data version &gt; {@link #BUILD_DATA_VERSION} → warn and use the default
 *       layout (unknown newer server)
 *   <li>otherwise → highest registered layout whose {@code minDataVersion} does
 *       not exceed the node's version
 * </ul>
 */
public final class Platform {

    /**
     * Chunk {@code DataVersion} observed on the Paper build this repository pins
     * (F1 acceptance: Paper {@code 26.2-112-main}). Every version decision in
     * specification section 12.9 is taken against this number on a node of this
     * build.
     */
    public static final int BUILD_DATA_VERSION = 4903;

    /**
     * Nodes below this data version refuse to enable. For this build the floor
     * equals {@link #BUILD_DATA_VERSION}: the seam was written and verified
     * against that Paper line only.
     */
    public static final int MIN_SUPPORTED_DATA_VERSION = BUILD_DATA_VERSION;

    private static final List<WorldLayout> LAYOUTS = List.of(DefaultWorldLayout.INSTANCE);

    private final ServerIdentity identity;
    private final WorldLayout worldLayout;
    private final ItemCodec itemCodec;
    private final WorldRuntime worldRuntime;
    private final WorldLifecycle worldLifecycle;
    private final PortalRouting portalRouting;
    private final boolean unknownNewerVersion;

    private Platform(
            ServerIdentity identity,
            WorldLayout worldLayout,
            ItemCodec itemCodec,
            WorldRuntime worldRuntime,
            WorldLifecycle worldLifecycle,
            PortalRouting portalRouting,
            boolean unknownNewerVersion) {
        this.identity = identity;
        this.worldLayout = worldLayout;
        this.itemCodec = itemCodec;
        this.worldRuntime = worldRuntime;
        this.worldLifecycle = worldLifecycle;
        this.portalRouting = portalRouting;
        this.unknownNewerVersion = unknownNewerVersion;
    }

    /**
     * Selects seam implementations for the running server.
     *
     * @throws UnsupportedPlatformException if the server is older than this build supports
     */
    public static Platform create(ServerIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        int dataVersion = identity.dataVersion();

        if (dataVersion < MIN_SUPPORTED_DATA_VERSION) {
            throw new UnsupportedPlatformException(
                    "chunk data version " + dataVersion + " is below the minimum supported "
                            + MIN_SUPPORTED_DATA_VERSION + " (minecraft " + identity.minecraftVersion()
                            + "); this build of gzmn-worlds refuses to enable on older servers");
        }

        boolean unknownNewer = dataVersion > BUILD_DATA_VERSION;
        WorldLayout layout = selectLayout(dataVersion);
        return new Platform(
                identity,
                layout,
                PaperItemCodec.INSTANCE,
                PaperWorldRuntime.INSTANCE,
                PaperWorldLifecycle.INSTANCE,
                DefaultPortalRouting.INSTANCE,
                unknownNewer);
    }

    static WorldLayout selectLayout(int dataVersion) {
        return LAYOUTS.stream()
                .filter(layout -> layout.minDataVersion() <= dataVersion)
                .max(Comparator.comparingInt(WorldLayout::minDataVersion))
                .orElse(DefaultWorldLayout.INSTANCE);
    }

    public ServerIdentity identity() {
        return identity;
    }

    public WorldLayout worldLayout() {
        return worldLayout;
    }

    public ItemCodec itemCodec() {
        return itemCodec;
    }

    public WorldRuntime worldRuntime() {
        return worldRuntime;
    }

    public WorldLifecycle worldLifecycle() {
        return worldLifecycle;
    }

    public PortalRouting portalRouting() {
        return portalRouting;
    }

    /**
     * True when the server is newer than {@link #BUILD_DATA_VERSION}. The default
     * layout is used and the enable path must log a warning so an operator sees
     * the mismatch before anything subtle breaks.
     */
    public boolean unknownNewerVersion() {
        return unknownNewerVersion;
    }
}
