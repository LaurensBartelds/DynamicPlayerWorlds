plugins {
    id("gzmn.quality-conventions")
}

description = "e2e-only Paper plugin: join markers and RCON probes for the compose harness"

// Test-only artifact. Never published; loaded only by e2e/compose nodes.
base.archivesName.set("e2e-harness")

val paperApiCoordinate: String =
    providers
        .gradleProperty("paperApi")
        .orElse(providers.provider { libs.versions.paperApi.get() })
        .get()

// Same reduction as gzmn.plugin-conventions: stamp api-version from the pin.
val minecraftApiVersion: String =
    paperApiCoordinate
        .substringBefore("-")
        .replace(Regex("""\.build\.\d+$"""), "")

dependencies {
    compileOnly(libs.paper.api)
}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "io.papermc.paper" && requested.name == "paper-api") {
            val override = providers.gradleProperty("paperApi").orNull
            if (override != null) {
                useVersion(override)
                because("overridden by -PpaperApi")
            }
        }
    }
}

tasks.processResources {
    val tokens =
        mapOf(
            "version" to project.version.toString(),
            "apiVersion" to minecraftApiVersion,
        )
    inputs.properties(tokens)
    filesMatching("plugin.yml") { expand(tokens) }
}

tasks.jar {
    archiveVersion.set("${project.version}+mc$minecraftApiVersion")
}
