import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("gzmn.java-conventions")
    id("com.diffplug.spotless")
    id("net.ltgt.errorprone")
    id("de.thetaphi.forbiddenapis")
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
