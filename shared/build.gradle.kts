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

    androidLibrary {
        namespace = "app.skerry.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }
    }

    // Дефолтный шаблон иерархии + общий JVM-узел jvmShared (desktop JVM + Android) под commonMain.
    // Кастомизация шаблона (а не ручные dependsOn) совместима с дефолтной группировкой таргетов.
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                // withAndroidTarget() матчит только старый KotlinAndroidTarget; target нового
                // плагина com.android.kotlin.multiplatform.library ловим предикатом по имени.
                withCompilations { it.target.name == "android" }
            }
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
        // Общий JVM source set (создан шаблоном выше): чистый JVM/Java API для desktop и Android —
        // sshj-транспорт, SFTP-клиент, файловые сторы host/known-hosts.
        val jvmSharedMain by getting {
            dependencies {
                implementation(libs.sshj)
                // Явно: транзитивный bcprov sshj не виден на compile classpath, а SshjTransport
                // ссылается на BouncyCastleProvider для подмены урезанного системного «BC» на Android.
                implementation(libs.bouncycastle.prov)
                // Sync-клиент (Phase 2): HTTP+WS к self-hosted серверу. iOS отложен — клиент
                // живёт в общем JVM-узле (desktop + Android), как и sshj-транспорт.
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.client.websockets)
                implementation(libs.ktor.serialization.kotlinx.json)
                // SRP-6a клиентская сторона (verifier-генератор + клиентский обмен).
                implementation(libs.nimbus.srp)
            }
        }
        androidMain.dependencies {
            // BiometricPrompt + CryptoObject для AndroidBiometricKeyStore (Keystore-огороженный ключ)
            implementation(libs.androidx.biometric)
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.sshd.core)
                // SFTP-подсистема встроенного сервера для интеграционных тестов SshjSftpClient
                implementation(libs.sshd.sftp)
                // e2e sync: реальный self-hosted сервер поднимается в тесте, клиент ходит к нему
                // по настоящему HTTP — доказательство zero-knowledge round-trip.
                implementation(project(":server"))
                // Движок Netty + ktor core нужны тесту, чтобы поднять embeddedServer (у :server
                // это implementation-зависимости и в тест транзитивно не приходят).
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.netty)
                // MockEngine: TDD HTTP-клиентов (OpenAiProvider) без реальной сети — проверяем
                // запрос (url/заголовки/тело) и скармливаем каноничные ответы.
                implementation(libs.ktor.client.mock)
            }
        }
    }
}
