package dev.ztripez.massmate

import java.net.URI
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Small text transport seam for the first Sendspin connection path. */
interface SendspinTransport {
    /** Opens the transport and reports lifecycle/text events to [listener]. */
    fun open(endpoint: URI, listener: Listener)

    /** Sends a text protocol frame. */
    fun sendText(text: String)

    /** Closes the transport. */
    fun close(code: Int = NORMAL_CLOSURE, reason: String? = null)

    /** Transport event callbacks. */
    interface Listener {
        fun onOpen()
        fun onText(text: String)
        fun onClosed(code: Int, reason: String)
        fun onFailure(error: Throwable)
    }

    companion object {
        const val NORMAL_CLOSURE = 1000
    }
}

/** Factory for real or fake Sendspin transports. */
fun interface SendspinTransportFactory {
    fun create(): SendspinTransport
}

/** WebSocket transport implementation backed by OkHttp. */
class OkHttpWebSocketSendspinTransport(
    private val client: OkHttpClient,
) : SendspinTransport {
    private var webSocket: WebSocket? = null

    override fun open(endpoint: URI, listener: SendspinTransport.Listener) {
        val request = Request.Builder().url(endpoint.toString()).build()
        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    listener.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    listener.onText(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosed(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    listener.onFailure(t)
                }
            },
        )
    }

    override fun sendText(text: String) {
        val socket = webSocket ?: throw SendspinConnectionException(
            LocalPlayerEnvelope.LOCAL_PLAYER_TRANSPORT_ERROR,
            "Sendspin WebSocket is not open.",
        )
        if (!socket.send(text)) {
            throw SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_TRANSPORT_ERROR,
                "Sendspin WebSocket refused a text frame.",
            )
        }
    }

    override fun close(code: Int, reason: String?) {
        webSocket?.close(code, reason)
        webSocket = null
    }
}

/** Creates the initial real Sendspin WebSocket transport. */
class OkHttpWebSocketSendspinTransportFactory : SendspinTransportFactory {
    private val client = OkHttpClient()

    override fun create(): SendspinTransport = OkHttpWebSocketSendspinTransport(client)
}
