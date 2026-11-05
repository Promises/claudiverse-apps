package dev.claudiverse.core

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout

/** An HTTP client on this platform's engine: OkHttp on Android, URLSession on macOS. */
internal expect fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient

/** A request's whole time, after which it fails: a poll stuck on a dead connection must not stop its loop. */
internal const val REQUEST_TIMEOUT_MS = 60_000L

/** A transfer's (an upload, the app update's download): long enough for a big file on a slow line, but still ending. */
internal const val TRANSFER_TIMEOUT_MS = 15 * 60_000L

/**
 * The client the API and the socket share. A request that cannot connect in
 * 10 s, goes 30 s without a byte, or takes more than [REQUEST_TIMEOUT_MS] in
 * all fails rather than hanging the screen. The last matters on macOS, whose
 * engine may not apply the byte timeout: a seat-list poll that hung when the
 * proxy died mid-request (2026-11-05) froze the list for five hours. An
 * upload or a download has [TRANSFER_TIMEOUT_MS] instead.
 */
internal fun createHttpClient(): HttpClient = platformHttpClient { timeouts() }

/** The timeouts every client of ours has ([requestMs] shorter only in a test). */
internal fun HttpClientConfig<*>.timeouts(requestMs: Long = REQUEST_TIMEOUT_MS) {
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 30_000
        requestTimeoutMillis = requestMs
    }
}
