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
        // JitPack: только для usb-serial-for-android (USB-OTG serial на Android). Ограничено группой,
        // чтобы прочие зависимости резолвились из mavenCentral/google.
        maven("https://jitpack.io") {
            mavenContent { includeGroup("com.github.mik3y") }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// serverOnly: собрать только sync-сервер (Ktor/JVM) без Android-модулей — для Docker-образа,
// где нет Android SDK. Включается `-PserverOnly` или env SKERRY_SERVER_ONLY=1.
val serverOnly = providers.gradleProperty("serverOnly").isPresent ||
    System.getenv("SKERRY_SERVER_ONLY") == "1"

include(":server")
if (!serverOnly) {
    include(":shared")
    include(":composeApp")
    include(":androidApp")
}
