pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "My Application"
include(":app")
include(":baselineprofile")
// Note: ffmpeg-decoder-downmix is excluded by default. To enable it:
// 1. Add FFMPEG_SOURCE_DIR and FFMPEG_BUILD_DIR to local.properties
// 2. Set USE_LOCAL_FFMPEG_DECODER=true in local.properties
// See local.example.properties for details.
