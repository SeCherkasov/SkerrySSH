// Kover (code coverage) rides the root buildscript classpath rather than the plugins {} DSL, and
// each measured module applies it by id (see its build.gradle.kts). Both routes would work; this
// one is what the modules are already wired for.
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

// --- Kover: aggregate coverage. Run: ./gradlew koverHtmlReport ---------------------------------
apply<kotlinx.kover.gradle.plugin.KoverGradlePlugin>()

// Aggregate over the modules that carry real logic and tests. Each measured module applies Kover
// itself (see its build.gradle.kts). findProject keeps this correct under the serverOnly settings
// graph, which drops some modules; project(path) is the dependency notation — passing the Project
// itself is deprecated and fails in Gradle 10.
dependencies {
    listOf(":shared", ":composeApp", ":server", ":sync-wire").forEach { path ->
        findProject(path)?.let { add("kover", project(path)) }
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

// --- detekt: static analysis. Run: ./gradlew detektAll -----------------------------------------
//
// The DetektPlugin itself is not applied — the task type is registered directly, which needs no
// Kotlin Gradle plugin: detekt-cli just parses the files we point it at. The plugin used to die
// with NoClassDefFoundError from the isolated classpath of the script this moved out of; from here
// it does apply, but it brings per-source-set tasks with type resolution, a different finding set
// and new baselines. That switch is its own change, not a side effect of this one.
val detektCliDependency = "io.gitlab.arturbosch.detekt:detekt-cli:" + libs.versions.detekt.get()

subprojects {
    val detektCli = configurations.detachedConfiguration(
        dependencies.create(detektCliDependency),
    )

    tasks.register<io.gitlab.arturbosch.detekt.Detekt>("detekt") {
        group = "verification"
        description = "Runs detekt over every source set of this module."
        detektClasspath.setFrom(detektCli)
        // KMP layout: detekt's default source set (src/main/kotlin) doesn't exist here. Point it at
        // the whole module so commonMain/jvmSharedMain/desktopMain/androidMain are all covered.
        // Generated code needs no exclusion: it lands in <module>/build, a sibling of src.
        setSource(files("src"))
        include("**/*.kt", "**/*.kts")
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

    // Generates/refreshes this module's baseline. Run once per module after adding a rule:
    // ./gradlew detektBaseline
    tasks.register<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>("detektBaseline") {
        group = "verification"
        description = "Rewrites this module's detekt baseline from its current findings."
        detektClasspath.setFrom(detektCli)
        setSource(files("src"))
        include("**/*.kt", "**/*.kts")
        config.setFrom(rootProject.file("gradle/detekt.yml"))
        buildUponDefaultConfig = true
        baseline.set(rootProject.file("gradle/detekt-baseline-${project.name}.xml"))
    }
}

tasks.register("detektAll") {
    group = "verification"
    description = "Runs detekt in every module."
    dependsOn(subprojects.map { "${it.path}:detekt" })
}
