package dev.ztripez.massmate

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/**
 * Route-independent Android owner for the Mass Mate native local-player backend.
 *
 * The service is bound by [LocalPlayerChannel] and outlives Flutter route rebuilds. It owns the
 * future native player lifecycle boundary, while Flutter widgets continue to send only intent-level
 * playback operations through the Dart adapter seam. This skeleton intentionally does not open any
 * network transport, parse any player protocol, or start any audio output.
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
    private var snapshotListener: ((Map<String, Any?>) -> Unit)? = null
    private var state = LocalPlayerServiceState.DISCONNECTED

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
     * Requests a local-player connection and reports explicit unavailability for this skeleton.
     *
     * Returns `{ accepted: false, error: { code: LOCAL_PLAYER_UNAVAILABLE, message } }` and emits
     * an unavailable snapshot. No transport, network, protocol, or audio work starts in this issue.
     */
    fun connect(): Map<String, Any?> {
        state = LocalPlayerServiceState.UNAVAILABLE
        emitSnapshot()
        return LocalPlayerEnvelope.failedResult(
            LocalPlayerEnvelope.LOCAL_PLAYER_UNAVAILABLE,
            LocalPlayerEnvelope.TRANSPORT_UNIMPLEMENTED_MESSAGE,
        )
    }

    /**
     * Requests an explicit local-player disconnect without destroying route-independent ownership.
     *
     * Returns `{ accepted: true }` and emits a disconnected snapshot. Route unmounts do not call
     * this method; only explicit adapter lifecycle requests should disconnect the local player.
     */
    fun disconnect(): Map<String, Any?> {
        state = LocalPlayerServiceState.DISCONNECTED
        emitSnapshot()
        return LocalPlayerEnvelope.acceptedResult()
    }

    /**
     * Sends an intent-level Mass Mate playback command envelope to the native backend seam.
     *
     * [envelope] must contain a string `command` field naming a Mass Mate operation. Invalid
     * envelopes return `LOCAL_PLAYER_INVALID_ENVELOPE`. Valid commands return
     * `LOCAL_PLAYER_NOT_CONNECTED` in this skeleton and emit a failed not-connected snapshot.
     */
    fun sendCommand(envelope: Map<*, *>?): Map<String, Any?> {
        if (envelope?.get("command") !is String) {
            return LocalPlayerEnvelope.failedResult(
                LocalPlayerEnvelope.LOCAL_PLAYER_INVALID_ENVELOPE,
                LocalPlayerEnvelope.INVALID_COMMAND_ENVELOPE_MESSAGE,
            )
        }

        state = LocalPlayerServiceState.FAILED_NOT_CONNECTED
        emitSnapshot()
        return LocalPlayerEnvelope.failedResult(
            LocalPlayerEnvelope.LOCAL_PLAYER_NOT_CONNECTED,
            LocalPlayerEnvelope.NOT_CONNECTED_MESSAGE,
        )
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

private enum class LocalPlayerServiceState(
    val connectionStatus: String,
    val connectionLabel: String,
    val mediaTitle: String,
    val mediaSubtitle: String,
) {
    DISCONNECTED(
        "disconnected",
        "Native local player disconnected",
        "Local player ready",
        "Not connected",
    ),
    UNAVAILABLE(
        "unavailable",
        "Native local player unavailable",
        "Local player unavailable",
        "Transport will be added by a later issue",
    ),
    FAILED_NOT_CONNECTED(
        "failed",
        "Native local player not connected",
        "Local player not connected",
        "Connect before sending playback commands",
    );

    fun errorEnvelope(): Map<String, Any?>? {
        return when (this) {
            DISCONNECTED -> null
            UNAVAILABLE -> LocalPlayerEnvelope.errorEnvelope(
                LocalPlayerEnvelope.LOCAL_PLAYER_UNAVAILABLE,
                LocalPlayerEnvelope.TRANSPORT_UNIMPLEMENTED_MESSAGE,
            )
            FAILED_NOT_CONNECTED -> LocalPlayerEnvelope.errorEnvelope(
                LocalPlayerEnvelope.LOCAL_PLAYER_NOT_CONNECTED,
                LocalPlayerEnvelope.NOT_CONNECTED_MESSAGE,
            )
        }
    }
}
