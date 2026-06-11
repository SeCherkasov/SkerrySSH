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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.sshj)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.sshd.core)
            }
        }
    }
}
