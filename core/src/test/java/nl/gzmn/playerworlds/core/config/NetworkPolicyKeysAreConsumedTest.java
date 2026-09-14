package nl.gzmn.playerworlds.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan 05 section 6: "a config key nothing reads failing the build".
 *
 * <p>{@link NetworkPolicy} is a wide record that grows by adding a {@code KEY_*} constant, a
 * {@code DEFAULT_*} constant, a read in {@code fromRaw} and a record component — four steps, and
 * it is easy to do the first three and never do the fourth: wire the value in somewhere that
 * actually changes behaviour. That is exactly what happened to
 * {@code worlds.public.browse-page-size} before this test existed: the key, default and accessor
 * all existed, {@code ConfigValidator} did not even check it, and {@code /world browse} returned
 * every public world on the network in one unbounded listing regardless of what an operator set
 * it to. Reverting the {@code WorldActions#browse} fix that consults {@link
 * NetworkPolicy#browsePageSize()} is enough to watch this test fail.
 *
 * <p>This is a source scan rather than an ArchUnit rule because no single module's test
 * classpath ever sees {@code :core}, {@code :backend} and {@code :proxy} compiled together —
 * {@code :backend} and {@code :proxy} are siblings that depend on {@code :core} but not on each
 * other, and {@code :core} must not depend on either (CONTRIBUTING.md rule 2). A plain text scan
 * of the checked-out source has no such boundary.
 *
 * <p>A call from {@link ConfigValidator} does not count as a reader: that class exists to check a
 * value is internally consistent with the others, not to act on it, and counting it would have
 * hidden the one real gap this test still tracks — see {@link #KNOWN_GAPS}.
 */
class NetworkPolicyKeysAreConsumedTest {

    /**
     * Accessors with a real reader that consults them: {@link ConfigValidator} checks {@code
     * storage.cold-load-budget-seconds} is strictly below the holding timeout (FR-11/NFR-1's
     * relationship, see {@code NetworkPolicy.DEFAULT_HOLDING_TIMEOUT}'s Javadoc), but nothing
     * downstream of that actually bounds a cold load by it — {@code
     * WorldLifecycleService#materialiseExisting} calls {@code WorldDownloader#materialize}
     * un-timed, relying entirely on the outer holding timeout ({@code
     * transfers.holding-timeout-seconds}) via {@code TransferJoinListener}'s deadline to eventually
     * evict a stuck join. That outer bound is a real safety net, so this is not a correctness bug
     * today, but it means an operator who lowers {@code storage.cold-load-budget-seconds} changes
     * nothing about how long a stalled object-store fetch is allowed to run before the wider
     * timeout catches it. Tracked here rather than silently allowed everywhere, so shrinking this
     * set is the signal that {@code coldLoadBudget()} has gained a real caller.
     */
    private static final Set<String> KNOWN_GAPS = Set.of("coldLoadBudget");

    private static final Set<String> EXCLUDED_FILES = Set.of("NetworkPolicy.java", "ConfigValidator.java");

    @Test
    @DisplayName("every NetworkPolicy accessor has a reader outside NetworkPolicy/ConfigValidator, or is a tracked gap")
    void everyAccessorHasAReaderOrIsATrackedGap() throws IOException {
        Path repoRoot = findRepoRoot();
        String haystack = readAllJavaSourceExcept(repoRoot, EXCLUDED_FILES);

        List<String> unread = new ArrayList<>();
        for (RecordComponent component : NetworkPolicy.class.getRecordComponents()) {
            String call = "." + component.getName() + "(";
            if (!haystack.contains(call)) {
                unread.add(component.getName());
            }
        }

        assertThat(unread)
                .as("NetworkPolicy accessor(s) with no reader outside NetworkPolicy.java/ConfigValidator.java. "
                        + "If this is new, either wire the key in somewhere real or remove it. If it is "
                        + "coldLoadBudget, it belongs in KNOWN_GAPS already; if the failure is that a "
                        + "KNOWN_GAPS entry now HAS a reader, shrink KNOWN_GAPS instead of this list.")
                .containsExactlyInAnyOrderElementsOf(KNOWN_GAPS);
    }

    private static String readAllJavaSourceExcept(Path repoRoot, Set<String> excludedFileNames) throws IOException {
        StringBuilder combined = new StringBuilder();
        for (String module : List.of("core", "backend", "proxy")) {
            Path srcDir = repoRoot.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(srcDir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(srcDir)) {
                for (Path file :
                        files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    if (excludedFileNames.contains(file.getFileName().toString())) {
                        continue;
                    }
                    combined.append(Files.readString(file)).append('\n');
                }
            }
        }
        return combined.toString();
    }

    /** Walks up from the working directory to the checkout root, marked by {@code settings.gradle.kts}. */
    private static Path findRepoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle.kts"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not find repo root (no settings.gradle.kts in any parent directory of "
                + Path.of("").toAbsolutePath() + ")");
    }
}
