package dev.ztripez.massmate

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/**
 * Route-independent Android service boundary for the native local-player backend.
 *
 * This issue only creates the lifecycle and bridge seam. Future Sendspin transport,
 * protocol, and audio owners attach behind this service instead of living in Flutter
 * routes or MainActivity.
 */
class LocalPlayerService : Service() {
    private val binder = LocalBinder()
    private var snapshotListener: ((Map<String, Any?>) -> Unit)? = null
    private var connected = false

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

    /** Requests a local-player connection. */
    fun connect(): Map<String, Any?> {
        connected = false
        emitSnapshot()
        return failedResult(
            LOCAL_PLAYER_UNAVAILABLE,
            "Native local player service is present, but transport is not implemented yet.",
        )
    }

    /** Requests an explicit local-player disconnect. */
    fun disconnect(): Map<String, Any?> {
        connected = false
        emitSnapshot()
        return acceptedResult()
    }

    /** Sends an intent-level Mass Mate playback command envelope to the native backend. */
    fun sendCommand(envelope: Map<*, *>?): Map<String, Any?> {
        if (envelope?.get("command") !is String) {
            return failedResult(
                LOCAL_PLAYER_INVALID_ENVELOPE,
                "Local player command envelope is missing an intent-level command.",
            )
        }

        if (!connected) {
            emitSnapshot()
            return failedResult(
                LOCAL_PLAYER_NOT_CONNECTED,
                "Native local player is not connected.",
            )
        }

        return failedResult(
            LOCAL_PLAYER_UNAVAILABLE,
            "Native local player command transport is not implemented yet.",
        )
    }

    private fun emitSnapshot() {
        snapshotListener?.invoke(currentSnapshot())
    }

    private fun currentSnapshot(): Map<String, Any?> {
        val connectionStatus = if (connected) "connected" else "unavailable"
        val connectionLabel = if (connected) {
            "Native local player connected"
        } else {
            "Native local player unavailable"
        }

        return mapOf(
            "connectionStatus" to connectionStatus,
            "playerName" to "Mass Mate",
            "connectionLabel" to connectionLabel,
            "mediaTitle" to "Local player unavailable",
            "mediaSubtitle" to "Transport will be added by a later issue",
            "positionMs" to 0,
            "trackLengthMs" to 1,
            "volume" to 0.0,
            "queueIndex" to 1,
            "queueMinIndex" to 1,
            "queueMaxIndex" to 1,
            "isPlaying" to false,
            "error" to mapOf(
                "code" to LOCAL_PLAYER_UNAVAILABLE,
                "message" to
                    "Native local player service is present, but transport is not implemented yet.",
            ),
        )
    }

    private fun acceptedResult(): Map<String, Any?> = mapOf("accepted" to true)

    private fun failedResult(code: String, message: String): Map<String, Any?> =
        mapOf(
            "accepted" to false,
            "error" to mapOf(
                "code" to code,
                "message" to message,
            ),
        )

    private companion object {
        const val LOCAL_PLAYER_UNAVAILABLE = "LOCAL_PLAYER_UNAVAILABLE"
        const val LOCAL_PLAYER_NOT_CONNECTED = "LOCAL_PLAYER_NOT_CONNECTED"
        const val LOCAL_PLAYER_INVALID_ENVELOPE = "LOCAL_PLAYER_INVALID_ENVELOPE"
    }
}
