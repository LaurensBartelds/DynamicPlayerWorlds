plugins {
    `kotlin-dsl`
}

// Convention plugins apply third-party Gradle plugins, which means those
// plugins must be on this build's classpath. Plugin marker artifacts are used
// so the versions stay in gradle/libs.versions.toml and nowhere else.
fun marker(plugin: Provider<PluginDependency>) =
    plugin.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }

dependencies {
    implementation(marker(libs.plugins.spotless))
    implementation(marker(libs.plugins.errorprone))
    implementation(marker(libs.plugins.forbiddenapis))
    implementation(marker(libs.plugins.shadow))
}
