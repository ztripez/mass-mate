package dev.ztripez.massmate

import java.net.URI

/** Typed native Sendspin connection status reported to Flutter snapshots. */
enum class SendspinConnectionStatus(val bridgeValue: String) {
    DISCONNECTED("disconnected"),
    CONNECTING("connecting"),
    READY("ready"),
    FAILED("failed"),
}

/** Snapshot of the native Sendspin connection state. */
data class SendspinConnectionSnapshot(
    val status: SendspinConnectionStatus,
    val connectionLabel: String,
    val mediaTitle: String,
    val mediaSubtitle: String,
    val error: SendspinConnectionException? = null,
)

/** Deterministic connection controller for Sendspin transport open, hello, goodbye, and reconnect. */
class SendspinConnectionController(
    private val transportFactory: SendspinTransportFactory,
    private val onSnapshot: (SendspinConnectionSnapshot) -> Unit,
) {
    private var sessionId = 0L
    private var activeTransport: SendspinTransport? = null
    private var activeSession: ActiveSession? = null

    /** Opens a fresh Sendspin session, replacing any existing session deterministically. */
    @Synchronized
    fun connect(endpoint: URI) {
        closeActiveSession(deliberate = true)
        val transport = transportFactory.create()
        val nextSessionId = ++sessionId
        activeTransport = transport
        activeSession = ActiveSession(nextSessionId)
        onSnapshot(connectingSnapshot(endpoint))
        transport.open(endpoint, listenerFor(nextSessionId, transport))
    }

    /** Deliberately disconnects the active session and sends goodbye when the handshake allows it. */
    @Synchronized
    fun disconnect(): SendspinConnectionException? {
        val goodbyeError = closeActiveSession(deliberate = true)
        onSnapshot(disconnectedSnapshot())
        return goodbyeError
    }

    private fun listenerFor(
        listenerSessionId: Long,
        transport: SendspinTransport,
    ): SendspinTransport.Listener {
        return object : SendspinTransport.Listener {
            override fun onOpen() = handleOpen(listenerSessionId, transport)
            override fun onText(text: String) = handleText(listenerSessionId, text)
            override fun onClosed(code: Int, reason: String) = handleClosed(listenerSessionId, code, reason)
            override fun onFailure(error: Throwable) = handleFailure(listenerSessionId, error)
        }
    }

    @Synchronized
    private fun handleOpen(listenerSessionId: Long, transport: SendspinTransport) {
        val session = activeSessionIfCurrent(listenerSessionId) ?: return
        try {
            transport.sendText(SendspinProtocolHandshake.clientHello())
            session.clientHelloSent = true
        } catch (error: SendspinConnectionException) {
            failCurrentSession(listenerSessionId, error)
        } catch (error: RuntimeException) {
            failCurrentSession(
                listenerSessionId,
                SendspinConnectionException(
                    LocalPlayerEnvelope.LOCAL_PLAYER_TRANSPORT_ERROR,
                    "Failed to send Sendspin client hello.",
                    mapOf("message" to error.message),
                ),
            )
        }
    }

    @Synchronized
    private fun handleText(listenerSessionId: Long, text: String) {
        val session = activeSessionIfCurrent(listenerSessionId) ?: return
        if (session.ready) return
        try {
            val serverHello = SendspinProtocolHandshake.parseServerHello(text)
            SendspinProtocolHandshake.validateServerHello(serverHello)
            session.ready = true
            onSnapshot(readySnapshot())
        } catch (error: SendspinConnectionException) {
            failCurrentSession(listenerSessionId, error)
        } catch (error: RuntimeException) {
            failCurrentSession(
                listenerSessionId,
                SendspinConnectionException(
                    LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR,
                    "Sendspin server hello could not be parsed.",
                    mapOf("message" to error.message),
                ),
            )
        }
    }

    @Synchronized
    private fun handleClosed(listenerSessionId: Long, code: Int, reason: String) {
        activeSessionIfCurrent(listenerSessionId) ?: return
        failCurrentSession(
            listenerSessionId,
            SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_TRANSPORT_ERROR,
                "Sendspin transport closed before an explicit disconnect.",
                mapOf("code" to code, "reason" to reason),
            ),
        )
    }

    @Synchronized
    private fun handleFailure(listenerSessionId: Long, error: Throwable) {
        activeSessionIfCurrent(listenerSessionId) ?: return
        failCurrentSession(
            listenerSessionId,
            SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_TRANSPORT_ERROR,
                "Sendspin transport failed.",
                mapOf("exception" to error.javaClass.name, "message" to error.message),
            ),
        )
    }

    private fun activeSessionIfCurrent(listenerSessionId: Long): ActiveSession? {
        val session = activeSession ?: return null
        if (session.id != listenerSessionId) return null
        return session
    }

    private fun closeActiveSession(deliberate: Boolean): SendspinConnectionException? {
        val transport = activeTransport
        val session = activeSession
        activeTransport = null
        activeSession = null
        sessionId += 1

        var goodbyeError: SendspinConnectionException? = null
        if (deliberate && transport != null && session?.clientHelloSent == true) {
            goodbyeError = try {
                transport.sendText(SendspinProtocolHandshake.clientGoodbye())
                null
            } catch (error: SendspinConnectionException) {
                error
            } catch (error: RuntimeException) {
                SendspinConnectionException(
                    LocalPlayerEnvelope.LOCAL_PLAYER_TRANSPORT_ERROR,
                    "Failed to send Sendspin client goodbye.",
                    mapOf("message" to error.message),
                )
            }
        }
        transport?.close(SendspinTransport.NORMAL_CLOSURE, "Mass Mate disconnect")
        return goodbyeError
    }

    private fun failCurrentSession(listenerSessionId: Long, error: SendspinConnectionException) {
        activeSessionIfCurrent(listenerSessionId) ?: return
        closeActiveSession(deliberate = false)
        onSnapshot(failedSnapshot(error))
    }

    private fun connectingSnapshot(endpoint: URI): SendspinConnectionSnapshot = SendspinConnectionSnapshot(
        status = SendspinConnectionStatus.CONNECTING,
        connectionLabel = "Connecting to Sendspin",
        mediaTitle = "Connecting local player",
        mediaSubtitle = endpoint.host,
    )

    private fun readySnapshot(): SendspinConnectionSnapshot = SendspinConnectionSnapshot(
        status = SendspinConnectionStatus.READY,
        connectionLabel = "Sendspin local player ready",
        mediaTitle = "Local player ready",
        mediaSubtitle = "Handshake complete",
    )

    private fun disconnectedSnapshot(): SendspinConnectionSnapshot = SendspinConnectionSnapshot(
        status = SendspinConnectionStatus.DISCONNECTED,
        connectionLabel = "Native local player disconnected",
        mediaTitle = "Local player ready",
        mediaSubtitle = "Not connected",
    )

    private fun failedSnapshot(error: SendspinConnectionException): SendspinConnectionSnapshot =
        SendspinConnectionSnapshot(
            status = SendspinConnectionStatus.FAILED,
            connectionLabel = "Sendspin local player failed",
            mediaTitle = "Local player failed",
            mediaSubtitle = error.message,
            error = error,
        )

    private data class ActiveSession(
        val id: Long,
        var clientHelloSent: Boolean = false,
        var ready: Boolean = false,
    )
}
