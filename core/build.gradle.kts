plugins {
    id("gzmn.quality-conventions")
}

description = "Platform-independent core: model, database, storage, config, control plane"

// This module must never gain a dependency on paper-api or velocity-api. It is
// what makes the storage engine, lease logic, manifest format and control plane
// testable without booting a Minecraft server, and it keeps the
// version-sensitive surface confined to backend/platform. Enforced by
// ArchitectureTest as well as by this file's contents. See ADR 0004.
dependencies {
    api(libs.slf4j.api)

    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.awssdk.s3)
    implementation(libs.zstd)
    implementation(libs.micrometer.core)

    testImplementation(libs.archunit.junit5)
}
