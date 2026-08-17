package nl.gzmn.playerworlds.core.storage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Copies one regular file to a destination path (MN-5a step 4).
 *
 * <p>Implementations must produce an independent inode — hard links are forbidden
 * because an in-place region write would be visible through them and the copy
 * would silently do nothing.
 */
@FunctionalInterface
public interface FileCloner {

    /**
     * Copies {@code source} onto {@code target}, replacing any existing file.
     * Parent directories of {@code target} are created when missing.
     */
    void copy(Path source, Path target) throws IOException;
}
