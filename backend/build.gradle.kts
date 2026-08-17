plugins {
    id("gzmn.plugin-conventions")
}

description = "gzmn-worlds: the Paper plugin, runs on every worlds node"

base.archivesName.set("gzmn-worlds")

dependencies {
    compileOnly(libs.paper.api)
    implementation(project(":core"))
    implementation(libs.logstash.encoder)

    testImplementation(libs.paper.api)
    testImplementation(project(":testing"))
}

// The plugin.yml version is expanded from the build, so the version in the jar
// and the version the server reports can never drift.
tasks.processResources {
    val tokens = mapOf("version" to project.version.toString())
    inputs.properties(tokens)
    filesMatching("plugin.yml") { expand(tokens) }
}
