plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.ktor)
}

group = "app.skerry"
version = "0.3.0"

// Second launcher in the same distribution: `bin/skerry-admin` (the administration CLI) ships next
// to `bin/server`, so the Docker image gets it for free — the Dockerfile copies the whole install
// directory. One jar, one classpath, no separate module.
val adminCliScripts = tasks.register<CreateStartScripts>("adminCliStartScripts") {
    mainClass.set("app.skerry.server.cli.AdminCliRunnerKt")
    applicationName = "skerry-admin"
    // Outside build/scripts on purpose: the `application` plugin copies that whole directory into
    // bin/, so a subdirectory of it would land in the distribution twice.
    outputDir = layout.buildDirectory.dir("adminCliScripts").get().asFile
    classpath = tasks.startScripts.get().classpath
}

application {
    mainClass.set("app.skerry.server.ApplicationKt")
    // filePermissions: files added through `from` don't inherit the executable bit that
    // CreateStartScripts sets, and a non-executable launcher is a silent "command not found".
    applicationDistribution.from(adminCliScripts) {
        into("bin")
        filePermissions { unix("755") }
    }
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
    // Observability: Prometheus exposition on /metrics. The Micrometer registry also brings JVM,
    // process and HikariCP instrumentation under their standard names, so off-the-shelf Grafana
    // dashboards work — that is the reason for the dependency over a hand-rolled exposition writer.
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.logback.classic)
    // HTTP client for the `skerry-admin` CLI (second launcher in this module): it drives the same
    // /admin endpoints as the console instead of reaching into the database behind the server's back.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
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
    // Argon2id for the web password (see WebPasswordHasher). BouncyCastle rather than the libsodium
    // binding the client uses: it is pure Java, so the server keeps running wherever a JVM does and
    // an operator's `docker compose up` never depends on a native library resolving.
    implementation(libs.bouncycastle.prov)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    // WS client for /sync tests: Close-frame handling and revoke are verified with a real handshake.
    testImplementation(libs.ktor.client.websockets)
    // MockEngine: lets the CLI tests drive responses a real server can't produce (a malformed body).
    testImplementation(libs.ktor.client.mock)
    testImplementation(kotlin("test"))
}

// Stamp the project version into version.properties so the runtime never drifts from this file.
// Captured into a task-local val: referencing `version` (or a script-level val) inside
// filesMatching would drag the script object into the closure and break the configuration cache.
tasks.processResources {
    val projectVersion = project.version.toString()
    inputs.property("version", projectVersion)
    filesMatching("version.properties") { expand("version" to projectVersion) }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Lets tests assert that the reported server version matches this build file's version.
    systemProperty("skerry.projectVersion", version)
}

// Kover coverage — applied via pluginManager; the classpath comes from the root buildscript.
pluginManager.apply("org.jetbrains.kotlinx.kover")
