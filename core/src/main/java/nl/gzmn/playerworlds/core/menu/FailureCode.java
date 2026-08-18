package nl.gzmn.playerworlds.core.menu;

import java.util.Objects;

/**
 * Standard failure categories for menu actions and operations.
 */
public enum FailureCode {
    PERMISSION_DENIED,
    WORLD_NOT_FOUND,
    PLAYER_NOT_FOUND,
    CAP_REACHED,
    QUOTA_EXCEEDED,
    BANNED,
    INVALID_NAME,
    ALREADY_EXISTS,
    ISOLATION_VIOLATION,
    SERVER_UNROUTABLE,
    STATE_CONFLICT,
    TIMEOUT,
    GENERIC_ERROR;

    public static FailureCode fromName(String name) {
        Objects.requireNonNull(name, "name");
        for (FailureCode code : values()) {
            if (code.name().equals(name)) {
                return code;
            }
        }
        throw new IllegalArgumentException("Unknown FailureCode: " + name);
    }
}
