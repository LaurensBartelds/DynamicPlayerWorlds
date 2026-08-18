plugins {
    id("gzmn.plugin-conventions")
}

description = "gzmn-worlds-proxy: the Velocity plugin, owns commands and placement"

base.archivesName.set("gzmn-worlds-proxy")

dependencies {
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)
    // Optional at runtime (§4). With LuckPerms installed the proxy enumerates a player's
    // storage tiers outright, so any tier works; without it, it probes the nodes named by
    // storage.quota-tiers. compileOnly because LuckPerms supplies its own API at runtime and
    // shading a second copy would break the service lookup.
    compileOnly(libs.luckperms.api)
    implementation(project(":core"))
    implementation(libs.logstash.encoder)
    // config.toml (specification section 7). Velocity ships toml4j for its own
    // velocity.toml, but we relocate our copy rather than borrow the platform's:
    // a plugin that breaks when the proxy reorganises its internals is the tax
    // relocation exists to avoid.
    implementation(libs.toml4j)
    implementation(libs.gson)

    testImplementation(libs.velocity.api)
    testImplementation(libs.luckperms.api)
    testImplementation(project(":testing"))
}
