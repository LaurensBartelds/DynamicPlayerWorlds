import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.zip.ZipFile
import org.cyclonedx.gradle.CycloneDxTask

plugins {
    id("gzmn.quality-conventions")
    id("com.gradleup.shadow")
    id("org.cyclonedx.bom")
}

val libs = versionCatalogs.named("libs")

/**
 * paper-api coordinate actually used for this build.
 *
 * Defaults to the pin in {@code gradle/libs.versions.toml}. CI's paper-latest
 * job overrides it with {@code -PpaperApi=<maven version>} so a nightly can
 * compile against Paper's newest API without rewriting the catalog (plan §5.6).
 */
val paperApiCoordinate: String =
    providers
        .gradleProperty("paperApi")
        .orElse(
            providers.provider {
                libs.findVersion("paperApi").orElseThrow().requiredVersion
            },
        ).get()

/**
 * The Minecraft version this artifact was built against, taken from the
 * paper-api coordinate. It goes in the jar filename deliberately: an operator
 * should never have to open a jar to find out what it targets.
 *
 * Paper has published under two coordinate shapes, and both have to reduce to
 * the Minecraft version alone:
 *
 *     1.21.4-R0.1-SNAPSHOT   ->  1.21.4    Bukkit-style, up to Minecraft 1.21.11
 *     26.2.build.112-stable  ->  26.2      year.season, from Minecraft 26.1 on
 *
 * The Paper build number is deliberately dropped. It is a property of the pin in
 * libs.versions.toml, not of the Minecraft version the jar targets, and putting
 * it here would change the filename on every routine Paper bump.
 */
val minecraftVersion: String =
    paperApiCoordinate
        .substringBefore("-")
        .replace(Regex("""\.build\.\d+$"""), "")

// Exposed so a module's processResources can stamp it into a plugin descriptor.
// plugin.yml's api-version has to be the version we actually compiled against:
// it is what Paper reads to decide which compatibility behaviours to apply, and a
// descriptor claiming an older API than the jar was built for is asking the
// server to guess. Deriving it from the same value as the jar name is what stops
// the two from drifting.
extra["minecraftApiVersion"] = minecraftVersion
extra["paperApiCoordinate"] = paperApiCoordinate

// When -PpaperApi is set, force every paper-api dependency onto that coordinate
// so compile classpath, tests and the jar name all agree. Without this the
// catalog pin would still win resolution and the nightly would be a no-op.
val paperApiOverride = providers.gradleProperty("paperApi")
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "io.papermc.paper" && requested.name == "paper-api") {
            val override = paperApiOverride.orNull
            if (override != null) {
                useVersion(override)
                because("overridden by -PpaperApi (CI paper-latest / local smoke)")
            }
        }
    }
}

// Relocation is mandatory, not optional. A plugin jar shares a classloader with
// every other plugin on the server, so an unrelocated HikariCP meeting a
// different unrelocated HikariCP is a startup failure discovered weeks later.
val shadePrefix = "nl.gzmn.playerworlds.libs"

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveVersion.set("${project.version}+mc$minecraftVersion")

    // Direct dependencies.
    relocate("com.zaxxer.hikari", "$shadePrefix.hikari")
    relocate("org.flywaydb", "$shadePrefix.flywaydb")
    relocate("org.postgresql", "$shadePrefix.postgresql")
    relocate("io.micrometer", "$shadePrefix.micrometer")
    relocate("com.github.luben.zstd", "$shadePrefix.zstd")
    relocate("org.apache.commons", "$shadePrefix.commons")
    // Relocating this means a logback configuration must name the encoder by
    // its relocated class, not net.logstash.logback.encoder.LogstashEncoder.
    // See config/logback/ for the class names F8 documents.
    relocate("net.logstash.logback", "$shadePrefix.logstash")
    relocate("com.moandjiezana.toml", "$shadePrefix.toml4j")

    // Transitives. These are the ones that actually bite: nothing here is
    // written in a build file anywhere, so each arrived through someone else's
    // dependency and would ship unrelocated unless named. verifyShadedJar below
    // is what stops the next one from slipping through silently.
    relocate("software.amazon", "$shadePrefix.amazon") // awssdk + eventstream
    relocate("tools.jackson", "$shadePrefix.jackson3") // via flyway-core
    relocate("com.fasterxml.jackson", "$shadePrefix.jackson2") // via jackson 3
    relocate("org.reactivestreams", "$shadePrefix.reactivestreams") // via awssdk
    relocate("org.HdrHistogram", "$shadePrefix.hdrhistogram") // via micrometer
    relocate("org.LatencyUtils", "$shadePrefix.latencyutils") // via micrometer
    // micrometer-registry-prometheus pulls the Prometheus client libraries.
    relocate("io.prometheus", "$shadePrefix.prometheus")
    // via toml4j. Both platforms ship Gson, so this is relocated rather than
    // excluded: borrowing the platform's copy would tie us to whichever version
    // it happens to carry, and toml4j was compiled against a much older one.
    relocate("com.google.gson", "$shadePrefix.gson")

    dependencies {
        // Both platforms provide SLF4J and bind it to their own logger. A
        // second copy inside the plugin jar is the classloader conflict this
        // whole relocation scheme exists to prevent, and it cannot be fixed by
        // relocating: the point is to use the server's binding, not ours.
        exclude(dependency("org.slf4j:slf4j-api"))

        // Annotations with no runtime behaviour, arriving transitively.
        exclude(dependency("org.checkerframework:checker-qual"))
        exclude(dependency("org.jspecify:jspecify"))
        // via gson, which arrives via toml4j on the proxy.
        exclude(dependency("com.google.errorprone:error_prone_annotations"))
    }

    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class")

    // Reproducible output, as in gzmn.java-conventions.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

/**
 * Fails the build if any class in a plugin jar sits outside our own namespace.
 *
 * The relocation list above is a list, and lists go stale: a dependency bump
 * adds a transitive, nobody notices, and the jar ships an unrelocated Netty or
 * Jackson that collides with another plugin's copy months later on someone
 * else's server. That failure is invisible here and expensive there, which is
 * exactly the kind the build should be catching instead of a reviewer.
 *
 * The rule is deliberately absolute — every class under nl/gzmn/playerworlds/
 * or the jar does not ship — so the only way to add a dependency is to say
 * where it goes.
 */
val verifyShadedJar =
    tasks.register("verifyShadedJar") {
        group = "verification"
        description = "Checks that every class in the plugin jar is relocated into our own namespace."

        val shadedJar = tasks.named<ShadowJar>("shadowJar")
            .flatMap { it.archiveFile }
        inputs.file(shadedJar).withPropertyName("shadedJar")

        val marker = layout.buildDirectory.file("reports/verifyShadedJar.txt")
        outputs.file(marker).withPropertyName("report")

        // Copied into locals on purpose: a doLast that reads a script-level
        // property captures the script object, which the configuration cache
        // cannot serialise.
        val allowedPrefix = "nl/gzmn/playerworlds/"
        val relocationPrefix = shadePrefix

        doLast {
            val jarFile = shadedJar.get().asFile
            val multiRelease = Regex("^META-INF/versions/\\d+/")

            val stray =
                ZipFile(jarFile).use { zip ->
                    zip.entries()
                        .asSequence()
                        .map { it.name }
                        .filter { it.endsWith(".class") }
                        .map { it.replace(multiRelease, "") }
                        .filterNot { it.startsWith(allowedPrefix) }
                        .filterNot { !it.contains('/') } // default-package helpers
                        .map { it.substringBeforeLast('/').replace('/', '.') }
                        .distinct()
                        .sorted()
                        .toList()
                }

            if (stray.isNotEmpty()) {
                throw GradleException(
                    buildString {
                        appendLine("${jarFile.name} ships ${stray.size} package(s) outside $allowedPrefix:")
                        stray.take(20).forEach { appendLine("  $it") }
                        if (stray.size > 20) appendLine("  ... and ${stray.size - 20} more")
                        appendLine()
                        appendLine("Every third-party class must be relocated under $relocationPrefix,")
                        appendLine("or excluded if the platform already provides it. See")
                        appendLine("build-logic/src/main/kotlin/gzmn.plugin-conventions.gradle.kts.")
                    },
                )
            }

            marker.get().asFile.also { it.parentFile.mkdirs() }.writeText("ok: no unrelocated classes\n")
        }
    }

tasks.named("check") {
    dependsOn(verifyShadedJar)
}

tasks.named("jar") {
    // The thin jar is never shipped; only the shaded one is.
    enabled = false
}

tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}

// CycloneDX SBOM for the runtime graph that actually ships inside the shaded
// jar (F10 / release.yml). Generated on demand — release CI attaches it next to
// the jar and checksums; day-to-day check does not need it. The plugin exposes
// a task, not an extension, so configuration goes on cyclonedxBom directly.
tasks.named<CycloneDxTask>("cyclonedxBom") {
    includeConfigs.set(listOf("runtimeClasspath"))
    skipConfigs.set(listOf("testRuntimeClasspath", "testCompileClasspath"))
    projectType.set("application")
    schemaVersion.set("1.5")
    destination.set(layout.buildDirectory.dir("reports/sbom").map { it.asFile })
    outputName.set("${project.name}-bom")
    outputFormat.set("json")
    includeBomSerialNumber.set(true)
}
