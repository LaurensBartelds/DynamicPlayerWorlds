plugins {
    id("gzmn.plugin-conventions")
}

description = "gzmn-worlds: the Paper plugin, runs on every worlds node"

base.archivesName.set("gzmn-worlds")

dependencies {
    compileOnly(libs.paper.api)
    implementation(project(":core"))
    implementation(libs.zstd)
    implementation(libs.commons.compress)
    implementation(libs.logstash.encoder)

    // MockBukkit before paper-api so the mock implementation wins on the test
    // classpath (plan section 11 plugin-surface layer).
    testImplementation(libs.mockbukkit)
    testImplementation(libs.paper.api)
    testImplementation(project(":testing"))
    testRuntimeOnly(libs.logback.classic)
}

// plugin.yml's version and api-version are both expanded from the build, so
// neither the version the server reports nor the API level it applies can drift
// from what was actually compiled. api-version comes from paperApi via
// gzmn.plugin-conventions, the same value that names the jar.
tasks.processResources {
    val tokens = mapOf(
        "version" to project.version.toString(),
        "apiVersion" to project.extra["minecraftApiVersion"].toString(),
    )
    inputs.properties(tokens)
    filesMatching("plugin.yml") { expand(tokens) }
}
