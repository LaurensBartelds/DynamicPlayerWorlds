package nl.gzmn.playerworlds.proxy;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proxy-side architecture rules.
 *
 * <p>The proxy had none, which is part of why the clock rule below was broken
 * here as well as on a node: {@code core}'s version of it covers {@code core.db}
 * and nothing else, so the two components that actually take lease decisions were
 * both outside it.
 */
class ArchitectureTest {

    private static JavaClasses proxy;

    @BeforeAll
    static void importClasses() {
        proxy = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("nl.gzmn.playerworlds.proxy");
    }

    @Test
    @DisplayName("the wall clock is never read (CONTRIBUTING.md rule 5, MN-10b)")
    void noWallClockReads() {
        // /world join compared lease_expires to Instant.now() to decide whether
        // the world already held a live lease, and therefore whether to acquire
        // one. That comparison decides where a player is routed, between three
        // machines whose clocks drift independently; it belongs in the database,
        // which is where PlayerWorldRepository.placementContext now puts it.
        //
        // System.nanoTime is not covered and is not meant to be: it is monotonic
        // and measures elapsed time, which is what DbClock.elapsedSince wraps.
        ArchRule rule = noClasses()
                .should()
                .callMethod(System.class, "currentTimeMillis")
                .orShould()
                .callMethod(java.time.Instant.class, "now")
                .orShould()
                .callMethod(java.time.LocalDateTime.class, "now")
                .because("node clocks drift and every lease decision is a timestamp comparison; "
                        + "lease liveness is read in database time (CONTRIBUTING.md rule 5, MN-10b)");

        rule.allowEmptyShould(true).check(proxy);
    }

    @Test
    @DisplayName("JDBC stays inside :core (CONTRIBUTING.md rule 3)")
    void jdbcStaysInCore() {
        // SQLException is excepted for the same reason it is on the backend:
        // core's repositories declare it, so every caller has to name it.
        ArchRule rule = noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.sql.Connection")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.sql.PreparedStatement")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.sql.ResultSet")
                .orShould()
                .dependOnClassesThat()
                .resideInAPackage("com.zaxxer.hikari..")
                .because("every database call goes through a repository, so 'no blocking JDBC on a "
                        + "latency-sensitive path' is a property of the structure rather than of each caller");

        rule.allowEmptyShould(true).check(proxy);
    }
}
