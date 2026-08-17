package nl.gzmn.playerworlds.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules in CONTRIBUTING.md that a compiler cannot express, enforced as
 * ordinary tests so they fail in the same place as everything else.
 *
 * <p>These are not style preferences. Each one, if broken, produces a defect
 * that is expensive or impossible to detect later: a Minecraft upgrade that
 * cannot be done cheaply, a main thread stalled on IO, or a lease decision made
 * against a drifting clock.
 */
class ArchitectureTest {

    private static JavaClasses core;

    @BeforeAll
    static void importClasses() {
        core = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("nl.gzmn.playerworlds.core");
    }

    @Test
    @DisplayName("core never depends on Paper, Bukkit or Velocity (ADR 0004)")
    void coreIsPlatformIndependent() {
        ArchRule rule = noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.bukkit..", "io.papermc..", "com.velocitypowered..", "com.destroystokyo..")
                .because("core must be testable without booting a Minecraft server, and keeping the "
                        + "version-sensitive surface inside backend/platform is what makes a Minecraft "
                        + "upgrade cheap (CONTRIBUTING.md rule 2)");

        rule.check(core);
    }

    @Test
    @DisplayName("server internals are never referenced (CONTRIBUTING.md rule 1)")
    void noServerInternals() {
        ArchRule rule = noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("net.minecraft..", "org.bukkit.craftbukkit..")
                .because("internals are the single largest tax on a Minecraft upgrade; if the API "
                        + "genuinely cannot do it, report that rather than adding reflection");

        rule.check(core);
    }

    @Test
    @DisplayName("JDBC stays inside core.db (CONTRIBUTING.md rule 3)")
    void jdbcIsConfinedToTheDatabasePackage() {
        ArchRule rule = noClasses()
                .that()
                .resideOutsideOfPackage("nl.gzmn.playerworlds.core.db..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("java.sql..", "javax.sql..", "com.zaxxer.hikari..")
                .because("every database call is asynchronous and goes through a repository, so that "
                        + "'no blocking JDBC on the main thread' is a property of the structure "
                        + "rather than of each caller remembering");

        rule.check(core);
    }

    @Test
    @DisplayName("lease decisions use database time, never a local clock (MN-10b)")
    void leaseCodeDoesNotReadTheLocalClock() {
        ArchRule rule = noClasses()
                .that()
                .resideInAnyPackage(
                        "nl.gzmn.playerworlds.core.db..",
                        "nl.gzmn.playerworlds.core.lease..",
                        "nl.gzmn.playerworlds.core.storage..")
                .should()
                .callMethod(System.class, "currentTimeMillis")
                .orShould()
                .callMethod(java.time.Instant.class, "now")
                .orShould()
                .callMethod(java.time.LocalDateTime.class, "now")
                .because("every lease safety property is a timestamp comparison and node clocks drift; "
                        + "use DbClock, and derive MN-10b's self-fence deadline from a lease_expires "
                        + "value the database issued (CONTRIBUTING.md rule 5)");

        rule.allowEmptyShould(true).check(core);
    }
}
