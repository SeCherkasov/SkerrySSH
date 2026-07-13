// Kover (code coverage) is loaded via the buildscript classpath instead of the plugins {} DSL, and
// applied conditionally below. A versioned plugins {} entry is resolved even with `apply false` (the
// same trap that keeps io.ktor.plugin out of this block — see the note there), which would make the
// hermetic offline Flatpak build (-Dskerry.offlineRepo) fail: its offline repo doesn't carry Kover.
// Coverage is a dev/CI concern, so it is simply absent from the offline packaging build.
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
if (System.getProperty("skerry.offlineRepo") == null) {
    apply(plugin = "org.jetbrains.kotlinx.kover")
    dependencies {
        listOf(":shared", ":composeApp", ":server", ":sync-wire").forEach { path ->
            findProject(path)?.let { add("kover", it) }
        }
    }
    extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
        reports.filters.excludes {
            // Generated code has no tests by design and swamps the denominator: the Compose
            // resource accessors alone are ~43k lines of generated Kotlin (the i18n string table).
            packages("app.skerry.ui.generated.resources")
            classes("app.skerry.ui.app.AppVersion")
        }
    }
}
