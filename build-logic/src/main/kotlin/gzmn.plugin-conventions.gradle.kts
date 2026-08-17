plugins {
    id("gzmn.quality-conventions")
    id("com.gradleup.shadow")
}

val libs = versionCatalogs.named("libs")

/**
 * The Minecraft version this artifact was built against, taken from the
 * paper-api coordinate and reduced to its release part (1.21.4-R0.1-SNAPSHOT ->
 * 1.21.4). It goes in the jar filename deliberately: an operator should never
 * have to open a jar to find out what it targets.
 */
val minecraftVersion: String =
    libs.findVersion("paperApi").orElseThrow().requiredVersion.substringBefore("-R")

// Relocation is mandatory, not optional. A plugin jar shares a classloader with
// every other plugin on the server, so an unrelocated HikariCP meeting a
// different unrelocated HikariCP is a startup failure discovered weeks later.
val shadePrefix = "nl.gzmn.playerworlds.libs"

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveVersion.set("${project.version}+mc$minecraftVersion")

    relocate("com.zaxxer.hikari", "$shadePrefix.hikari")
    relocate("org.flywaydb", "$shadePrefix.flywaydb")
    relocate("org.postgresql", "$shadePrefix.postgresql")
    relocate("io.micrometer", "$shadePrefix.micrometer")
    relocate("software.amazon.awssdk", "$shadePrefix.awssdk")
    relocate("com.github.luben.zstd", "$shadePrefix.zstd")
    relocate("net.logstash.logback", "$shadePrefix.logstash")

    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class")

    // Reproducible output, as in gzmn.java-conventions.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.named("jar") {
    // The thin jar is never shipped; only the shaded one is.
    enabled = false
}

tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}
