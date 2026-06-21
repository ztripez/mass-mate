package dev.ztripez.massmate

import java.net.URI

/** Native Sendspin connection status reported through local-player snapshots. */
enum class SendspinConnectionStatus(val bridgeValue: String) {
    /** No active Sendspin transport session is owned by the native local-player service. */
    DISCONNECTED("disconnected"),

    /** A WebSocket transport is opening or waiting for a valid server hello. */
    CONNECTING("connecting"),

    /** The client hello and server hello exchange passed version and required-role gates. */
    READY("ready"),

    /** The transport, endpoint, protocol, or role gate failed visibly. */
    FAILED("failed"),
}

/**
 * Single native representation of Sendspin connection state emitted to Flutter.
 *
 * [generation] increases for every controller-owned snapshot so the service can ignore stale
 * main-thread posts after disconnect or reconnect. [status] is the typed lifecycle state.
 * [connectionLabel], [mediaTitle], and [mediaSubtitle] are user-visible bridge fields. [error]
 * is present only for [SendspinConnectionStatus.FAILED] snapshots and carries the failure code,
 * message, and detail payload returned through the platform bridge.
 */
data class SendspinConnectionSnapshot(
    val generation: Long,
    val status: SendspinConnectionStatus,
    val connectionLabel: String,
    val mediaTitle: String,
    val mediaSubtitle: String,
    val error: SendspinConnectionException? = null,
) {
    companion object {
        /** Creates the initial disconnected native connection state. */
        fun disconnected(generation: Long = 0L): SendspinConnectionSnapshot = SendspinConnectionSnapshot(
            generation = generation,
            status = SendspinConnectionStatus.DISCONNECTED,
            connectionLabel = "Native local player disconnected",
            mediaTitle = "Local player ready",
            mediaSubtitle = "Not connected",
        )

        /** Creates a connecting snapshot for [endpoint]. */
        fun connecting(generation: Long, endpoint: URI): SendspinConnectionSnapshot =
            SendspinConnectionSnapshot(
                generation = generation,
                status = SendspinConnectionStatus.CONNECTING,
                connectionLabel = "Connecting to Sendspin",
                mediaTitle = "Connecting local player",
                mediaSubtitle = endpoint.host,
            )

        /** Creates a ready snapshot after a validated handshake. */
        fun ready(generation: Long): SendspinConnectionSnapshot = SendspinConnectionSnapshot(
            generation = generation,
            status = SendspinConnectionStatus.READY,
            connectionLabel = "Sendspin local player ready",
            mediaTitle = "Local player ready",
            mediaSubtitle = "Handshake complete",
        )

        /** Creates a failed snapshot carrying [error] details. */
        fun failed(
            generation: Long,
            error: SendspinConnectionException,
        ): SendspinConnectionSnapshot = SendspinConnectionSnapshot(
            generation = generation,
            status = SendspinConnectionStatus.FAILED,
            connectionLabel = "Sendspin local player failed",
            mediaTitle = "Local player failed",
            mediaSubtitle = error.message,
            error = error,
        )
    }
}

/** Serial executor used to keep transport callbacks and lifecycle requests ordered. */
fun interface SendspinConnectionQueue {
    /** Enqueues [task] on the controller-owned serial thread. */
    fun execute(task: () -> Unit)
}

/** Callback receiving the platform-channel result for an enqueued lifecycle request. */
typealias SendspinLifecycleResult = (SendspinConnectionException?) -> Unit

/**
 * Deterministic Sendspin connection owner for transport open, hello, goodbye, and reconnect.
 *
 * All public methods and transport callbacks are serialized through [queue]. Production code passes
 * a dedicated HandlerThread-backed queue so MethodChannel calls never block the Android main thread.
 * Tests may use the default immediate queue for deterministic fake-transport assertions. A new
 * [connect] replaces any active session: the previous session sends `client/goodbye` only after a
 * client hello was sent, closes with [SendspinTransport.NORMAL_CLOSURE], and any later callbacks
 * from that old session are ignored by session id. If replacement goodbye fails, the reconnect is
 * not opened and a failed snapshot/result are emitted. Unsupported text after READY fails loudly
 * until a future dispatcher owns non-handshake messages.
 */
class SendspinConnectionController(
    private val transportFactory: SendspinTransportFactory,
    private val onSnapshot: (SendspinConnectionSnapshot) -> Unit,
    private val queue: SendspinConnectionQueue = SendspinConnectionQueue { task -> task() },
) {
    private var sessionId = 0L
    private var snapshotGeneration = 0L
    private var activeTransport: SendspinTransport? = null
    private var activeSession: ActiveSession? = null

    /** Enqueues opening a fresh Sendspin session to [endpoint]. */
    fun connect(endpoint: URI, onResult: SendspinLifecycleResult = {}) {
        queue.execute { connectOnQueue(endpoint, onResult) }
    }

    /** Enqueues deliberate disconnect and goodbye when the current protocol state allows it. */
    fun disconnect(onResult: SendspinLifecycleResult = {}) {
        queue.execute { disconnectOnQueue(onResult) }
    }

    private fun connectOnQueue(endpoint: URI, onResult: SendspinLifecycleResult) {
        val replacementError = closeActiveSession(deliberate = true)
        if (replacementError != null) {
            emitFailed(replacementError)
            onResult(replacementError)
            return
        }

        val transport = try {
            transportFactory.create()
        } catch (error: RuntimeException) {
            val connectionError = SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_TRANSPORT_ERROR,
                "Sendspin transport could not be created.",
                mapOf("exception" to error.javaClass.name, "message" to error.message),
            )
            emitFailed(connectionError)
            onResult(connectionError)
            return
        }
        val nextSessionId = ++sessionId
        activeTransport = transport
        activeSession = ActiveSession(nextSessionId, endpoint)
        emit(SendspinConnectionSnapshot.connecting(nextGeneration(), endpoint))

        try {
            transport.open(endpoint, listenerFor(nextSessionId, transport))
            onResult(null)
        } catch (error: RuntimeException) {
            val connectionError = SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_TRANSPORT_ERROR,
                "Sendspin transport could not be opened.",
                mapOf("exception" to error.javaClass.name, "message" to error.message),
            )
            failCurrentSession(nextSessionId, connectionError)
            onResult(connectionError)
        }
    }

    private fun disconnectOnQueue(onResult: SendspinLifecycleResult) {
        val goodbyeError = closeActiveSession(deliberate = true)
        if (goodbyeError == null) {
            emit(SendspinConnectionSnapshot.disconnected(nextGeneration()))
            onResult(null)
            return
        }

        emitFailed(goodbyeError)
        onResult(goodbyeError)
    }

    private fun listenerFor(
        listenerSessionId: Long,
        transport: SendspinTransport,
    ): SendspinTransport.Listener {
        return object : SendspinTransport.Listener {
            override fun onOpen() = queue.execute { handleOpen(listenerSessionId, transport) }
            override fun onText(text: String) = queue.execute { handleText(listenerSessionId, text) }
            override fun onClosed(code: Int, reason: String) =
                queue.execute { handleClosed(listenerSessionId, code, reason) }

            override fun onFailure(error: Throwable) = queue.execute { handleFailure(listenerSessionId, error) }
        }
    }

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

    private fun handleText(listenerSessionId: Long, text: String) {
        val session = activeSessionIfCurrent(listenerSessionId) ?: return
        if (session.ready) {
            failCurrentSession(
                listenerSessionId,
                SendspinConnectionException(
                    LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR,
                    "Received unsupported Sendspin text frame after handshake readiness.",
                    mapOf("sessionId" to listenerSessionId),
                ),
            )
            return
        }

        try {
            val serverHello = SendspinProtocolHandshake.parseServerHello(text)
            SendspinProtocolHandshake.validateServerHello(serverHello)
            session.ready = true
            emit(SendspinConnectionSnapshot.ready(nextGeneration()))
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

        var closeError: SendspinConnectionException? = null
        if (deliberate && transport != null && session?.clientHelloSent == true) {
            closeError = try {
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

        val transportCloseError = try {
            transport?.close(SendspinTransport.NORMAL_CLOSURE, SendspinTransport.NORMAL_CLOSURE_REASON)
            null
        } catch (error: RuntimeException) {
            SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_TRANSPORT_ERROR,
                "Failed to close Sendspin transport.",
                mapOf("message" to error.message),
            )
        }

        return closeError ?: transportCloseError
    }

    private fun failCurrentSession(listenerSessionId: Long, error: SendspinConnectionException) {
        activeSessionIfCurrent(listenerSessionId) ?: return
        val closeError = closeActiveSession(deliberate = false)
        emitFailed(error.withCloseError(closeError))
    }

    private fun SendspinConnectionException.withCloseError(
        closeError: SendspinConnectionException?,
    ): SendspinConnectionException {
        if (closeError == null) return this
        val combinedDetails = (details.orEmpty() + mapOf(
            "closeErrorCode" to closeError.code,
            "closeErrorMessage" to closeError.message,
            "closeErrorDetails" to closeError.details,
        ))
        return SendspinConnectionException(code, message, combinedDetails)
    }

    private fun emitFailed(error: SendspinConnectionException) {
        emit(SendspinConnectionSnapshot.failed(nextGeneration(), error))
    }

    private fun emit(snapshot: SendspinConnectionSnapshot) {
        onSnapshot(snapshot)
    }

    private fun nextGeneration(): Long {
        snapshotGeneration += 1
        return snapshotGeneration
    }

    private data class ActiveSession(
        val id: Long,
        val endpoint: URI,
        var clientHelloSent: Boolean = false,
        var ready: Boolean = false,
    )
}
