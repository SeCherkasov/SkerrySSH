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
