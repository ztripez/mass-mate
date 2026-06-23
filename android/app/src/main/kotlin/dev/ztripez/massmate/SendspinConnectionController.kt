package dev.ztripez.massmate

import java.net.URI

/**
 * Deterministic Sendspin connection owner for transport open, hello, goodbye, and reconnect.
 *
 * All public methods and transport callbacks are serialized through [queue]. Production code passes
 * a dedicated HandlerThread-backed queue so MethodChannel calls never block the Android main thread.
 * Tests may use the default immediate queue for deterministic fake-transport assertions. A new
 * [connect] replaces any active session: the previous session sends `client/goodbye` only after a
 * client hello was sent, closes with [SendspinTransport.NORMAL_CLOSURE], and any later callbacks
 * from that old session are ignored by session id. If replacement goodbye fails, the reconnect is
 * not opened and a failed snapshot/result are emitted. Post-handshake text is parsed by the native
 * protocol dispatcher so unknown message types and invalid known messages fail visibly through
 * snapshots. [sendRawClientCommand] is native protocol serialization only; Flutter
 * intent-to-command mapping remains unimplemented in this controller.
 *
 * @param transportFactory Creates a fresh transport for each new Sendspin connection attempt.
 * @param onSnapshot Receives every lifecycle snapshot emitted by the controller.
 * @param queue Serializes lifecycle calls and transport callbacks; defaults to immediate execution
 * for deterministic unit tests.
 * @param protocolEvents Optional native owner for parsed post-handshake protocol events. `null`
 * selects [FailHardSendspinProtocolEvents], which fails visibly for families without owners.
 * @param protocolLogger Logger for protocol diagnostics before visible failures.
 * @param timingController Native owner for client time requests and server-time synchronization.
 * @param streamBuffer Native owner for stream lifecycle and timestamp-ordered audio frame buffering.
 * @param audioPipeline Native owner for PCM scheduling and Android audio sink writes.
 * @param streamSnapshotFrameInterval Positive accepted/dropped binary-frame interval for coalesced
 * stream diagnostic snapshots.
 */
class SendspinConnectionController(
    private val transportFactory: SendspinTransportFactory,
    private val onSnapshot: (SendspinConnectionSnapshot) -> Unit,
    private val queue: SendspinConnectionQueue = SendspinConnectionQueue { task -> task() },
    protocolEvents: SendspinProtocolEvents? = null,
    protocolLogger: SendspinProtocolLogger = JavaUtilSendspinProtocolLogger,
    private val timingController: SendspinTimingController = SendspinTimingController(),
    private val streamBuffer: SendspinStreamBuffer = SendspinStreamBuffer(),
    private val audioPipeline: SendspinAudioPipeline = SendspinAudioPipeline(),
    private val streamSnapshotFrameInterval: Int = 64,
) {
    init {
        require(streamSnapshotFrameInterval > 0) { "streamSnapshotFrameInterval must be positive." }
    }

    private var sessionId = 0L
    private var snapshotGeneration = 0L
    private var activeTransport: SendspinTransport? = null
    private var activeSession: ActiveSession? = null
    private var binaryFramesSinceStreamSnapshot = 0
    private val failHardProtocolEvents = FailHardSendspinProtocolEvents(protocolLogger)
    private val dispatcher = SendspinProtocolDispatcher(
        logger = protocolLogger,
        events = SendspinControllerProtocolEvents(
            delegate = protocolEvents,
            failHardEvents = failHardProtocolEvents,
            handleServerTime = ::handleServerTime,
            handleStreamStart = ::handleStreamStart,
            handleStreamClear = ::handleStreamClear,
            handleStreamEnd = ::handleStreamEnd,
        ),
    )

    /** Enqueues opening a fresh Sendspin session to [endpoint]. */
    fun connect(endpoint: URI, onResult: SendspinLifecycleResult = {}) {
        queue.execute { connectOnQueue(endpoint, onResult) }
    }

    /** Enqueues deliberate disconnect, goodbye, transport close, and audio release. */
    fun disconnect(onResult: SendspinLifecycleResult = {}) {
        queue.execute { disconnectOnQueue(onResult) }
    }

    /**
     * Enqueues a raw native `client/command` message for an already-ready Sendspin session.
     *
     * This method is not a Flutter bridge API and does not map `PlaybackIntent` values. Later command
     * mapping owns deciding when to create [command]. Not-ready sessions and send failures are emitted
     * as visible native snapshots instead of pretending success.
     */
    fun sendRawClientCommand(
        command: SendspinClientCommand,
        onResult: SendspinProtocolSendResult = {},
    ) {
        queue.execute { sendRawClientCommandOnQueue(command, onResult) }
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
        timingController.reset()
        streamBuffer.reset()
        audioPipeline.reset()
        binaryFramesSinceStreamSnapshot = 0
        activeTransport = transport
        activeSession = ActiveSession(nextSessionId)
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
            override fun onBinary(bytes: ByteArray) = queue.execute { handleBinary(listenerSessionId, bytes) }
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
            dispatchReadyText(listenerSessionId, text)
            return
        }

        try {
            val serverHello = SendspinProtocolHandshake.parseServerHello(text)
            SendspinProtocolHandshake.validateServerHello(serverHello)
        } catch (error: SendspinConnectionException) {
            failCurrentSession(listenerSessionId, error)
            return
        } catch (error: RuntimeException) {
            failCurrentSession(
                listenerSessionId,
                SendspinConnectionException(
                    LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR,
                    "Sendspin server hello could not be parsed.",
                    mapOf("message" to error.message),
                ),
            )
            return
        }

        try {
            sendInitialClientProtocolStatus(listenerSessionId)
            session.ready = true
            emitReadySnapshot()
        } catch (error: SendspinConnectionException) {
            failCurrentSession(listenerSessionId, error)
        } catch (error: RuntimeException) {
            failCurrentSession(
                listenerSessionId,
                SendspinConnectionException(
                    LocalPlayerEnvelope.LOCAL_PLAYER_TRANSPORT_ERROR,
                    "Failed to send initial Sendspin client protocol status.",
                    mapOf("message" to error.message),
                ),
            )
        }
    }

    private fun sendRawClientCommandOnQueue(
        command: SendspinClientCommand,
        onResult: SendspinProtocolSendResult,
    ) {
        val session = activeSession
        val transport = activeTransport
        if (session?.ready != true || transport == null) {
            val error = SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_NOT_CONNECTED,
                "Cannot send Sendspin client command before handshake readiness.",
            )
            if (session != null) {
                failCurrentSession(session.id, error)
            } else {
                emitFailed(error)
            }
            onResult(error)
            return
        }

        val sendError = try {
            transport.sendText(command.toText())
            null
        } catch (error: SendspinConnectionException) {
            error
        } catch (error: RuntimeException) {
            SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_TRANSPORT_ERROR,
                "Failed to send Sendspin client command.",
                mapOf("message" to error.message),
            )
        }
        if (sendError == null) {
            onResult(null)
            return
        }

        failCurrentSession(session.id, sendError)
        onResult(sendError)
    }

    private fun dispatchReadyText(listenerSessionId: Long, text: String) {
        try {
            dispatcher.dispatch(text)
        } catch (error: SendspinConnectionException) {
            failCurrentSession(listenerSessionId, error)
        } catch (error: RuntimeException) {
            failCurrentSession(
                listenerSessionId,
                SendspinConnectionException(
                    LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR,
                    "Sendspin protocol message could not be dispatched.",
                    mapOf("message" to error.message),
                ),
            )
        }
    }

    private fun handleBinary(listenerSessionId: Long, bytes: ByteArray) {
        val session = activeSessionIfCurrent(listenerSessionId) ?: return
        if (!session.ready) return
        try {
            val result = streamBuffer.receiveBinaryResult(bytes)
            result.acceptedFrame?.let { frame ->
                audioPipeline.receiveFrame(frame, timingController::stableServerTimeToLocalTimeMs)
                streamBuffer.consume(frame)
            }
            binaryFramesSinceStreamSnapshot += 1
            if (binaryFramesSinceStreamSnapshot >= streamSnapshotFrameInterval) {
                binaryFramesSinceStreamSnapshot = 0
                emitReadySnapshot()
            }
        } catch (error: SendspinConnectionException) {
            failCurrentSession(listenerSessionId, error)
        } catch (error: RuntimeException) {
            failCurrentSession(
                listenerSessionId,
                SendspinConnectionException(
                    LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR,
                    "Sendspin binary frame could not be buffered.",
                    mapOf("message" to error.message),
                ),
            )
        }
    }

    private fun sendInitialClientProtocolStatus(listenerSessionId: Long) {
        val transport = activeTransport ?: throw SendspinConnectionException(
            LocalPlayerEnvelope.LOCAL_PLAYER_TRANSPORT_ERROR,
            "Cannot send initial Sendspin client protocol status without an active transport.",
        )
        transport.sendText(SendspinClientState.initialReady().toText())
        sendClientTimeRequest(transport, listenerSessionId)
    }

    private fun sendClientTimeRequest(transport: SendspinTransport, listenerSessionId: Long) {
        transport.sendText(timingController.createRequest(listenerSessionId).toText())
    }

    private fun handleServerTime(time: SendspinServerTime) {
        timingController.recordResponse(time)
        audioPipeline.drain(timingController::stableServerTimeToLocalTimeMs)
        val transport = activeTransport
        val session = activeSession
        if (transport != null && session?.ready == true) {
            timingController.createFollowUpRequest(session.id)?.let { request ->
                transport.sendText(request.toText())
            }
        }
        emitReadySnapshot()
    }

    private fun handleStreamStart(stream: SendspinStreamStart) {
        streamBuffer.start(stream)
        audioPipeline.start(stream)
        binaryFramesSinceStreamSnapshot = 0
        emitReadySnapshot()
    }

    private fun handleStreamClear(stream: SendspinStreamClear) {
        streamBuffer.clear(stream)
        audioPipeline.clear(stream)
        binaryFramesSinceStreamSnapshot = 0
        emitReadySnapshot()
    }

    private fun handleStreamEnd(stream: SendspinStreamEnd) {
        streamBuffer.end(stream)
        audioPipeline.end(stream)
        binaryFramesSinceStreamSnapshot = 0
        emitReadySnapshot()
    }

    private fun emitReadySnapshot() {
        emit(SendspinConnectionSnapshot.ready(nextGeneration(), timingController.snapshot(), streamBuffer.snapshot(), audioPipeline.snapshot()))
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
        val audioReleaseError = try {
            audioPipeline.release()
            null
        } catch (error: SendspinConnectionException) {
            error
        } catch (error: RuntimeException) {
            SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_AUDIO_ERROR,
                "Failed to release Sendspin audio pipeline.",
                mapOf("message" to error.message),
            )
        }

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

        return aggregateCloseErrors(closeError, transportCloseError, audioReleaseError)
    }

    private fun aggregateCloseErrors(
        goodbyeError: SendspinConnectionException?,
        transportCloseError: SendspinConnectionException?,
        audioReleaseError: SendspinConnectionException?,
    ): SendspinConnectionException? {
        val failures = listOfNotNull(
            goodbyeError?.toCloseFailure("goodbye"),
            transportCloseError?.toCloseFailure("transportClose"),
            audioReleaseError?.toCloseFailure("audioRelease"),
        )
        if (failures.isEmpty()) return null
        if (failures.size == 1) return goodbyeError ?: transportCloseError ?: audioReleaseError
        return SendspinConnectionException(
            LocalPlayerEnvelope.LOCAL_PLAYER_TRANSPORT_ERROR,
            "Multiple Sendspin disconnect failures occurred.",
            mapOf("closeFailures" to failures),
        )
    }

    private fun SendspinConnectionException.toCloseFailure(stage: String): Map<String, Any?> = mapOf(
        "stage" to stage,
        "code" to code,
        "message" to message,
        "details" to details,
    )

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
        emit(SendspinConnectionSnapshot.failed(nextGeneration(), error, audio = audioPipeline.snapshot()))
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
        var clientHelloSent: Boolean = false,
        var ready: Boolean = false,
    )
}
