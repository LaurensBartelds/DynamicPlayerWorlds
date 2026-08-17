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
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
            content {
                includeGroup("io.papermc.paper")
                includeGroup("com.velocitypowered")
                includeGroup("net.md-5")
                includeGroup("io.papermc")
            }
        }
    }
}

include(":core", ":backend", ":proxy", ":testing")
