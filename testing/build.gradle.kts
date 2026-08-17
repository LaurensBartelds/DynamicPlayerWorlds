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

    implementation(project(":core"))
}
