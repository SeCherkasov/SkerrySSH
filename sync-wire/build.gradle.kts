plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinxSerialization)
}

group = "app.skerry"
version = "0.1.4"

kotlin {
    jvmToolchain(21)
}

dependencies {
    // api: the contract's @Serializable types are visible to consumers together with their serializers.
    api(libs.kotlinx.serialization.json)
}

// Kover coverage — applied via pluginManager (classpath comes from the root buildscript) so the
// offline Flatpak build, which sets -Dskerry.offlineRepo, never resolves it. See the root build.
if (System.getProperty("skerry.offlineRepo") == null) {
    pluginManager.apply("org.jetbrains.kotlinx.kover")
}
