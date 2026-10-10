package dev.claudiverse.core

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout

/** An HTTP client on this platform's engine: OkHttp on Android, URLSession on macOS. */
internal expect fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient

/**
 * The client the API and the socket share. A request that cannot connect in
 * 10 s, or goes 30 s without a byte, fails rather than hanging the screen.
 */
internal fun createHttpClient(): HttpClient = platformHttpClient {
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 30_000
    }
}
