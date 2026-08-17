rootProject.name = "dynamicplayerworlds"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        // Paper and Velocity APIs. Required for :backend and :proxy; :core and
        // :testing resolve from Maven Central alone.
        //
        // The content filter is deliberately tight, so an ordinary dependency
        // can never be silently served from here instead of Central. It has to
        // cover paper-api's transitives that Central does not carry:
        // com.mojang:brigadier and com.mojang:datafixerupper.
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
            content {
                includeGroup("io.papermc.paper")
                includeGroup("com.velocitypowered")
                includeGroup("net.md-5")
                includeGroup("io.papermc")
                includeGroup("com.mojang")
            }
        }
    }
}

include(":core", ":backend", ":proxy", ":testing")
