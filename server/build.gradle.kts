plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.ktor)
}

group = "app.skerry"
version = "0.1.2"

application {
    mainClass.set("app.skerry.server.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Client⇆server wire contract (shared with shared/sync — a single source of DTOs).
    implementation(project(":sync-wire"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.cors)
    // Security hardening: rate-limit (anti-flood per IP) and security headers (DefaultHeaders).
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.logback.classic)
    // Coroutines: Exposed suspend transactions (newSuspendedTransaction) take DB work off the request thread.
    implementation(libs.kotlinx.coroutines.core)

    // Storage layer: Exposed + HikariCP; SQLite by default, PostgreSQL optionally via DB URL.
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.hikari)
    runtimeOnly(libs.sqlite.jdbc)
    runtimeOnly(libs.postgresql)

    // SRP-6a: the server stores only the verifier; the client's password/authKey is never transmitted.
    implementation(libs.nimbus.srp)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    // WS client for /sync tests: Close-frame handling and revoke are verified with a real handshake.
    testImplementation(libs.ktor.client.websockets)
    testImplementation(kotlin("test"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Kover coverage — applied via pluginManager (classpath comes from the root buildscript) so the
// offline Flatpak build, which sets -Dskerry.offlineRepo, never resolves it. See the root build.
if (System.getProperty("skerry.offlineRepo") == null) {
    pluginManager.apply("org.jetbrains.kotlinx.kover")
}
