// Kover (code coverage) rides the root buildscript classpath rather than the plugins {} DSL, and
// each measured module applies it by id (see its build.gradle.kts). Both routes would work; this
// one is what the modules are already wired for. The root-level aggregation + report config lives
// in gradle/kover.gradle.kts (applied below).
buildscript {
    repositories { gradlePluginPortal() }
    dependencies {
        classpath("org.jetbrains.kotlinx:kover-gradle-plugin:" + libs.versions.kover.get())
        // Compose Hot Reload (dev-only live reload) rides the same classpath as Kover.
        classpath("org.jetbrains.compose.hot-reload:hot-reload-gradle-plugin:" + libs.versions.composeHotReload.get())
        // detekt (static analysis), same again.
        classpath("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:" + libs.versions.detekt.get())
    }
}

plugins {
    // io.ktor.plugin is intentionally NOT declared here — it is applied directly (with its catalog
    // version) by its only consumer, :server. A root entry, even with `apply false`, puts ktor's
    // plugin classpath — including its dynamic-version constraint commons-lang3:[3.18.0,) — on the
    // buildscript classpath every module inherits, instead of leaving it in :server's.
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
// serverOnly settings graph, which drops some modules. Run: ./gradlew koverHtmlReport
apply(from = rootProject.file("gradle/kover.gradle.kts"))
// Static analysis, wired the same way. Run: ./gradlew detekt
apply(from = rootProject.file("gradle/detekt.gradle.kts"))
