import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    jvmToolchain(21)

    jvm("desktop") {
        // kotlin("test") выбирает бэкенд по конфигурации Test-задачи: это включает JUnit 5
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "SkerryShared"
            isStatic = true
        }
    }

    androidLibrary {
        namespace = "app.skerry.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }
    }

    sourceSets {
        commonMain.dependencies {
            // api: Flow участвует в публичном контракте ssh (ShellChannel.output)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // единый VaultCrypto на всех таргетах (Argon2id + XChaCha20-Poly1305)
            implementation(libs.ionspin.libsodium)
            // api: okio.Path/FileSystem — в публичном конструкторе FileVault (commonMain)
            api(libs.okio)
            // мультиплатформенные локи вместо JVM-only @Synchronized (деталь реализации FileVault)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            // in-memory FileSystem для тестов FileVault без реальной ФС
            implementation(libs.okio.fakefilesystem)
        }
        androidMain.dependencies {
            // BiometricPrompt + CryptoObject для AndroidBiometricKeyStore (Keystore-огороженный ключ)
            implementation(libs.androidx.biometric)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.sshj)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.sshd.core)
                // SFTP-подсистема встроенного сервера для интеграционных тестов SshjSftpClient
                implementation(libs.sshd.sftp)
            }
        }
    }
}
