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
            }
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "app.skerry.ui.generated.resources"
}

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
            packageVersion = providers.gradleProperty("skerry.versionName").orNull ?: "0.1.0"
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
            macOS { iconFile.set(project.file("icons/skerry.icns")) }
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
    providers.systemProperty("skerry.screenshot.aiProvider").orNull?.let { systemProperty("skerry.screenshot.aiProvider", it) }
}
