plugins {
    id("gzmn.plugin-conventions")
}

description = "gzmn-worlds-lobby: standalone zero-database Paper lobby plugin"

base.archivesName.set("gzmn-worlds-lobby")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    compileOnly(libs.paper.api.v121)
    // Zero-database stateless renderer: package only :core classes without heavy database/S3 transitives
    implementation(project(":core")) {
        isTransitive = false
    }

    testImplementation(libs.mockbukkit.v121)
    testImplementation(libs.paper.api.v121)
    testImplementation(project(":testing"))
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveVersion.set("${project.version}+mc1.21")
}

tasks.processResources {
    val tokens =
        mapOf(
            "version" to project.version.toString(),
            "apiVersion" to "1.21",
        )
    inputs.properties(tokens)
    filesMatching("plugin.yml") { expand(tokens) }
}
