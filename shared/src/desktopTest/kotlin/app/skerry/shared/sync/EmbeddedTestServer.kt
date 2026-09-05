package app.skerry.shared.sync

import app.skerry.server.config.ServerConfig
import app.skerry.server.module
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * The real server, in-process, for the end-to-end tests.
 *
 * Bound to loopback on purpose: [ServerConfig] leaves registration open by default, and Ktor with no
 * `host` binds every interface — so a `./gradlew allTests` on a laptop would publish an
 * unauthenticated `POST /register` to whatever network it is on, for as long as the test runs.
 */
internal fun startTestServer(config: ServerConfig, port: Int) =
    embeddedServer(Netty, host = "127.0.0.1", port = port) { module(config) }.start(wait = false)
