package dev.ztripez.massmate

import java.net.URI
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Text-only transport seam for the Sendspin hello/goodbye connection path.
 *
 * [open] starts an asynchronous connection to a validated endpoint and reports exactly one listener
 * stream for that transport instance. [sendText] sends a UTF-8 text protocol frame or throws
 * [SendspinConnectionException] / [RuntimeException] when the frame cannot be accepted by the
 * transport. [close] asks the transport to close; callers still surface close/send failures instead
 * of falling back to a demo backend. Binary audio frames and stream lifecycle are intentionally out
 * of scope for this interface.
 */
interface SendspinTransport {
    /** Opens the transport to [endpoint] and reports lifecycle, text, close, and failure callbacks. */
    fun open(endpoint: URI, listener: Listener)

    /** Sends a text protocol frame; failure to enqueue the frame must throw visibly. */
    fun sendText(text: String)

    /** Closes the transport with a WebSocket close [code] and optional [reason]. */
    fun close(code: Int = NORMAL_CLOSURE, reason: String? = null)

    /** Callback stream emitted by one transport instance and serialized by the controller owner. */
    interface Listener {
        /** The underlying WebSocket opened and can accept text frames. */
        fun onOpen()

        /** A text frame arrived; unsupported post-handshake text fails until dispatch exists. */
        fun onText(text: String)

        /** The WebSocket closed without an explicit service disconnect. */
        fun onClosed(code: Int, reason: String)

        /** The WebSocket transport failed before an orderly close. */
        fun onFailure(error: Throwable)
    }

    companion object {
        /** Normal WebSocket close code used for deliberate Mass Mate disconnect/reconnect. */
        const val NORMAL_CLOSURE = 1000

        /** Stable close reason used by tests and visible transport diagnostics. */
        const val NORMAL_CLOSURE_REASON = "Mass Mate disconnect"
    }
}

/** Factory for creating one real or fake [SendspinTransport] per connection attempt. */
fun interface SendspinTransportFactory {
    /** Creates a fresh unopened transport instance. */
    fun create(): SendspinTransport
}

/** OkHttp-backed WebSocket implementation of [SendspinTransport]. */
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
        val socket = webSocket ?: return
        if (!socket.close(code, reason)) {
            throw SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_TRANSPORT_ERROR,
                "Sendspin WebSocket refused to enqueue close.",
                mapOf("code" to code, "reason" to reason),
            )
        }
        webSocket = null
    }
}

/** Creates OkHttp WebSocket transports for configured Sendspin endpoints. */
class OkHttpWebSocketSendspinTransportFactory : SendspinTransportFactory {
    private val client = OkHttpClient()

    override fun create(): SendspinTransport = OkHttpWebSocketSendspinTransport(client)
}
