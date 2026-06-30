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
        // kotlin("test") выбирает бэкенд по конфигурации Test-задачи: это включает JUnit 5
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
            // androidx.activity.compose.BackHandler — перехват системного «назад» (PlatformBackHandler).
            implementation(libs.androidx.activity.compose)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
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
            packageName = "Skerry"
            packageVersion = "1.0.0"
        }

        // ProGuard для release ОТКЛЮЧЁН осознанно. Для крипто-стека этого SSH-клиента
        // минификация давала только release-only поломки, неустранимые в рамках .pro:
        //   1) JNA/libsodium — рефлексивный доступ из нативного кода (UnsatisfiedLinkError);
        //   2) okio — оптимизация специализировала тип возврата → VerifyError «Bad return
        //      type» на первом же чтении файла (выглядело как «Storage is damaged»);
        //   3) BouncyCastle — ленивая регистрация провайдера «BC» не срабатывала → NPE;
        //   4) BouncyCastle — bcprov это ПОДПИСАННЫЙ jar: ProGuard переписывает байткод
        //      классов, но тащит старый META-INF с подписью → JarVerifier роняет
        //      «SHA-256 digest error for .../BouncyCastleProvider.class». Снять подпись
        //      чужого jar чисто через .pro нельзя (это фильтры -injars Compose-плагина).
        // Размер дистрибутива для desktop-приложения некритичен; корректность крипты —
        // критична. Так release ведёт себя как debug. Правила сохранены в compose-desktop.pro
        // на случай повторного включения (тогда потребуется обработка подписи bcprov).
        buildTypes.release.proguard {
            isEnabled.set(false)
            configurationFiles.from(project.file("compose-desktop.pro"))
        }
    }
}

// Офскрин-рендер дизайна в PNG (визуальная проверка без окна). См. design/Screenshot.kt.
// Параметры: -Dskerry.screenshot.{out,view,overlay,live,device}. device=mobile рендерит телефонный
// макет (MobileDesignApp); view тогда — имя MobileTab. Не входит в дистрибутив.
tasks.register<JavaExec>("screenshotDesign") {
    group = "verification"
    description = "Render Desktop/Mobile DesignApp to a PNG via ImageComposeScene"
    val desktopMainComp = kotlin.targets.getByName("desktop").compilations.getByName("main")
    dependsOn(desktopMainComp.compileTaskProvider)
    classpath(desktopMainComp.output.allOutputs, desktopMainComp.runtimeDependencyFiles)
    mainClass.set("app.skerry.ui.design.ScreenshotKt")
    systemProperty("skerry.screenshot.out", providers.systemProperty("skerry.screenshot.out").getOrElse("/tmp/skerry_design.png"))
    systemProperty("skerry.screenshot.view", providers.systemProperty("skerry.screenshot.view").getOrElse("Terminal"))
    systemProperty("skerry.screenshot.overlay", providers.systemProperty("skerry.screenshot.overlay").getOrElse(""))
    systemProperty("skerry.screenshot.live", providers.systemProperty("skerry.screenshot.live").getOrElse("false"))
    systemProperty("skerry.screenshot.device", providers.systemProperty("skerry.screenshot.device").getOrElse("desktop"))
}
