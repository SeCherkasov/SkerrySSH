import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(21)

    // expect/actual for classes/objects (LocalAppLocale) is still Beta — the flag removes a noisy warning.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm("desktop") {
        // kotlin("test") picks its backend from the Test task configuration: this enables JUnit 5
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }

    androidLibrary {
        namespace = "app.skerry.ui"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }
        androidResources {
            enable = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.shared)
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.material3)
            api(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
        }
        androidMain.dependencies {
            // androidx.activity.compose.BackHandler — intercepts the system "back" (PlatformBackHandler).
            implementation(libs.androidx.activity.compose)
            // Fast pairing (option B): ZXing generates the QR matrix, CameraX provides the camera preview,
            // ML Kit barcode-scanning recognizes the QR in the frame on-device (no network) — QrScannerScreen.
            implementation(libs.zxing.core)
            implementation(libs.androidx.camera.core)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.mlkit.barcode.scanning)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                // ZXing generates the QR matrix of the pairing code (desktop shows a QR for the phone to scan).
                implementation(libs.zxing.core)
                // Linux/X11 native window drag (_NET_WM_MOVERESIZE) for the undecorated window's
                // custom titlebar — hands the move to the compositor so it stays smooth. jna core
                // is already present transitively; jna-platform adds the X11 bindings.
                implementation(libs.jna.platform)
            }
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "app.skerry.ui.generated.resources"
}

// AppVersion.kt is generated from gradle.properties (skerry.versionName) so the About page and the
// update check compare against the real release version — a hand-edited constant drifted from the
// packaged one (2.4.0 vs 0.1.1).
val generateAppVersion = tasks.register("generateAppVersion") {
    val versionName = providers.gradleProperty("skerry.versionName").orNull ?: "0.1.0"
    val outDir = layout.buildDirectory.dir("generated/skerry/commonMain/kotlin")
    inputs.property("versionName", versionName)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("app/skerry/ui/app/AppVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package app.skerry.ui.app

            /** Generated from gradle.properties by :composeApp:generateAppVersion — do not edit. */
            object AppVersion {
                const val VERSION = "$versionName"
            }
            """.trimIndent() + "\n",
        )
    }
}
kotlin.sourceSets.named("commonMain") { kotlin.srcDir(generateAppVersion) }

// Theme colors are authored in composeApp/themes/*.xml (single source of truth) and generated into
// Kotlin at build time — same offline-safe pattern as generateAppVersion (no plugin, JDK XML only).
// Alpha-over-base tokens are kept as `.copy(alpha=)` on the inlined base literal so generated values
// stay bit-identical to the previous hand-written palette. A missing required token surfaces as a
// Kotlin "no value passed for parameter" compile error — the compile-time safety XML would otherwise lose.
val generateThemeSources = tasks.register("generateThemeSources") {
    val themesDir = layout.projectDirectory.dir("themes")
    val outDir = layout.buildDirectory.dir("generated/theme/commonMain/kotlin")
    inputs.dir(themesDir)
    outputs.dir(outDir)
    doLast {
        val out = outDir.get()

        fun hexToColor(raw: String): String {
            val h = raw.trim().removePrefix("#").uppercase()
            val argb = if (h.length == 6) "FF$h" else h
            require(argb.length == 8 && argb.all { it in "0123456789ABCDEF" }) { "Bad color hex: '$raw'" }
            return "Color(0x$argb)"
        }
        fun alphaLiteral(a: String): String = a.trim().let { if (it.contains('.')) "${it}f" else "$it.0f" }

        fun parse(fileName: String): org.w3c.dom.Document =
            javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .apply { isIgnoringComments = true }
                .newDocumentBuilder()
                .parse(themesDir.file(fileName).asFile)

        fun org.w3c.dom.Element.elements(tag: String): List<org.w3c.dom.Element> {
            val nl = getElementsByTagName(tag)
            return (0 until nl.length).map { nl.item(it) as org.w3c.dom.Element }
        }

        // --- Chrome themes → SkerryColors builder functions ---
        val chromeDoc = parse("chrome-themes.xml")
        val chromeSb = StringBuilder()
        chromeSb.append(
            """
            |// Generated from composeApp/themes/chrome-themes.xml by :composeApp:generateThemeSources — do not edit.
            |package app.skerry.ui.theme
            |
            |import androidx.compose.ui.graphics.Color
            |
            """.trimMargin() + "\n",
        )
        for (chrome in chromeDoc.documentElement.elements("chrome")) {
            val fn = chrome.getAttribute("fn")
            val colors = chrome.elements("color")
            // Literal tokens first so alpha-over-<token> can inline the base literal.
            val literals = colors.filter { !it.hasAttribute("over") }
                .associate { it.getAttribute("name") to hexToColor(it.textContent) }
            chromeSb.append("/** Generated ${chrome.getAttribute("id")} palette. */\n")
            chromeSb.append("fun $fn(): SkerryColors = SkerryColors(\n")
            for (color in colors) {
                val name = color.getAttribute("name")
                val expr = if (color.hasAttribute("over")) {
                    val over = color.getAttribute("over")
                    val base = if (over.startsWith("#")) hexToColor(over)
                    else literals[over] ?: error("Token '$name' references unknown base '$over'")
                    "$base.copy(alpha = ${alphaLiteral(color.getAttribute("alpha"))})"
                } else {
                    hexToColor(color.textContent)
                }
                chromeSb.append("    $name = $expr,\n")
            }
            chromeSb.append(")\n\n")
        }
        val chromeFile = out.file("app/skerry/ui/theme/GeneratedChromeThemes.kt").asFile
        chromeFile.parentFile.mkdirs()
        chromeFile.writeText(chromeSb.toString().trimEnd() + "\n")

        // --- Terminal themes → TerminalThemes catalog object ---
        val termDoc = parse("terminal-themes.xml")
        val terminals = termDoc.documentElement.elements("terminal")
        val termSb = StringBuilder()
        termSb.append(
            """
            |// Generated from composeApp/themes/terminal-themes.xml by :composeApp:generateThemeSources — do not edit.
            |package app.skerry.ui.terminal
            |
            |import androidx.compose.ui.graphics.Color
            |
            |/** Built-in terminal theme catalog. Order matches the Appearance card order. */
            |object TerminalThemes {
            """.trimMargin() + "\n",
        )
        for (term in terminals) {
            val symbol = term.getAttribute("symbol")
            val ansi = term.elements("ansi").single().textContent.trim().split(Regex("\\s+"))
            require(ansi.size == 16) { "Terminal '${term.getAttribute("id")}' must list 16 ANSI colors, got ${ansi.size}" }
            termSb.append("    val $symbol: TerminalTheme = TerminalTheme(\n")
            termSb.append("        id = \"${term.getAttribute("id")}\",\n")
            termSb.append("        displayName = \"${term.getAttribute("name")}\",\n")
            termSb.append("        background = ${hexToColor(term.elements("background").single().textContent)},\n")
            termSb.append("        foreground = ${hexToColor(term.elements("foreground").single().textContent)},\n")
            termSb.append("        cursor = ${hexToColor(term.elements("cursor").single().textContent)},\n")
            termSb.append("        ansi = listOf(\n")
            ansi.chunked(4).forEach { row ->
                termSb.append("            " + row.joinToString(", ") { hexToColor(it) } + ",\n")
            }
            termSb.append("        ),\n")
            term.elements("selection").firstOrNull()?.let {
                termSb.append("        selection = ${hexToColor(it.textContent)},\n")
            }
            termSb.append("    )\n\n")
        }
        val order = terminals.joinToString(", ") { it.getAttribute("symbol") }
        val default = terminals.single { it.getAttribute("default") == "true" }.getAttribute("symbol")
        termSb.append("    val all: List<TerminalTheme> = listOf($order)\n\n")
        termSb.append("    val DEFAULT: TerminalTheme = $default\n\n")
        termSb.append("    /** Theme by stable [TerminalTheme.id]; unknown/`null`/empty id falls back to [DEFAULT]. */\n")
        termSb.append("    fun fromId(id: String?): TerminalTheme = all.firstOrNull { it.id == id } ?: DEFAULT\n")
        termSb.append("}\n")
        val termFile = out.file("app/skerry/ui/terminal/GeneratedTerminalThemes.kt").asFile
        termFile.parentFile.mkdirs()
        termFile.writeText(termSb.toString())
    }
}
kotlin.sourceSets.named("commonMain") { kotlin.srcDir(generateThemeSources) }

compose.desktop {
    application {
        mainClass = "app.skerry.ui.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Dmg)
            // jlink drops modules loaded reflectively (jdk.unsupported, java.naming, GSSAPI, …),
            // so the packaged app died on startup while `./gradlew run` worked. Bundle them all.
            includeAllModules = true
            packageName = "Skerry"
            // Version from the single source (gradle.properties); the release workflow overrides it.
            val appVersion = providers.gradleProperty("skerry.versionName").orNull ?: "0.1.0"
            packageVersion = appVersion
            // macOS-only: jpackage validates --app-version and rejects a zero major, so force
            // the mac package version's major to >=1. The in-app About version (AppVersion.kt)
            // stays on skerry.versionName, so only the dmg bundle metadata carries this.
            val macVersion = if (appVersion.substringBefore('.') == "0" && appVersion.contains('.'))
                "1${appVersion.substring(appVersion.indexOf('.'))}" else appVersion
            // App icons per platform, rasterized from icons/skerry.svg (canonical mark, docs/design/Skerry Logo.html).
            linux { iconFile.set(project.file("icons/skerry.png")) }
            windows {
                iconFile.set(project.file("icons/skerry.ico"))
                // Space-free install path (%LOCALAPPDATA%\Skerry): goterl/ionspin's isJarFile()
                // throws on the space in "C:\Program Files" and then crashes libsodium init.
                perUserInstall = true
                menu = true
                shortcut = true
            }
            macOS {
                iconFile.set(project.file("icons/skerry.icns"))
                packageVersion = macVersion
            }
        }

        // ProGuard is DISABLED for release on purpose. For the crypto stack of this SSH client
        // minification produced only release-only breakage, unfixable within a .pro file:
        //   1) JNA/libsodium — reflective access from native code (UnsatisfiedLinkError);
        //   2) okio — optimization specialized a return type → VerifyError "Bad return
        //      type" on the very first file read (looked like "Storage is damaged");
        //   3) BouncyCastle — lazy registration of the "BC" provider did not kick in → NPE;
        //   4) BouncyCastle — bcprov is a SIGNED jar: ProGuard rewrites the class
        //      bytecode but carries over the old META-INF with the signature → JarVerifier fails with
        //      "SHA-256 digest error for .../BouncyCastleProvider.class". Stripping the signature
        //      of a third-party jar purely via .pro is impossible (those are -injars filters of the Compose plugin).
        // Distribution size is non-critical for a desktop app; crypto correctness is
        // critical. This way release behaves like debug. The rules are kept in compose-desktop.pro
        // in case it is re-enabled (which would then require handling the bcprov signature).
        buildTypes.release.proguard {
            isEnabled.set(false)
            configurationFiles.from(project.file("compose-desktop.pro"))
        }
    }
}

// Wrap the jpackage app-image (createDistributable) into a portable Skerry.AppImage. jpackage
// itself has no AppImage target, so a small script assembles the AppDir and runs appimagetool.
// The version and paths mirror the other packaging tasks; the release workflow sets versionName.
tasks.register<Exec>("packageAppImage") {
    group = "compose desktop"
    description = "Build a portable Linux .AppImage from the jpackage app-image"
    dependsOn("createDistributable")

    val appImageDir = layout.buildDirectory.dir("compose/binaries/main/appimage")
    workingDir = project.projectDir
    commandLine("bash", project.file("appimage/package-appimage.sh").absolutePath)
    environment("APP_DIR", layout.buildDirectory.dir("compose/binaries/main/app/Skerry").get().asFile.absolutePath)
    environment("APPIMAGE_DIR", appImageDir.get().asFile.absolutePath)
    environment("ASSET_DIR", project.file("appimage").absolutePath)
    environment("ICON_PNG", project.file("icons/skerry.png").absolutePath)
    environment("VERSION", providers.gradleProperty("skerry.versionName").orNull ?: "0.1.0")
}

// Build a single-file Skerry.flatpak via flatpak-builder. Unlike the other packaging tasks this
// does NOT depend on createDistributable: flatpak-builder compiles the app hermetically inside the
// sandbox from the committed offline sources (composeApp/flatpak/flatpak-sources.json). The task
// just shells out to the build script; flatpak + flatpak-builder must be on the runner.
tasks.register<Exec>("packageFlatpak") {
    group = "compose desktop"
    description = "Build a single-file Linux .flatpak bundle (hermetic source build)"
    workingDir = project.projectDir
    commandLine("bash", project.file("flatpak/package-flatpak.sh").absolutePath)
    environment("VERSION", providers.gradleProperty("skerry.versionName").orNull ?: "0.1.0")
}

// Offscreen render of the design to PNG (visual check without a window). See design/Screenshot.kt.
// Parameters: -Dskerry.screenshot.{out,view,overlay,live,device}. device=mobile renders the phone
// layout (MobileDesignApp); view is then a MobileTab name. Not part of the distribution.
tasks.register<JavaExec>("screenshotDesign") {
    group = "verification"
    description = "Render Desktop/Mobile DesignApp to a PNG via ImageComposeScene"
    val desktopMainComp = kotlin.targets.getByName("desktop").compilations.getByName("main")
    dependsOn(desktopMainComp.compileTaskProvider)
    classpath(desktopMainComp.output.allOutputs, desktopMainComp.runtimeDependencyFiles)
    mainClass.set("app.skerry.ui.desktop.ScreenshotKt")
    systemProperty("skerry.screenshot.out", providers.systemProperty("skerry.screenshot.out").getOrElse("/tmp/skerry_design.png"))
    systemProperty("skerry.screenshot.view", providers.systemProperty("skerry.screenshot.view").getOrElse("Terminal"))
    systemProperty("skerry.screenshot.overlay", providers.systemProperty("skerry.screenshot.overlay").getOrElse(""))
    systemProperty("skerry.screenshot.live", providers.systemProperty("skerry.screenshot.live").getOrElse("false"))
    systemProperty("skerry.screenshot.device", providers.systemProperty("skerry.screenshot.device").getOrElse("desktop"))
    providers.systemProperty("skerry.screenshot.settingsTab").orNull?.let { systemProperty("skerry.screenshot.settingsTab", it) }
    providers.systemProperty("skerry.screenshot.termTheme").orNull?.let { systemProperty("skerry.screenshot.termTheme", it) }
    providers.systemProperty("skerry.screenshot.theme").orNull?.let { systemProperty("skerry.screenshot.theme", it) }
    providers.systemProperty("skerry.screenshot.portsScan").orNull?.let { systemProperty("skerry.screenshot.portsScan", it) }
    providers.systemProperty("skerry.screenshot.locale").orNull?.let { systemProperty("skerry.screenshot.locale", it) }
    providers.systemProperty("skerry.screenshot.aiProvider").orNull?.let { systemProperty("skerry.screenshot.aiProvider", it) }
    providers.systemProperty("skerry.screenshot.updateAvailable").orNull?.let { systemProperty("skerry.screenshot.updateAvailable", it) }
    // Stub window chrome: draws the custom window buttons of the undecorated window in the titlebar.
    systemProperty("skerry.screenshot.windowChrome", providers.systemProperty("skerry.screenshot.windowChrome").getOrElse("false"))
}

// Kover coverage — applied via pluginManager (classpath comes from the root buildscript) so the
// offline Flatpak build, which sets -Dskerry.offlineRepo, never resolves it. See the root build.
if (System.getProperty("skerry.offlineRepo") == null) {
    pluginManager.apply("org.jetbrains.kotlinx.kover")
    // Compose Hot Reload — same online-only gate: adds the dev `hotRunJvm` task (live UI reload on
    // the desktop target). Never applied in the offline packaging build (no dev tasks needed there).
    pluginManager.apply("org.jetbrains.compose.hot-reload")
}
