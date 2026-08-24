plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinxSerialization)
}

group = "app.skerry"
version = "0.4.1"

kotlin {
    jvmToolchain(21)
}

dependencies {
    // api: the contract's @Serializable types are visible to consumers together with their serializers.
    api(libs.kotlinx.serialization.json)
}

// Kover coverage — applied via pluginManager; the classpath comes from the root buildscript.
pluginManager.apply("org.jetbrains.kotlinx.kover")
