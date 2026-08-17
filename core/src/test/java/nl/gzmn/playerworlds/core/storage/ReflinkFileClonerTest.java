package nl.gzmn.playerworlds.core.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import nl.gzmn.playerworlds.core.obs.ReflinkVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReflinkFileClonerTest {

    @TempDir
    Path temp;

    @Test
    @DisplayName("FULL_COPY never attempts reflink and uses the fallback")
    void fullCopyUsesFallbackOnly() throws Exception {
        Path source = temp.resolve("src.bin");
        Path target = temp.resolve("dst.bin");
        Files.writeString(source, "data", StandardCharsets.UTF_8);

        AtomicBoolean fallbackUsed = new AtomicBoolean(false);
        FileCloner fallback = (s, t) -> {
            fallbackUsed.set(true);
            PlainFileCloner.INSTANCE.copy(s, t);
        };

        ReflinkFileCloner cloner = new ReflinkFileCloner(ReflinkVerdict.FULL_COPY, fallback);
        cloner.copy(source, target);

        assertThat(fallbackUsed).isTrue();
        assertThat(Files.readString(target)).isEqualTo("data");
    }

    @Test
    @DisplayName("UNKNOWN never attempts reflink and uses the fallback")
    void unknownUsesFallbackOnly() throws Exception {
        Path source = temp.resolve("src.bin");
        Path target = temp.resolve("dst.bin");
        Files.writeString(source, "data", StandardCharsets.UTF_8);

        AtomicBoolean fallbackUsed = new AtomicBoolean(false);
        FileCloner fallback = (s, t) -> {
            fallbackUsed.set(true);
            PlainFileCloner.INSTANCE.copy(s, t);
        };

        new ReflinkFileCloner(ReflinkVerdict.UNKNOWN, fallback).copy(source, target);
        assertThat(fallbackUsed).isTrue();
    }

    @Test
    @DisplayName("REFLINK falls back to plain copy when cp is unavailable")
    void reflinkFallsBackWhenCpMissing() throws Exception {
        Path source = temp.resolve("src.bin");
        Path target = temp.resolve("dst.bin");
        Files.writeString(source, "payload", StandardCharsets.UTF_8);

        // On Windows CI without GNU cp, tryReflink fails to launch and falls back.
        // On XFS/btrfs with cp, the clone may succeed — either way the target
        // must equal the source and no exception escapes.
        ReflinkFileCloner cloner = new ReflinkFileCloner(ReflinkVerdict.REFLINK);
        cloner.copy(source, target);
        assertThat(Files.readAllBytes(target)).isEqualTo(Files.readAllBytes(source));
    }
}
