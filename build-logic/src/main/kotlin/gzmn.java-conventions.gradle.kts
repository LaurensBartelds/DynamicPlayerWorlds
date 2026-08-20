plugins {
    `java-library`
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

java {
    toolchain {
        languageVersion.set(
            JavaLanguageVersion.of(providers.gradleProperty("javaToolchainVersion").get()),
        )
    }
}

val libs = versionCatalogs.named("libs")

dependencies {
    // JSpecify and Checker Framework annotations are compile-only: they carry
    // no runtime behaviour and must not be shaded into a plugin jar.
    compileOnly(libs.findLibrary("jspecify").orElseThrow())
    testCompileOnly(libs.findLibrary("jspecify").orElseThrow())
    compileOnly(libs.findLibrary("checker-qual").orElseThrow())
    testCompileOnly(libs.findLibrary("checker-qual").orElseThrow())

    testImplementation(platform(libs.findLibrary("junit-bom").orElseThrow()))
    testImplementation(libs.findLibrary("junit-jupiter").orElseThrow())
    testImplementation(libs.findLibrary("assertj").orElseThrow())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").orElseThrow())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf(
            "-Xlint:all",
            "-Xlint:-serial",
            "-Xlint:-processing",
            "-Xlint:-this-escape",
            "-Xlint:-classfile",
        ),
    )
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
    )
    val jvmVersion = javaLauncher.map { it.metadata.languageVersion.asInt() }
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            if (jvmVersion.getOrElse(21) >= 24) {
                listOf("--sun-misc-unsafe-memory-access=allow")
            } else {
                emptyList()
            }
        },
    )
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }
}

// Reproducible archives: a rebuild of a tag produces byte-identical output, so
// "is the running jar the one we released" is an answerable question.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    dirPermissions { unix("rwxr-xr-x") }
    filePermissions { unix("rw-r--r--") }
}
