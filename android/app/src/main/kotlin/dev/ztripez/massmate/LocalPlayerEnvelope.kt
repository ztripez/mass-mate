package dev.ztripez.massmate

/**
 * Canonical envelope builder and error-code owner for the native local-player bridge.
 *
 * Method results use `{ accepted: Boolean, error?: { code, message, details? } }` and snapshots
 * use scalar player-state fields plus an optional error envelope. Centralizing those maps keeps
 * the Android service and channel aligned with the Dart bridge parser.
 */
object LocalPlayerEnvelope {
    /** Native service or platform channel is unavailable in this runtime. */
    const val LOCAL_PLAYER_UNAVAILABLE = "LOCAL_PLAYER_UNAVAILABLE"

    /** Request requires a ready native connection, but no ready session exists. */
    const val LOCAL_PLAYER_NOT_CONNECTED = "LOCAL_PLAYER_NOT_CONNECTED"

    /** Dart sent a malformed intent-level command envelope. */
    const val LOCAL_PLAYER_INVALID_ENVELOPE = "LOCAL_PLAYER_INVALID_ENVELOPE"

    /** Configured Sendspin endpoint settings are missing, malformed, or unsupported. */
    const val LOCAL_PLAYER_ENDPOINT_INVALID = "LOCAL_PLAYER_ENDPOINT_INVALID"

    /** WebSocket open, send, close, or callback failure. */
    const val LOCAL_PLAYER_TRANSPORT_ERROR = "LOCAL_PLAYER_TRANSPORT_ERROR"

    /** Minimal hello/goodbye protocol parsing or version validation failure. */
    const val LOCAL_PLAYER_PROTOCOL_ERROR = "LOCAL_PLAYER_PROTOCOL_ERROR"

    /** Server-activated roles do not match advertised and required client roles. */
    const val LOCAL_PLAYER_ROLE_MISMATCH = "LOCAL_PLAYER_ROLE_MISMATCH"

    /** Native backend rejected a valid request without treating it as a transport crash. */
    const val LOCAL_PLAYER_REJECTED = "LOCAL_PLAYER_REJECTED"

    /** User-visible message for intent requests before handshake readiness. */
    const val NOT_CONNECTED_MESSAGE = "Native local player is not connected."

    /** User-visible message for malformed Dart-to-native command envelopes. */
    const val INVALID_COMMAND_ENVELOPE_MESSAGE =
        "Local player command envelope is missing an intent-level command."

    /** User-visible message when Android service binding cannot be established. */
    const val BIND_FAILED_MESSAGE = "Native local player service could not be bound."

    /** User-visible rejection for ready-state commands before command dispatch exists. */
    const val COMMAND_DISPATCH_DEFERRED_MESSAGE =
        "Native local player is connected, but Flutter command dispatch is not implemented."

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

    /**
     * Converts the canonical native [SendspinConnectionSnapshot] into the Dart bridge envelope.
     *
     * This is the single serialization boundary for native local-player connection state. It keeps
     * raw native status values, labels, media placeholders, and typed error payloads aligned with
     * Dart's `LocalPlayerSnapshot` parser while preventing duplicate service-specific state models.
     */
    fun snapshot(snapshot: SendspinConnectionSnapshot): Map<String, Any?> {
        val error = snapshot.error?.let { currentError ->
            errorEnvelope(currentError.code, currentError.message, currentError.details)
        }
        return mutableMapOf<String, Any?>(
            "connectionStatus" to snapshot.status.bridgeValue,
            "playerName" to "Mass Mate",
            "connectionLabel" to snapshot.connectionLabel,
            "mediaTitle" to snapshot.mediaTitle,
            "mediaSubtitle" to snapshot.mediaSubtitle,
            "positionMs" to 0,
            "trackLengthMs" to 1,
            "volume" to 0.0,
            "queueIndex" to 1,
            "queueMinIndex" to 1,
            "queueMaxIndex" to 1,
            "isPlaying" to false,
            "timing" to snapshot.timing.toBridgeMap(),
        ).apply {
            if (error != null) this["error"] = error
        }
    }
}
