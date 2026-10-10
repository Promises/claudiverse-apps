package dev.claudiverse.core

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

internal actual fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp) {
    // OkHttp's own WebSocket ping, a second line under the Phoenix heartbeat:
    // it fails the socket when a pong does not come back.
    engine { config { pingInterval(15, TimeUnit.SECONDS) } }
    block()
}
