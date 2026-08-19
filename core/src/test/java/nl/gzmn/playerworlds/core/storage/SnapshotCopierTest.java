package nl.gzmn.playerworlds.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import nl.gzmn.playerworlds.core.obs.ReflinkVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotCopierTest {

    @TempDir
    Path temp;

    @Test
    @DisplayName("settled source is copied once with matching bytes")
    void settledCopySucceeds() throws Exception {
        Path live = temp.resolve("live");
        Path snap = temp.resolve("snap");
        Files.createDirectories(live);
        Path relative = Path.of("region", "r.0.0.mca");
        Path source = live.resolve(relative);
        Files.createDirectories(source.getParent());
        byte[] payload = "stable-region-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(source, payload);

        SnapshotCopier copier = new SnapshotCopier(PlainFileCloner.INSTANCE, 3);
        SnapshotCopier.CopiedFile result = copier.copyOne(live, snap, relative);

        assertThat(result.attempts()).isEqualTo(1);
        assertThat(Files.readAllBytes(result.snapshotPath())).isEqualTo(payload);
        assertThat(result.fingerprint().sizeBytes()).isEqualTo(payload.length);
    }

    @Test
    @DisplayName("file mutated mid-copy is detected and retried until it settles")
    void midCopyMutationIsRetried() throws Exception {
        Path live = temp.resolve("live");
        Path snap = temp.resolve("snap");
        Files.createDirectories(live);
        Path relative = Path.of("region", "r.0.0.mca");
        Path source = live.resolve(relative);
        Files.createDirectories(source.getParent());
        Files.writeString(source, "v0", StandardCharsets.UTF_8);

        // Mutate the live source after the first two clones so attempts 1–2 look
        // torn; attempt 3 sees a settled fingerprint.
        AtomicInteger mutationsLeft = new AtomicInteger(2);
        FileCloner mutating = (src, dst) -> {
            PlainFileCloner.INSTANCE.copy(src, dst);
            if (mutationsLeft.getAndDecrement() > 0) {
                mutateSource(src);
            }
        };

        SnapshotCopier copier = new SnapshotCopier(mutating, 3);
        SnapshotCopier.CopiedFile result = copier.copyOne(live, snap, relative);

        assertThat(result.attempts()).isEqualTo(3);
        assertThat(Files.readAllBytes(result.snapshotPath())).isEqualTo(Files.readAllBytes(source));
        assertThat(mutationsLeft.get()).isEqualTo(-1);
    }

    @Test
    @DisplayName("file that never settles aborts with UnstableFileException")
    void neverSettlesAborts() throws Exception {
        Path live = temp.resolve("live");
        Path snap = temp.resolve("snap");
        Files.createDirectories(live);
        Path relative = Path.of("data.bin");
        Path source = live.resolve(relative);
        Files.writeString(source, "x", StandardCharsets.UTF_8);

        FileCloner alwaysMutate = (src, dst) -> {
            PlainFileCloner.INSTANCE.copy(src, dst);
            mutateSource(src);
        };

        SnapshotCopier copier = new SnapshotCopier(alwaysMutate, 3);
        assertThatThrownBy(() -> copier.copyOne(live, snap, relative))
                .isInstanceOf(UnstableFileException.class)
                .hasMessageContaining("would not settle");
        assertThat(Files.exists(snap.resolve(relative))).isFalse();
    }

    @Test
    @DisplayName("copyAll preserves relative layout for several files")
    void copyAllLayout() throws Exception {
        Path live = temp.resolve("live");
        Path snap = temp.resolve("snap");
        List<Path> relatives = List.of(Path.of("a.txt"), Path.of("dir", "b.txt"));
        for (Path relative : relatives) {
            Path src = live.resolve(relative);
            Files.createDirectories(src.getParent());
            Files.writeString(src, relative.toString(), StandardCharsets.UTF_8);
        }

        SnapshotCopier copier = new SnapshotCopier(PlainFileCloner.INSTANCE);
        List<SnapshotCopier.CopiedFile> results = copier.copyAll(live, snap, relatives);

        assertThat(results).hasSize(2);
        for (SnapshotCopier.CopiedFile copied : results) {
            assertThat(Files.readString(copied.snapshotPath()))
                    .isEqualTo(copied.relative().toString());
        }
    }

    @Test
    @DisplayName("ReflinkFileCloner with FULL_COPY falls back to plain copy")
    void reflinkClonerFallsBackWhenVerdictIsFullCopy() throws Exception {
        Path live = temp.resolve("live");
        Path snap = temp.resolve("snap");
        Files.createDirectories(live);
        Path relative = Path.of("f.bin");
        Files.writeString(live.resolve(relative), "payload", StandardCharsets.UTF_8);

        FileCloner cloner = new ReflinkFileCloner(ReflinkVerdict.FULL_COPY);
        SnapshotCopier copier = new SnapshotCopier(cloner, 1);
        SnapshotCopier.CopiedFile result = copier.copyOne(live, snap, relative);

        assertThat(Files.readString(result.snapshotPath())).isEqualTo("payload");
    }

    @Test
    @DisplayName("absolute relative path is rejected")
    void absoluteRelativeRejected() {
        SnapshotCopier copier = new SnapshotCopier(PlainFileCloner.INSTANCE);
        assertThatThrownBy(() -> copier.copyOne(temp, temp.resolve("s"), temp.toAbsolutePath()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");
    }

    private static void mutateSource(Path src) throws IOException {
        byte[] current = Files.readAllBytes(src);
        byte[] next = new byte[current.length + 1];
        System.arraycopy(current, 0, next, 0, current.length);
        next[next.length - 1] = (byte) (current.length & 0xFF);
        Files.write(src, next);
        // Bump mtime so coarse-resolution filesystems still see a change even
        // if a racing reader only compared timestamps.
        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(src, FileTime.fromMillis(now + 60_000L + current.length));
    }

    @Test
    @DisplayName("a source that vanished between the scan and the copy is skipped, not fatal (MN-5a)")
    void vanishedSourceIsSkippedRatherThanAbortingTheSync() throws Exception {
        Path live = Files.createDirectories(temp.resolve("live"));
        Path snap = temp.resolve("snap");

        Path kept = live.resolve("region").resolve("r.0.0.mca");
        Files.createDirectories(kept.getParent());
        Files.writeString(kept, "region-bytes", StandardCharsets.UTF_8);

        // Named by the scan, gone by the time the copy runs. Paper writes and
        // removes transient files under data/ around every save, so this is the
        // normal case rather than an exotic one — and when it aborted the sync,
        // no snapshot ever completed and object storage stayed empty.
        Path vanished = Path.of("data", "minecraft", "chunk_tickets.dat");

        SnapshotCopier copier = new SnapshotCopier(PlainFileCloner.INSTANCE, 3);
        List<SnapshotCopier.CopiedFile> copied =
                copier.copyAll(live, snap, List.of(Path.of("region", "r.0.0.mca"), vanished));

        assertThat(copied).hasSize(1);
        assertThat(copied.get(0).relative()).isEqualTo(Path.of("region", "r.0.0.mca"));
        assertThat(Files.exists(snap.resolve("region").resolve("r.0.0.mca"))).isTrue();
        assertThat(Files.exists(snap.resolve(vanished))).isFalse();
    }

    @Test
    @DisplayName("copyOne reports a vanished source as null rather than throwing")
    void copyOneReturnsNullForAVanishedSource() throws Exception {
        Path live = Files.createDirectories(temp.resolve("live2"));
        Path snap = temp.resolve("snap2");

        assertThat(new SnapshotCopier(PlainFileCloner.INSTANCE, 3).copyOne(live, snap, Path.of("gone.dat")))
                .isNull();
    }
}
