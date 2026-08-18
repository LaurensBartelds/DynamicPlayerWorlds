package nl.gzmn.playerworlds.backend;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Backend-side architecture rules. The two that matter most here are the
 * version seam and the {@code World} reference lifetime, because both fail
 * quietly: the first shows up as an expensive Minecraft upgrade months later,
 * the second as a memory leak plus a world that is silently the wrong one.
 */
class ArchitectureTest {

    private static JavaClasses backend;

    @BeforeAll
    static void importClasses() {
        backend = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("nl.gzmn.playerworlds.backend");
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

        rule.check(backend);
    }

    @Test
    @DisplayName("only backend.platform touches unstable API surfaces (plan section 5.2)")
    void versionSensitiveApisStayBehindTheSeam() {
        ArchRule rule = noClasses()
                .that()
                .resideOutsideOfPackage("nl.gzmn.playerworlds.backend.platform..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.bukkit.UnsafeValues")
                .because("the data version and other unstable surfaces are read in exactly one place, "
                        + "so the code that changes on a Minecraft upgrade is small and findable");

        rule.allowEmptyShould(true).check(backend);
    }

    @Test
    @DisplayName("no World reference is ever stored in a field (FR-25b)")
    void worldsAreNeverCachedAcrossAnUnload() {
        ArchRule rule = noFields()
                .should()
                .haveRawType(World.class)
                .because("a World reference does not survive an unload (FR-25b). Resolve by name or "
                        + "UUID through Bukkit.getWorld at use time, every time — holding one leaks "
                        + "the old world and silently operates on the wrong instance after a reload");

        rule.allowEmptyShould(true).check(backend);
    }

    @Test
    @DisplayName("the wall clock is never read (CONTRIBUTING.md rule 5, MN-10b)")
    void noWallClockReads() {
        // The rule that used to exist only for core.db, which is how it was
        // broken twice: WorldLifecycleService compared lease_expires to
        // Instant.now() to decide whether to re-acquire a lease, and the proxy
        // did the same at /world join. Every lease safety property in section
        // 12.3 is a timestamp comparison between machines whose clocks drift
        // independently, and the answer has to come from the database.
        //
        // Deliberately the whole module rather than the lease packages: the two
        // sites that got this wrong were in the world and command packages, and a
        // rule scoped to where the mistake was already made prevents nothing. The
        // sanctioned alternatives are DbClock for database time and
        // DbClock.elapsedSince over System.nanoTime for elapsed time, which is
        // monotonic and therefore not a clock.
        ArchRule rule = noClasses()
                .should()
                .callMethod(System.class, "currentTimeMillis")
                .orShould()
                .callMethod(java.time.Instant.class, "now")
                .orShould()
                .callMethod(java.time.LocalDateTime.class, "now")
                .because("node clocks drift and every lease decision is a timestamp comparison; "
                        + "read database time through DbClock, and elapsed time through "
                        + "DbClock.elapsedSince (CONTRIBUTING.md rule 5, MN-10b)");

        rule.allowEmptyShould(true).check(backend);
    }

    @Test
    @DisplayName("JDBC is never touched from the backend directly (CONTRIBUTING.md rule 3)")
    void databaseAccessGoesThroughCore() {
        ArchRule rule = noClasses()
                .should()
                .dependOnClassesThat(new DescribedPredicate<JavaClass>("are JDBC types other than SQLException") {
                    @Override
                    public boolean test(JavaClass type) {
                        if (type.getPackageName().startsWith("com.zaxxer.hikari")) {
                            return true;
                        }
                        if (!type.getPackageName().startsWith("java.sql")
                                && !type.getPackageName().startsWith("javax.sql")) {
                            return false;
                        }
                        // SQLException is the one JDBC type that legitimately
                        // crosses the module boundary: :core's repositories declare
                        // it, so any caller must name it to handle a failure. What
                        // the rule is protecting against is a backend class holding
                        // a Connection, Statement, ResultSet or DataSource — that
                        // is what "doing JDBC" means, and all of it stays banned.
                        return !type.getName().equals("java.sql.SQLException");
                    }
                })
                .because("every database call is asynchronous and lives behind a repository in :core, "
                        + "which is what keeps NFR-2 a property of the structure rather than of each "
                        + "caller remembering. A repository method that needs to compose several "
                        + "statements into one transaction exposes a Connection-free overload rather "
                        + "than handing the Connection out of the module");

        rule.check(backend);
    }
}
