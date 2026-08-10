// Root detekt (static analysis) wiring, applied from build.gradle.kts.
//
// This script carries its own buildscript classpath: an applied script does NOT inherit the parent's
// buildscript classpath for compilation.
buildscript {
    repositories { gradlePluginPortal() }
    // Keep in sync with `detekt` in gradle/libs.versions.toml (catalog accessors aren't available
    // in an applied script plugin, so the version is spelled out here — as with Kover).
    dependencies { classpath("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:1.23.8") }
}

// The DetektPlugin is deliberately NOT applied. Its KMP integration resolves
// KotlinMultiplatformExtension, which lives in the Kotlin Gradle plugin's class loader — a different
// one from this script's isolated buildscript classpath — so applying it dies with
// NoClassDefFoundError on :shared and :composeApp. Registering the task type directly needs no KGP
// at all: detekt-cli just parses the files we point it at.
val detektVersion = "1.23.8"

subprojects {
    val detektCli = configurations.detachedConfiguration(
        dependencies.create("io.gitlab.arturbosch.detekt:detekt-cli:$detektVersion"),
    )

    tasks.register<io.gitlab.arturbosch.detekt.Detekt>("detekt") {
        group = "verification"
        description = "Runs detekt over every source set of this module."
        detektClasspath.setFrom(detektCli)
        // KMP layout: detekt's default source set (src/main/kotlin) doesn't exist here. Point it at
        // the whole module so commonMain/jvmSharedMain/desktopMain/androidMain are all covered.
        setSource(files("src"))
        include("**/*.kt", "**/*.kts")
        // Compose resource accessors are ~43k generated lines (the i18n string table) — the same
        // exclusion Kover carries, for the same reason.
        exclude("**/generated/**", "**/build/**")
        config.setFrom(rootProject.file("gradle/detekt.yml"))
        buildUponDefaultConfig = true
        // One baseline per module: a shared file would be overwritten by whichever module ran
        // `detektBaseline` last, silently dropping the other modules' entries.
        val baselineFile = rootProject.file("gradle/detekt-baseline-${project.name}.xml")
        if (baselineFile.exists()) baseline.set(baselineFile)
        reports {
            html.required.set(true)
            xml.required.set(false)
            txt.required.set(false)
            sarif.required.set(false)
            md.required.set(false)
        }
    }

    // Generates/refreshes gradle/detekt-baseline.xml. Run once per module after adding a rule:
    // ./gradlew detektBaseline
    tasks.register<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>("detektBaseline") {
        group = "verification"
        description = "Rewrites this module's detekt baseline from its current findings."
        detektClasspath.setFrom(detektCli)
        setSource(files("src"))
        include("**/*.kt", "**/*.kts")
        exclude("**/generated/**", "**/build/**")
        config.setFrom(rootProject.file("gradle/detekt.yml"))
        buildUponDefaultConfig = true
        baseline.set(rootProject.file("gradle/detekt-baseline-${project.name}.xml"))
    }
}

// Aggregate entry point: ./gradlew detektAll
tasks.register("detektAll") {
    group = "verification"
    description = "Runs detekt in every module."
    dependsOn(subprojects.map { "${it.path}:detekt" })
}
