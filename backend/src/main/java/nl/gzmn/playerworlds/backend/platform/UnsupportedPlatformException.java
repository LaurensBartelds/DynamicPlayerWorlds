package nl.gzmn.playerworlds.backend.platform;

/**
 * This node runs a Minecraft version the build does not support.
 *
 * <p>Thrown when {@link Platform#create(ServerIdentity)} sees a chunk data
 * version below {@link Platform#MIN_SUPPORTED_DATA_VERSION}. The enable path
 * catches it, logs, and disables — running against an older server would mean
 * the seam implementations were never verified there.
 */
public final class UnsupportedPlatformException extends RuntimeException {

    public UnsupportedPlatformException(String message) {
        super(message);
    }
}
