// Kover (code coverage) is loaded onto the root buildscript classpath (not the plugins {} DSL) so
// every measured module inherits it and applies it by id (see each module's build.gradle.kts). It is
// gated on the online build: a versioned plugins {} entry would be resolved even with `apply false`
// (the same trap that keeps io.ktor.plugin out of that block — see the note there), which would make
// the hermetic offline Flatpak build (-Dskerry.offlineRepo) fail — its offline repo doesn't carry
// Kover. Coverage is a dev/CI concern, absent from the offline packaging build. The root-level
// aggregation + report config lives in gradle/kover.gradle.kts, applied online only (below), so this
// file never names a Kover type — which would fail to COMPILE offline, where Kover is off the classpath.
buildscript {
    if (System.getProperty("skerry.offlineRepo") == null) {
        repositories { gradlePluginPortal() }
        dependencies { classpath("org.jetbrains.kotlinx:kover-gradle-plugin:" + libs.versions.kover.get()) }
    }
}

plugins {
    // io.ktor.plugin is intentionally NOT declared here — it is applied directly (with its catalog
    // version) by its only consumer, :server. Declaring it at root made every build resolve its full
    // plugin classpath, including the hermetic desktop-only Flatpak build (which excludes :server),
    // where ktor's dynamic-version transitive (commons-lang3:[3.18.0,)) can't resolve offline.
    // com.android.application STAYS: it pins the AGP version so it shares a single classpath with the
    // AGP KMP library plugin — dropping it breaks the normal build ("plugin already on classpath").
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinxSerialization) apply false
}

// Aggregate coverage at the root over the modules that carry real logic and tests. Each measured
// module applies Kover itself (see its build.gradle.kts). findProject keeps this correct under the
// serverOnly / desktopOnly settings graphs, which drop some modules. Run: ./gradlew koverHtmlReport
// Applied from a separate script so build.gradle.kts carries no Kover types — the offline Flatpak
// build has no Kover on its classpath and a direct reference here would fail to compile. See the
// script header. buildscript{} above already gates the classpath the same way.
if (System.getProperty("skerry.offlineRepo") == null) {
    apply(from = rootProject.file("gradle/kover.gradle.kts"))
}
