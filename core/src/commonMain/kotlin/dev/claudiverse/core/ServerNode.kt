package dev.claudiverse.core

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Which copy of the server answered ("blue", "green"; null for a server
 * running alone) and whether it is the live one. Two copies run side by side
 * behind the proxy, and only the active one has the seats' live rooms.
 */
data class ServerNode(val node: String?, val active: Boolean) {
    companion object {
        /** From a join reply's or a whoami's response, or /api/health's body; null when it says neither. */
        fun from(o: JsonObject?): ServerNode? {
            if (o == null || ("node" !in o && "active" !in o)) return null
            return ServerNode(o.str("node"), o.bool("active") ?: true)
        }
    }
}

/**
 * Whether the socket must be reopened: it is joined on a copy that is not the
 * live one. On 2026-11-05 a proxy restart sent the Mac's socket to the standby
 * for its first second, and it stayed there for five hours: every join
 * succeeded with an empty replay. [socket] is what the socket itself says
 * (a join reply, a whoami); [live] what /api/health says on a fresh
 * connection, so never through a connection that might be pinned too.
 */
fun mustReconnect(socket: ServerNode?, live: ServerNode?): Boolean = when {
    socket == null -> false
    !socket.active -> true
    live == null || !live.active || live.node == null || socket.node == null -> false
    else -> live.node != socket.node
}

/**
 * /api/health on a client of its own, closed after: a new connection, so the
 * answer comes from the copy the proxy now sends new connections to. Null
 * when it cannot be read.
 */
@Throws(CancellationException::class)
suspend fun readLiveNode(baseUrl: String): ServerNode? {
    val client = createHttpClient()
    return try {
        val r = client.get(baseUrl.trimEnd('/') + "/api/health") { headers.append(HttpHeaders.Connection, "close") }
        if (!r.status.isSuccess()) null else ServerNode.from(Json.parseToJsonElement(r.bodyAsText()) as? JsonObject)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    } finally {
        client.close()
    }
}
