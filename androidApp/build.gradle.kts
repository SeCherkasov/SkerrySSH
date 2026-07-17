import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.composeApp)
    implementation(libs.androidx.activity.compose)
    // FragmentActivity — host of the biometric prompt (androidx.biometric)
    implementation(libs.androidx.fragment)
}

android {
    namespace = "app.skerry.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.android.buildTools.get()

    defaultConfig {
        applicationId = "app.skerry.onepve"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Version from the single source (gradle.properties); the release workflow overrides it.
        versionCode = (providers.gradleProperty("skerry.versionCode").orNull ?: "1").toInt()
        versionName = providers.gradleProperty("skerry.versionName").orNull ?: "0.1.0"
        // Local AI (Llamatik/llama.cpp): the AAR carries natives for four ABIs (~52 MB unpacked).
        // The project's real Android devices are arm64; the other ABIs are dead weight in the APK.
        ndk { abiFilters += "arm64-v8a" }
    }
    packaging {
        resources {
            // LICENSE.md is shipped in every BouncyCastle jar (bcprov/bcpkix/bcutil) since 1.85;
            // three identical copies would collide on merge, so drop the license notices.
            excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE.md}"
        }
        // .so files are page-mapped straight from the uncompressed APK (Llamatik's 16KB alignment is kept),
        // extractNativeLibs is not needed.
        jniLibs { useLegacyPackaging = false }
    }
    // Release signing from properties/environment (GH Secrets → -Pskerry.* or SKERRY_* env).
    // The keystore is never stored in the repo (.gitignore); if it is absent the release is built
    // unsigned, so local builds and forks without secrets don't fail.
    fun signingValue(prop: String, env: String): String? =
        providers.gradleProperty(prop).orNull ?: providers.environmentVariable(env).orNull
    val keystorePath = signingValue("skerry.keystoreFile", "SKERRY_KEYSTORE_FILE")
    val keystoreFile = keystorePath?.let { rootProject.file(it) }
    val hasKeystore = keystoreFile?.exists() == true
    if (hasKeystore) {
        signingConfigs {
            create("release") {
                storeFile = keystoreFile
                storePassword = signingValue("skerry.keystorePassword", "SKERRY_KEYSTORE_PASSWORD")
                keyAlias = signingValue("skerry.keyAlias", "SKERRY_KEY_ALIAS")
                keyPassword = signingValue("skerry.keyPassword", "SKERRY_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
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
