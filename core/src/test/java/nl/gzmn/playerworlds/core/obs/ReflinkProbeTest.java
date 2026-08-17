package nl.gzmn.playerworlds.core.obs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReflinkProbeTest {

    @TempDir
    Path temp;

    @Test
    @DisplayName("probe returns a definite verdict without throwing")
    void returnsVerdict() {
        ReflinkVerdict verdict = ReflinkProbe.probe(temp);
        assertThat(verdict).isIn(ReflinkVerdict.REFLINK, ReflinkVerdict.FULL_COPY, ReflinkVerdict.UNKNOWN);
        // On Windows CI without GNU cp this is FULL_COPY; on XFS/btrfs with cp it
        // may be REFLINK. Either way the wire token is stable for logs.
        assertThat(verdict.wire()).isNotBlank();
    }
}
