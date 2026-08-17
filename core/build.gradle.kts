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
    // The S3 module drags in two HTTP transports it does not need here: a
    // Netty-based async one and Apache HttpClient 5. Both are large, and Netty
    // in particular is a classloader hazard inside a plugin jar because Paper
    // and Velocity each ship their own. Every transfer in this system is
    // synchronous work on the bounded `io` executor (NFR-7), so the JDK's own
    // HttpURLConnection transport covers it, and the other two are removed
    // rather than merely relocated. Selecting it explicitly at client
    // construction is deliberate: classpath discovery would fail at the first
    // upload rather than at startup.
    implementation(libs.awssdk.s3) {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
    }
    implementation(libs.awssdk.urlconnection)
    implementation(libs.zstd)
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.prometheus)

    // SLF4J binding for unit tests (and compile for ListAppender-based tests).
    // Production plugins use the platform's binding (Paper/Velocity); core must
    // not ship logback into a plugin jar.
    testImplementation(libs.logback.classic)

    testImplementation(libs.archunit.junit5)

    // Testcontainers is declared here rather than taken from :testing, because
    // :testing depends on :core and the reverse would be a cycle. F9's fixtures
    // serve :backend, :proxy and the e2e harness; :core owns the database and so
    // tests it directly.
    //
    // Mocks are not an option for anything in core.db. The whole design rests on
    // the exact semantics of conditional UPDATE row counts, advisory locks and
    // database time (MN-3a, MN-8, FR-40), and a mock reproduces none of them —
    // it only reproduces what the author already believed (CONTRIBUTING.md,
    // "Tests").
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
