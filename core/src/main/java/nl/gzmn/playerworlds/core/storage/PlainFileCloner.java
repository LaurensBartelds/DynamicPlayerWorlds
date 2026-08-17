package nl.gzmn.playerworlds.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Full byte-for-byte file copy. The fallback when reflink clones are unavailable
 * (ext4, Windows, missing {@code cp}) and the honest path when the startup probe
 * reported {@link nl.gzmn.playerworlds.core.obs.ReflinkVerdict#FULL_COPY}.
 */
public final class PlainFileCloner implements FileCloner {

    public static final PlainFileCloner INSTANCE = new PlainFileCloner();

    private PlainFileCloner() {}

    @Override
    public void copy(Path source, Path target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }
}
