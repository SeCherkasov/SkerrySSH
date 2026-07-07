rootProject.name = "skerry"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // JitPack: only for usb-serial-for-android (USB-OTG serial on Android). Scoped to the group
        // so that all other dependencies resolve from mavenCentral/google.
        maven("https://jitpack.io") {
            mavenContent { includeGroup("com.github.mik3y") }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// serverOnly: build only the sync server (Ktor/JVM) without the Android modules — for the Docker
// image, which has no Android SDK. Enabled with `-PserverOnly` or the env SKERRY_SERVER_ONLY=1.
val serverOnly = providers.gradleProperty("serverOnly").isPresent ||
    System.getenv("SKERRY_SERVER_ONLY") == "1"

include(":server")
include(":sync-wire") // client⇆server wire contract — needed by the serverOnly build too
if (!serverOnly) {
    include(":shared")
    include(":composeApp")
    include(":androidApp")
}
