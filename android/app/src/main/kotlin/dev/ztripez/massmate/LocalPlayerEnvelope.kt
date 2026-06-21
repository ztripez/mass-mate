package dev.ztripez.massmate

/**
 * Canonical envelope builder and error-code owner for the native local-player bridge.
 *
 * Method results use `{ accepted: Boolean, error?: { code, message, details? } }` and snapshots
 * use scalar player-state fields plus an optional error envelope. Centralizing those maps keeps
 * the Android service and channel aligned with the Dart bridge parser.
 */
object LocalPlayerEnvelope {
    const val LOCAL_PLAYER_UNAVAILABLE = "LOCAL_PLAYER_UNAVAILABLE"
    const val LOCAL_PLAYER_NOT_CONNECTED = "LOCAL_PLAYER_NOT_CONNECTED"
    const val LOCAL_PLAYER_INVALID_ENVELOPE = "LOCAL_PLAYER_INVALID_ENVELOPE"

    const val TRANSPORT_UNIMPLEMENTED_MESSAGE =
        "Native local player service is present, but transport is not implemented yet."
    const val NOT_CONNECTED_MESSAGE = "Native local player is not connected."
    const val INVALID_COMMAND_ENVELOPE_MESSAGE =
        "Local player command envelope is missing an intent-level command."
    const val BIND_FAILED_MESSAGE = "Native local player service could not be bound."

    /** Creates a successful method result envelope. */
    fun acceptedResult(): Map<String, Any?> = mapOf("accepted" to true)

    /** Creates a failed method result envelope with a typed error payload. */
    fun failedResult(
        code: String,
        message: String,
        details: Map<String, Any?>? = null,
    ): Map<String, Any?> =
        mapOf(
            "accepted" to false,
            "error" to errorEnvelope(code, message, details),
        )

    /** Creates a typed error payload for method results or snapshots. */
    fun errorEnvelope(
        code: String,
        message: String,
        details: Map<String, Any?>? = null,
    ): Map<String, Any?> =
        mapOf(
            "code" to code,
            "message" to message,
            "details" to details,
        )

    /** Creates the current local-player snapshot envelope consumed by Dart. */
    fun snapshot(
        connectionStatus: String,
        connectionLabel: String,
        mediaTitle: String,
        mediaSubtitle: String,
        error: Map<String, Any?>?,
    ): Map<String, Any?> {
        return mutableMapOf<String, Any?>(
            "connectionStatus" to connectionStatus,
            "playerName" to "Mass Mate",
            "connectionLabel" to connectionLabel,
            "mediaTitle" to mediaTitle,
            "mediaSubtitle" to mediaSubtitle,
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
}
