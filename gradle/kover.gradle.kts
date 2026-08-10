// Root Kover (coverage) wiring, applied from build.gradle.kts.
//
// This script carries its own buildscript classpath: an applied script does NOT inherit the parent's
// buildscript classpath for compilation, so the Kover types must be resolvable from here.
buildscript {
    repositories { gradlePluginPortal() }
    // Keep the version in sync with `kover` in gradle/libs.versions.toml (type-safe catalog
    // accessors aren't available in an applied script plugin, so it's spelled out here).
    dependencies { classpath("org.jetbrains.kotlinx:kover-gradle-plugin:0.9.8") }
}

// Apply by type, not by id: an applied script's buildscript classpath carries the plugin CLASS but
// doesn't register its id for `apply(plugin = "…")` resolution (that fails with "plugin not found").
apply<kotlinx.kover.gradle.plugin.KoverGradlePlugin>()

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
