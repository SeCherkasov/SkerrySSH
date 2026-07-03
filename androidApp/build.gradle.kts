import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.composeApp)
    implementation(libs.androidx.activity.compose)
    // FragmentActivity — хост биометрического промпта (androidx.biometric)
    implementation(libs.androidx.fragment)
}

android {
    namespace = "app.skerry.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.android.buildTools.get()

    defaultConfig {
        applicationId = "app.skerry"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        // Локальный AI (Llamatik/llama.cpp): AAR несёт нативы четырёх ABI (~52 МБ распакованных).
        // Реальные Android-устройства проекта — arm64; остальные ABI — мёртвый вес в APK.
        ndk { abiFilters += "arm64-v8a" }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // .so льются страницами из несжатого APK (16KB-выравнивание Llamatik сохраняется),
        // extractNativeLibs не нужен.
        jniLibs { useLegacyPackaging = false }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}
