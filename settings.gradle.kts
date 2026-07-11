rootProject.name = "skerry"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Offline Maven repository for the hermetic Flatpak build: flatpak-builder materialises it from
// flatpak-sources.json, and the sandboxed `gradle --offline` is passed -Dskerry.offlineRepo=<dir>.
// Added first so it wins; absent (a no-op) for normal online builds. See composeApp/flatpak/.
// The snippet is inlined in each block because pluginManagement is evaluated in isolation from
// the rest of the settings script, so a shared top-level helper would not be visible there.

pluginManagement {
    repositories {
        System.getProperty("skerry.offlineRepo")?.let { java.io.File(it) }
            ?.takeIf { it.isDirectory }?.let { maven { url = it.toURI() } }
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
        System.getProperty("skerry.offlineRepo")?.let { java.io.File(it) }
            ?.takeIf { it.isDirectory }?.let { maven { url = it.toURI() } }
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

// desktopOnly: build the desktop client (shared + composeApp/desktop) without the Android app or
// the server — for the Flatpak sandbox, which has no Android SDK. The Android KMP library targets
// stay (they configure fine with no SDK as long as no Android task runs); shared reads this flag
// only to drop its desktopTest dependency on :server. Enabled with `-PdesktopOnly` or SKERRY_DESKTOP_ONLY=1.
val desktopOnly = providers.gradleProperty("desktopOnly").isPresent ||
    System.getenv("SKERRY_DESKTOP_ONLY") == "1"

// desktopOnly excludes the server too: the desktop client's main build never touches it, and its
// ktor plugin drags a dynamic-version dependency that can't resolve in the offline Flatpak sandbox.
if (!desktopOnly) {
    include(":server")
}
include(":sync-wire") // client⇆server wire contract — needed by the serverOnly build too
if (!serverOnly) {
    include(":shared")
    include(":composeApp")
    if (!desktopOnly) {
        include(":androidApp")
    }
}
