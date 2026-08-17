import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("gzmn.java-conventions")
    id("com.diffplug.spotless")
    id("net.ltgt.errorprone")
    id("de.thetaphi.forbiddenapis")
    id("app.cash.licensee")
}

val libs = versionCatalogs.named("libs")

// Formatting is never a review topic. `./gradlew spotlessApply` fixes it.
spotless {
    java {
        target("src/*/java/**/*.java")
        palantirJavaFormat(libs.findVersion("palantirFormat").orElseThrow().requiredVersion)
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

dependencies {
    errorprone(libs.findLibrary("errorprone-core").orElseThrow())
    errorprone(libs.findLibrary("nullaway").orElseThrow())
}

// NullAway is the compensation for choosing Java over Kotlin (ADR 0003). The
// Bukkit API returns null from places that matter — Bukkit.getWorld above all,
// which FR-25b requires be re-resolved at every use — so a forgotten null check
// is a compile error rather than a review note.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        disableWarningsInGeneratedCode.set(true)
        error("NullAway")
        option("NullAway:AnnotatedPackages", "nl.gzmn.playerworlds")
        // Platform types from these come back unannotated; check at the boundary.
        option("NullAway:UnannotatedSubPackages", "nl.gzmn.playerworlds.generated")
    }
}

tasks.named<JavaCompile>("compileTestJava") {
    options.errorprone {
        // Test code asserts on nulls deliberately.
        disable("NullAway")
    }
}

// Server internals are the single largest tax on a Minecraft upgrade, and the
// cheapest moment to refuse them is before any exist. See CONTRIBUTING.md rule 1.
// Configured on the tasks rather than through the `forbiddenApis` extension:
// the extension propagates signaturesFiles to the tasks but not
// bundledSignatures, which fails silently — the build passes having checked
// nothing. Verified by reading the task log for "Reading bundled API
// signatures"; see the note in config/forbidden-apis/internals.txt.
tasks.withType<de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis>().configureEach {
    bundledSignatures = setOf("jdk-unsafe", "jdk-deprecated", "jdk-non-portable")
    signaturesFiles = files(rootProject.file("config/forbidden-apis/internals.txt"))
    // Signature entries naming classes absent from the compile classpath are
    // expected: net.minecraft is never on it, which is the point.
    ignoreSignaturesOfMissingClasses = true
    failOnUnsupportedJava = false
}

// Licence gate (F10). The repository is AGPL-3.0-or-later; a transitive with a
// permissively-incompatible licence must fail the build here rather than be
// discovered after a jar ships. compileOnly platform APIs (Paper, Velocity) are
// not on the runtime graph licensee walks — they are accepted separately by the
// project licence posture in CONTRIBUTING.md.
licensee {
    // SPDX identifiers only — licensee rejects unknown ids at configuration time.
    allow("Apache-2.0")
    allow("MIT")
    allow("MIT-0")
    allow("BSD-2-Clause")
    allow("BSD-3-Clause")
    allow("EPL-1.0")
    allow("EPL-2.0")
    allow("GPL-2.0-with-classpath-exception")
    allow("GPL-2.0-only")
    allow("GPL-2.0-or-later")
    allow("GPL-3.0-only")
    allow("GPL-3.0-or-later")
    allow("LGPL-2.1-only")
    allow("LGPL-2.1-or-later")
    allow("LGPL-3.0-only")
    allow("LGPL-3.0-or-later")
    allow("MPL-2.0")
    allow("CC0-1.0")
    allow("CDDL-1.0")
    allow("CDDL-1.1")
    allow("AGPL-3.0-only")
    allow("AGPL-3.0-or-later")

    // POMs that declare a URL instead of (or as well as) an SPDX id. Each is a
    // known-good open licence we already allow by SPDX above; the URL form is
    // what the artifact actually ships and what licensee matches on.
    allowUrl("https://aws.amazon.com/apache2.0") // AWS SDK modules
    allowUrl("https://www.apache.org/licenses/LICENSE-2.0.txt")
    allowUrl("https://www.apache.org/licenses/LICENSE-2.0")
    allowUrl("https://opensource.org/licenses/Apache-2.0")
    allowUrl("https://opensource.org/license/mit") // modern OSI MIT URL
    allowUrl("https://opensource.org/licenses/MIT")
    allowUrl("https://www.bouncycastle.org/licence.html")
    // PostgreSQL JDBC — both spellings appear in the wild.
    allowUrl("https://jdbc.postgresql.org/about/license.html")
    allowUrl("https://jdbc.postgresql.org/about/licence.html")
    allowUrl("https://www.postgresql.org/about/licence/")
    // Flyway's POM points at a README rather than a licence file; the project
    // is Apache-2.0 (verified against the Flyway repository).
    allowUrl("https://github.com/flyway/flyway/blob/main/README.txt")
}
