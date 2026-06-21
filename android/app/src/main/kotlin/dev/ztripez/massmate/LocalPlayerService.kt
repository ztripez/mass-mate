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
        val service: LocalPlayerService
            get() = this@LocalPlayerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Registers a listener for typed local-player snapshot envelopes. */
    fun setSnapshotListener(listener: ((Map<String, Any?>) -> Unit)?) {
        snapshotListener = listener
        listener?.invoke(currentSnapshot())
    }

    /** Requests a local-player connection and reports explicit unavailability for this skeleton. */
    fun connect(): Map<String, Any?> {
        state = LocalPlayerServiceState.UNAVAILABLE
        emitSnapshot()
        return failedResult(
            LOCAL_PLAYER_UNAVAILABLE,
            "Native local player service is present, but transport is not implemented yet.",
        )
    }

    /** Requests an explicit local-player disconnect without destroying route-independent ownership. */
    fun disconnect(): Map<String, Any?> {
        state = LocalPlayerServiceState.DISCONNECTED
        emitSnapshot()
        return acceptedResult()
    }

    /** Sends an intent-level Mass Mate playback command envelope to the native backend seam. */
    fun sendCommand(envelope: Map<*, *>?): Map<String, Any?> {
        if (envelope?.get("command") !is String) {
            return failedResult(
                LOCAL_PLAYER_INVALID_ENVELOPE,
                "Local player command envelope is missing an intent-level command.",
            )
        }

        state = LocalPlayerServiceState.FAILED_NOT_CONNECTED
        emitSnapshot()
        return failedResult(
            LOCAL_PLAYER_NOT_CONNECTED,
            "Native local player is not connected.",
        )
    }

    private fun emitSnapshot() {
        snapshotListener?.invoke(currentSnapshot())
    }

    private fun currentSnapshot(): Map<String, Any?> {
        val error = state.errorEnvelope()
        return mutableMapOf<String, Any?>(
            "connectionStatus" to state.connectionStatus,
            "playerName" to "Mass Mate",
            "connectionLabel" to state.connectionLabel,
            "mediaTitle" to state.mediaTitle,
            "mediaSubtitle" to state.mediaSubtitle,
            "positionMs" to 0,
            "trackLengthMs" to 1,
            "volume" to 0.0,
            "queueIndex" to 1,
            "queueMinIndex" to 1,
            "queueMaxIndex" to 1,
            "isPlaying" to false,
        ).apply {
            if (error != null) this["error"] = error
        }
    }

    private fun acceptedResult(): Map<String, Any?> = mapOf("accepted" to true)

    private fun failedResult(
        code: String,
        message: String,
        details: Map<String, Any?>? = null,
    ): Map<String, Any?> =
        mapOf(
            "accepted" to false,
            "error" to mapOf(
                "code" to code,
                "message" to message,
                "details" to details,
            ),
        )

    private companion object {
        const val LOCAL_PLAYER_UNAVAILABLE = "LOCAL_PLAYER_UNAVAILABLE"
        const val LOCAL_PLAYER_NOT_CONNECTED = "LOCAL_PLAYER_NOT_CONNECTED"
        const val LOCAL_PLAYER_INVALID_ENVELOPE = "LOCAL_PLAYER_INVALID_ENVELOPE"
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
            UNAVAILABLE -> mapOf(
                "code" to "LOCAL_PLAYER_UNAVAILABLE",
                "message" to
                    "Native local player service is present, but transport is not implemented yet.",
            )
            FAILED_NOT_CONNECTED -> mapOf(
                "code" to "LOCAL_PLAYER_NOT_CONNECTED",
                "message" to "Native local player is not connected.",
            )
        }
    }
}
