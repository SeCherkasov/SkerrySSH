import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinxSerialization)
}

// desktopOnly (see settings.gradle.kts): the Flatpak build excludes :server, so the desktopTest
// dependency on it must drop out too. Tests never run in that build, so this only affects config.
val desktopOnly = providers.gradleProperty("desktopOnly").isPresent ||
    System.getenv("SKERRY_DESKTOP_ONLY") == "1"

kotlin {
    jvmToolchain(21)

    // expect/actual for classes/objects (SerialSystem) is still Beta — the flag removes a noisy warning.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm("desktop") {
        // kotlin("test") picks its backend from the Test task configuration: this enables JUnit 5
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }

    androidLibrary {
        namespace = "app.skerry.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }
    }

    // Default hierarchy template + a shared JVM node jvmShared (desktop JVM + Android) under commonMain.
    // Customizing the template (rather than manual dependsOn) stays compatible with the default target grouping.
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                // withAndroidTarget() only matches the old KotlinAndroidTarget; the target of the new
                // com.android.kotlin.multiplatform.library plugin is caught with a predicate on its name.
                withCompilations { it.target.name == "android" }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            // api: Flow is part of the public ssh contract (ShellChannel.output)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // a single VaultCrypto on all targets (Argon2id + XChaCha20-Poly1305)
            implementation(libs.ionspin.libsodium)
            // api: okio.Path/FileSystem appear in the public FileVault constructor (commonMain)
            api(libs.okio)
            // multiplatform locks instead of JVM-only @Synchronized (FileVault implementation detail)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            // in-memory FileSystem for FileVault tests without a real FS
            implementation(libs.okio.fakefilesystem)
        }
        // Shared JVM source set (created by the template above): pure JVM/Java API for desktop and Android —
        // sshj transport, SFTP client, file-based host/known-hosts stores.
        val jvmSharedMain by getting {
            dependencies {
                // Client⇆server wire contract (shared with server — a single source of DTOs instead of a mirror).
                implementation(project(":sync-wire"))
                implementation(libs.sshj)
                // sshj logs via slf4j-api; without a binding SLF4J prints "No SLF4J providers
                // were found" and goes NOP. An explicit no-op provider removes that warning on
                // both targets (desktop + Android). runtimeOnly — the code never references slf4j-nop.
                runtimeOnly(libs.slf4j.nop)
                // Explicit: sshj's transitive bcprov is not visible on the compile classpath, while SshjTransport
                // references BouncyCastleProvider to replace the stripped-down system "BC" on Android.
                implementation(libs.bouncycastle.prov)
                // sshj pins bcpkix/bcutil to 1.80; without this they stay behind the bumped bcprov (1.85),
                // and 1.80's bcpkix references OIDs that moved in 1.85 -> NoSuchFieldError at runtime.
                // Pinning bcpkix to the same version aligns bcutil transitively.
                runtimeOnly(libs.bouncycastle.pkix)
                // Sync client (Phase 2): HTTP+WS to the self-hosted server. iOS is deferred — the client
                // lives in the shared JVM node (desktop + Android), same as the sshj transport.
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.client.websockets)
                implementation(libs.ktor.serialization.kotlinx.json)
                // SRP-6a client side (verifier generator + client exchange).
                implementation(libs.nimbus.srp)
                // Local AI (Phase 3): llama.cpp behind a KMP binding — on-device GGUF
                // inference (desktop JVM + Android arm64) with no external calls. A single
                // LocalLlmRuntime implementation for both targets (LlamatikRuntime).
                implementation(libs.llamatik)
            }
        }
        val desktopMain by getting {
            dependencies {
                // Native access to serial ports (SerialSystem actual). Desktop only —
                // on Android, serial will go over USB-OTG as a separate implementation.
                implementation(libs.jserialcomm)
            }
        }
        androidMain.dependencies {
            // BiometricPrompt + CryptoObject for AndroidBiometricKeyStore (Keystore-fenced key)
            implementation(libs.androidx.biometric)
            // USB-OTG serial: CDC-ACM/FTDI/CP210x/CH34x drivers on top of the USB Host API (SerialSystem actual).
            implementation(libs.usbserial)
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.sshd.core)
                // SFTP subsystem of the embedded server for SshjSftpClient integration tests
                implementation(libs.sshd.sftp)
                // e2e sync: a real self-hosted server is started inside the test, the client talks
                // to it over real HTTP — proof of the zero-knowledge round-trip. Skipped in the
                // desktopOnly (Flatpak) build, which excludes :server from the settings graph.
                if (!desktopOnly) implementation(project(":server"))
                // The Netty engine + ktor core are needed by the test to start embeddedServer (for :server
                // these are implementation dependencies and do not reach the test transitively).
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.netty)
                // MockEngine: TDD for HTTP clients (OpenAiProvider) without a real network — we verify
                // the request (url/headers/body) and feed back canonical responses.
                implementation(libs.ktor.client.mock)
            }
        }
    }
}

// Kover coverage — applied via pluginManager (classpath comes from the root buildscript) so the
// offline Flatpak build, which sets -Dskerry.offlineRepo, never resolves it. See the root build.
if (System.getProperty("skerry.offlineRepo") == null) {
    pluginManager.apply("org.jetbrains.kotlinx.kover")
}
