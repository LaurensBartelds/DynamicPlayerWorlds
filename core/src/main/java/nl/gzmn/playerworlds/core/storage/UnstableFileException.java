package nl.gzmn.playerworlds.core.storage;

import java.nio.file.Path;

/**
 * A live file kept changing across every snapshot copy attempt.
 *
 * <p>MN-5a step 5: abort this sync and let the next one pick the file up.
 * Aborting is cheap; uploading a torn region is not.
 */
public final class UnstableFileException extends StorageException {

    private final Path source;

    public UnstableFileException(Path source, int attempts) {
        super("source file would not settle after " + attempts + " copy attempt(s): " + source);
        this.source = source;
    }

    public Path source() {
        return source;
    }
}
