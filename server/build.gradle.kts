plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.ktor)
}

group = "app.skerry"
version = "0.1.0"

application {
    mainClass.set("app.skerry.server.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
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
    implementation(libs.ktor.server.config.yaml)
    // Security-хардненинг: rate-limit (anti-flood по IP) и security-заголовки (DefaultHeaders).
    // Версия пинуется из того же каталога, что и остальной Ktor, без правки самого каталога.
    implementation("io.ktor:ktor-server-rate-limit:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-server-default-headers:${libs.versions.ktor.get()}")
    implementation(libs.logback.classic)
    // Корутины: suspend-транзакции Exposed (newSuspendedTransaction) уводят БД с потока запроса.
    implementation(libs.kotlinx.coroutines.core)

    // Слой хранения: Exposed + HikariCP; SQLite по умолчанию, PostgreSQL — опционально по DB URL.
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.hikari)
    runtimeOnly(libs.sqlite.jdbc)
    runtimeOnly(libs.postgresql)

    // SRP-6a: сервер хранит только verifier, пароль/authKey клиента не передаётся.
    implementation(libs.nimbus.srp)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}
