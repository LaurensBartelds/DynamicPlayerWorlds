package nl.gzmn.playerworlds.backend;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

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
    @DisplayName("JDBC is never touched from the backend directly (CONTRIBUTING.md rule 3)")
    void databaseAccessGoesThroughCore() {
        ArchRule rule = noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("java.sql..", "javax.sql..", "com.zaxxer.hikari..")
                .because("every database call is asynchronous and lives behind a repository in :core, "
                        + "which is what keeps NFR-2 a property of the structure rather than of each "
                        + "caller remembering");

        rule.check(backend);
    }
}
