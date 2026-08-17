plugins {
    id("gzmn.quality-conventions")
}

description = "Shared test fixtures: Testcontainers factories, world fixtures"

dependencies {
    api(platform(libs.junit.bom))
    api(platform(libs.testcontainers.bom))
    api(libs.junit.jupiter)
    api(libs.assertj)
    api(libs.testcontainers.junit)
    api(libs.testcontainers.postgresql)
    api(libs.testcontainers.minio)
    api(libs.archunit.junit5)

    // api so backend/proxy/e2e tests see Database, Schema, StorageClientSettings
    // and WorldId without re-declaring :core for fixture return types alone.
    api(project(":core"))

    // S3 client for TestObjectStore. Same transport exclusions as :core: every
    // transfer is synchronous on the test thread, so Netty/Apache are weight
    // with no upside and a classloader hazard if a consumer shaded this module.
    implementation(libs.awssdk.s3) {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
    }
    implementation(libs.awssdk.urlconnection)

    // Core marks Hikari/PostgreSQL/Flyway as implementation; smoke tests need
    // them on the runtime classpath when opening a real pool against Testcontainers.
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.flyway.core)
    testRuntimeOnly(libs.flyway.postgresql)
    testRuntimeOnly(libs.slf4j.api)
    testRuntimeOnly(libs.logback.classic)
}
