package dev.ztripez.massmate

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Route-independent Android owner for the Mass Mate native local-player backend.
 *
 * The service is bound by [LocalPlayerChannel] and outlives Flutter route rebuilds. It owns the
 * Sendspin connection lifecycle boundary, while Flutter widgets continue to send only intent-level
 * playback operations through the Dart adapter seam. The service owns transport open, handshake,
 * deterministic reconnect, typed connection snapshots, and deliberate goodbye on disconnect. It
 * intentionally does not own audio output, stream lifecycle, clock sync, browse, or command
 * dispatch beyond rejecting commands until later Sendspin slices implement that mapping.
 *
 * Public methods return platform-channel result envelopes shaped as
 * `{ accepted: Boolean, error?: { code: String, message: String, details?: Any } }`. Snapshot
 * listeners receive maps shaped as `{ connectionStatus, playerName, connectionLabel, mediaTitle,
 * mediaSubtitle, positionMs, trackLengthMs, volume, queueIndex, queueMinIndex, queueMaxIndex,
 * isPlaying, error? }`. Failed or unavailable snapshots include an error payload so Dart can fail
 * visibly and never fall back to the demo backend silently.
 */
class LocalPlayerService : Service() {
    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val controller = SendspinConnectionController(
        transportFactory = OkHttpWebSocketSendspinTransportFactory(),
        onSnapshot = ::handleControllerSnapshot,
    )
    private var snapshotListener: ((Map<String, Any?>) -> Unit)? = null
    private var state = LocalPlayerServiceState.disconnected()

    /** Binder exposing the service instance to the platform-channel registrar. */
    inner class LocalBinder : Binder() {
        /** Bound [LocalPlayerService] instance used by [LocalPlayerChannel] to call service APIs. */
        val service: LocalPlayerService
            get() = this@LocalPlayerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * Registers a listener for typed local-player snapshot envelopes.
     *
     * The listener immediately receives the current snapshot map and then receives subsequent
     * snapshots caused by [connect], [disconnect], or [sendCommand]. Passing `null` clears the
     * listener. Failed and unavailable snapshots include an `error` payload using
     * [LocalPlayerEnvelope.LOCAL_PLAYER_UNAVAILABLE] or
     * [LocalPlayerEnvelope.LOCAL_PLAYER_NOT_CONNECTED].
     */
    fun setSnapshotListener(listener: ((Map<String, Any?>) -> Unit)?) {
        snapshotListener = listener
        listener?.invoke(currentSnapshot())
    }

    /**
     * Requests a local-player connection to configured Sendspin server settings.
     *
     * [arguments] must include `serverUrl` and may include `sendspinPath`. The service converts
     * those settings into the WebSocket endpoint, opens the transport, sends `client/hello`, waits
     * for a valid `server/hello`, and reports `ready` only after protocol version and role gates
     * pass. Missing or invalid settings fail visibly without selecting the demo backend.
     */
    fun connect(arguments: Map<*, *>?): Map<String, Any?> {
        val endpoint = try {
            SendspinEndpointBuilder.fromBridgeArguments(arguments)
        } catch (error: SendspinConnectionException) {
            state = LocalPlayerServiceState.failed(error)
            emitSnapshot()
            return LocalPlayerEnvelope.failedResult(error.code, error.message, error.details)
        }

        controller.connect(endpoint)
        return LocalPlayerEnvelope.acceptedResult()
    }

    /**
     * Requests an explicit local-player disconnect without destroying route-independent ownership.
     *
     * Returns `{ accepted: true }` and emits a disconnected snapshot. Route unmounts do not call
     * this method; only explicit adapter lifecycle requests should disconnect the local player.
     */
    fun disconnect(): Map<String, Any?> {
        val goodbyeError = controller.disconnect()
        return if (goodbyeError == null) {
            LocalPlayerEnvelope.acceptedResult()
        } else {
            LocalPlayerEnvelope.failedResult(goodbyeError.code, goodbyeError.message, goodbyeError.details)
        }
    }

    /**
     * Sends an intent-level Mass Mate playback command envelope to the native backend seam.
     *
     * [envelope] must contain a string `command` field naming a Mass Mate operation. Invalid
     * envelopes return `LOCAL_PLAYER_INVALID_ENVELOPE`. Valid commands require a ready handshake.
     * Issue #27 and later slices own command dispatch, so commands received while ready are rejected
     * explicitly instead of being projected as local success.
     */
    fun sendCommand(envelope: Map<*, *>?): Map<String, Any?> {
        if (envelope?.get("command") !is String) {
            return LocalPlayerEnvelope.failedResult(
                LocalPlayerEnvelope.LOCAL_PLAYER_INVALID_ENVELOPE,
                LocalPlayerEnvelope.INVALID_COMMAND_ENVELOPE_MESSAGE,
            )
        }

        if (state.connectionStatus != SendspinConnectionStatus.READY.bridgeValue) {
            val error = SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_NOT_CONNECTED,
                LocalPlayerEnvelope.NOT_CONNECTED_MESSAGE,
            )
            state = LocalPlayerServiceState.failed(error)
            emitSnapshot()
            return LocalPlayerEnvelope.failedResult(error.code, error.message, error.details)
        }

        return LocalPlayerEnvelope.failedResult(
            LocalPlayerEnvelope.LOCAL_PLAYER_REJECTED,
            LocalPlayerEnvelope.COMMAND_DISPATCH_DEFERRED_MESSAGE,
        )
    }

    override fun onDestroy() {
        controller.disconnect()
        super.onDestroy()
    }

    private fun handleControllerSnapshot(snapshot: SendspinConnectionSnapshot) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyControllerSnapshot(snapshot)
        } else {
            mainHandler.post { applyControllerSnapshot(snapshot) }
        }
    }

    private fun applyControllerSnapshot(snapshot: SendspinConnectionSnapshot) {
        state = LocalPlayerServiceState.fromSendspinSnapshot(snapshot)
        emitSnapshot()
    }

    private fun emitSnapshot() {
        snapshotListener?.invoke(currentSnapshot())
    }

    private fun currentSnapshot(): Map<String, Any?> {
        return LocalPlayerEnvelope.snapshot(
            connectionStatus = state.connectionStatus,
            connectionLabel = state.connectionLabel,
            mediaTitle = state.mediaTitle,
            mediaSubtitle = state.mediaSubtitle,
            error = state.errorEnvelope(),
        )
    }
}

private data class LocalPlayerServiceState(
    val connectionStatus: String,
    val connectionLabel: String,
    val mediaTitle: String,
    val mediaSubtitle: String,
    val error: SendspinConnectionException?,
) {
    companion object {
        fun disconnected(): LocalPlayerServiceState = LocalPlayerServiceState(
            connectionStatus = SendspinConnectionStatus.DISCONNECTED.bridgeValue,
            connectionLabel = "Native local player disconnected",
            mediaTitle = "Local player ready",
            mediaSubtitle = "Not connected",
            error = null,
        )

        fun failed(error: SendspinConnectionException): LocalPlayerServiceState = LocalPlayerServiceState(
            connectionStatus = SendspinConnectionStatus.FAILED.bridgeValue,
            connectionLabel = "Sendspin local player failed",
            mediaTitle = "Local player failed",
            mediaSubtitle = error.message,
            error = error,
        )

        fun fromSendspinSnapshot(snapshot: SendspinConnectionSnapshot): LocalPlayerServiceState {
            return LocalPlayerServiceState(
                connectionStatus = snapshot.status.bridgeValue,
                connectionLabel = snapshot.connectionLabel,
                mediaTitle = snapshot.mediaTitle,
                mediaSubtitle = snapshot.mediaSubtitle,
                error = snapshot.error,
            )
        }
    }

    fun errorEnvelope(): Map<String, Any?>? {
        val currentError = error ?: return null
        return LocalPlayerEnvelope.errorEnvelope(
            currentError.code,
            currentError.message,
            currentError.details,
        )
    }
}
