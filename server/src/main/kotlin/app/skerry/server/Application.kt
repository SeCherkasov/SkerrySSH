package app.skerry.server

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Self-hosted sync-сервер Skerry (Phase 2). Пока только каркас с health-check;
 * REST/WS API и модель VaultRecord — `docs/skerry-sync-design.md`.
 */
fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    routing {
        get("/healthz") {
            call.respondText("ok")
        }
    }
}
