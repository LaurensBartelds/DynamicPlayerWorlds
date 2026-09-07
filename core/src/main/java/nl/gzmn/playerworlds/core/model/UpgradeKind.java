package nl.gzmn.playerworlds.core.model;

import java.util.Locale;
import java.util.Optional;

/**
 * What a one-time {@link WorldUpgrade} raises (FR-45).
 *
 * <p>Stored as text on {@code world_upgrade.kind}, and constrained there too: an unknown
 * kind is refused by the database rather than reaching a {@code switch} that has to invent
 * a meaning for it.
 */
public enum UpgradeKind {

    /**
     * Adds {@code amount} bytes to the owner's storage allowance.
     *
     * <p>The allowance is a per-player pool over every world they own (§4) and stays one.
     * The world an upgrade is redeemed against is recorded, and shown, but the bytes are
     * not fenced to it — fencing them would mean a second quota with its own enforcement
     * point on every snapshot commit, for a problem the pool does not have.
     */
    STORAGE,

    /**
     * Raises the redeemed world's border radius by {@code amount} blocks (FR-3c).
     *
     * <p>This is the kind that needed a world in the first place: a border belongs to one
     * world, and a permission node has nowhere to name it.
     */
    BORDER;

    /**
     * Parses a stored or player-typed kind, case-insensitively.
     *
     * @param name the text to parse
     * @return the kind, or empty when it is not one
     */
    public static Optional<UpgradeKind> parse(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UpgradeKind.valueOf(name.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
