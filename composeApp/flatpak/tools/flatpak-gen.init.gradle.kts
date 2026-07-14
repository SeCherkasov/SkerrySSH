// Generation-only init script: applies the flatpak-gradle-generator plugin to the desktop
// build's modules so it emits a Flatpak "sources" list (URL + sha256 per Maven artifact) plus a
// maven-layout offline repository. Kept out of the production build files — it is only ever
// passed with `--init-script` while generating, never during a normal build. See package-flatpak.sh.
initscript {
    repositories { gradlePluginPortal() }
    dependencies {
        // Plugin marker artifact: pulls the implementation transitively.
        classpath("io.github.jwharm.flatpak-gradle-generator:io.github.jwharm.flatpak-gradle-generator.gradle.plugin:1.5.0")
    }
}

// Modules that make up the desktop distributable (createDistributable). The plugin resolves each
// module's buildscript (plugin) classpath and its project configurations, so between the three we
// capture Kotlin/Compose/AGP plugins plus every runtime dependency (incl. skiko native jars).
val desktopModules = setOf("sync-wire", "shared", "composeApp")

gradle.allprojects {
    // Capture plugin *marker* artifacts (the `<id>.gradle.plugin` poms). The generator walks the
    // buildscript classpath, which carries plugin *implementations*, not the markers the plugins{}
    // DSL resolves through pluginManagement — so every plugin the root build declares `apply false`
    // needs its marker vendored, or the offline build fails resolving it at root/settings evaluation.
    // foojay is a settings plugin (same gap). Injecting each marker onto one module's buildscript
    // classpath makes the generator capture it (marker + impl, correct hashes); overlap with the
    // already-captured impls de-dupes on merge. Versions mirror gradle/libs.versions.toml.
    if (name == "sync-wire") {
        buildscript {
            // mavenCentral first: plugin *implementations* live there and on the portal with
            // DIFFERENT bytes, and the generator can emit a Maven-Central URL for a portal-resolved
            // jar → checksum mismatch at download. Resolving impls from Central keeps URL and hash
            // consistent; the portal only serves the `.gradle.plugin` markers, google the AGP ones.
            repositories {
                mavenCentral()
                gradlePluginPortal()
                google()
            }
            // The plugins the desktop-only build resolves: foojay (settings), the Kotlin/Compose
            // plugins, and both AGP plugins. com.android.application is still declared `apply false`
            // at root (it pins AGP), so its classpath is resolved and must be offline; io.ktor.plugin
            // is excluded — it is no longer at root and :server is not part of the desktop-only build.
            fun marker(id: String, version: String) =
                dependencies.add("classpath", "$id:$id.gradle.plugin:$version")
            marker("org.gradle.toolchains.foojay-resolver-convention", "1.0.0")
            marker("org.jetbrains.kotlin.multiplatform", "2.4.10")
            marker("org.jetbrains.kotlin.jvm", "2.4.10")
            marker("org.jetbrains.kotlin.plugin.compose", "2.4.10")
            marker("org.jetbrains.kotlin.plugin.serialization", "2.4.10")
            marker("org.jetbrains.compose", "1.9.3")
            marker("com.android.application", "9.1.1")
            marker("com.android.kotlin.multiplatform.library", "9.1.1")
            // Resolved by the Compose plugin at task-execution time via a detached configuration the
            // generator can't see — vendor it explicitly so createDistributable runs offline.
            dependencies.add("classpath", "org.jetbrains.compose:gradle-plugin-internal-jdk-version-probe:1.9.3")
        }
        // pluginManagement resolves each plugin's classpath in isolation; the generator instead
        // captures ONE merged buildscript classpath, where the Kotlin plugins' kotlin-stdlib (2.4.10)
        // outranks the older kotlin-stdlib/-reflect that AGP 9.1.1 pulls (2.2.21). Those never land
        // in the offline repo, so the sandbox — which does resolve AGP's marker on its own — fails on
        // `Could not resolve kotlin-stdlib:2.2.21`. Resolve the AGP marker in an isolated project
        // configuration so its Kotlin transitives are captured at AGP's real version (tracks any
        // future AGP bump automatically — no hardcoded stdlib version). Included in the generator's
        // whitelist below.
        val agpProbe = configurations.create("flatpakAgpProbe") {
            isCanBeResolved = true
            isCanBeConsumed = false
        }
        dependencies.add(agpProbe.name, "com.android.application:com.android.application.gradle.plugin:9.1.1")
        // The Kotlin Gradle plugin's Gradle-API variant (kotlin-gradle-plugin-idea's module metadata)
        // depends on an OLDER kotlin-stdlib/-reflect (2.2.21 for KGP 2.4.10) than the compiler itself
        // (2.4.10). The generator captures only the merged/highest version, but the sandbox resolves
        // that variant on the root buildscript classpath and needs the exact 2.2.21 jars. Vendor them
        // in their own isolated configuration so the merge can't collapse them to 2.4.10.
        // NOTE: bump this alongside the `kotlin` catalog version — check the failure if the sandbox
        // build reports a different `Could not resolve kotlin-stdlib:<x>` after a Kotlin upgrade.
        val kgpApiProbe = configurations.create("flatpakKgpApiProbe") {
            isCanBeResolved = true
            isCanBeConsumed = false
        }
        dependencies.add(kgpApiProbe.name, "org.jetbrains.kotlin:kotlin-stdlib:2.2.21")
        dependencies.add(kgpApiProbe.name, "org.jetbrains.kotlin:kotlin-reflect:2.2.21")
    }
    if (name in desktopModules) {
        apply<io.github.jwharm.flatpakgradlegenerator.FlatpakGradleGeneratorPlugin>()
        tasks.named<io.github.jwharm.flatpakgradlegenerator.FlatpakGradleGeneratorTask>("flatpakGradleGenerator") {
            outputFile.set(layout.buildDirectory.file("flatpak-sources.json"))
            // All modules download into one shared repo so flatpak-builder reconstructs a single
            // Maven repository the offline build points at.
            downloadDirectory.set("offline-repository")
            // Whitelist exactly the configurations createDistributable resolves: the desktop
            // dependency classpaths plus the Kotlin toolchain (compiler, build-tools API, and the
            // compose/serialization/atomicfu compiler plugins). Resolving *every* configuration
            // trips variant ambiguity on composeApp's Compose-resources/metadata configurations,
            // and the Android/metadata classpaths would drag in artifacts the desktop build never
            // touches. The plugin captures the buildscript (plugin) classpath separately regardless.
            includeConfigurations.set(
                setOf(
                    // Dependency classpaths — KMP jvm("desktop") and plain JVM (sync-wire).
                    "desktopCompileClasspath", "desktopRuntimeClasspath",
                    "desktopMainCompileClasspath", "desktopMainRuntimeClasspath",
                    "compileClasspath", "runtimeClasspath",
                    // Kotlin toolchain: the compiler, the Build Tools API impl, and compiler plugins.
                    "kotlinCompilerClasspath", "kotlinBuildToolsApiClasspath",
                    "kotlinCompilerPluginClasspath",
                    "kotlinCompilerPluginClasspathDesktopMain", // KMP desktop compilation
                    "kotlinCompilerPluginClasspathMain",        // plain JVM (sync-wire)
                    // Probe configs (sync-wire only) — capture the Kotlin transitives the merged
                    // classpath hides: AGP's isolated deps and KGP's Gradle-API-variant stdlib/reflect.
                    // A no-op on modules that don't define them; the generator resolves only what exists.
                    "flatpakAgpProbe", "flatpakKgpApiProbe",
                )
            )
        }
    }
}
