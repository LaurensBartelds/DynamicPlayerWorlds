package nl.gzmn.playerworlds.proxy.permission;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import java.util.Objects;
import java.util.Set;

/**
 * Single permission semantic for every {@code /world} entry point (D14 / R5).
 *
 * <p>Brigadier {@code .requires(...)} only hides a subcommand from tab completion.
 * The GUI path ({@code MenuChannelListener} → {@code WorldActions}) never sees that
 * tree, so the real gate lives on the action. Both surfaces must call this helper.
 *
 * <p><strong>UNDEFINED is denied</strong>, matching Velocity's {@code hasPermission}
 * and FR-9h's reading that an ungranted node is not an open door. Two permissions
 * ship granted by default for ordinary players — {@code gzmn.worlds.create} and
 * {@code gzmn.worlds.join} — so a network without a permission plugin still lets
 * players create and join. {@code gzmn.worlds.public} deliberately does not: a
 * public world admits strangers onto a node of private tenants.
 */
public final class WorldPermissions {

    /** FR-1 — create a world. Default: granted. */
    public static final String CREATE = "gzmn.worlds.create";

    /** FR-10 — join / accept / browse. Default: granted. */
    public static final String JOIN = "gzmn.worlds.join";

    /** FR-9h — make a world public. Default: denied. */
    public static final String PUBLIC = "gzmn.worlds.public";

    /** Section 6 admin namespace. Default: denied. */
    public static final String ADMIN = "gzmn.worlds.admin";

    /** FR-37 — permanent delete of an archived world. Default: denied. */
    public static final String HARD_DELETE = "gzmn.worlds.delete.hard";

    /**
     * Permissions that evaluate to allowed when Velocity reports {@link Tristate#UNDEFINED}.
     * An explicit {@link Tristate#FALSE} from a permission plugin still denies them.
     */
    private static final Set<String> DEFAULT_GRANTED = Set.of(CREATE, JOIN);

    private WorldPermissions() {}

    /**
     * Whether {@code source} may perform the action gated by {@code permission}.
     *
     * <ul>
     *   <li>{@link Tristate#TRUE} → allow
     *   <li>{@link Tristate#FALSE} → deny
     *   <li>{@link Tristate#UNDEFINED} → allow only if the permission is in the
     *       default-granted set (create, join); otherwise deny
     * </ul>
     */
    public static boolean allows(CommandSource source, String permission) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(permission, "permission");
        Tristate value = source.getPermissionValue(permission);
        return switch (value) {
            case TRUE -> true;
            case FALSE -> false;
            case UNDEFINED -> DEFAULT_GRANTED.contains(permission);
        };
    }

    /** Whether {@code permission} is one of the nodes granted when unset. */
    public static boolean defaultGranted(String permission) {
        Objects.requireNonNull(permission, "permission");
        return DEFAULT_GRANTED.contains(permission);
    }
}
