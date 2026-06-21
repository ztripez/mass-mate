package dev.ztripez.massmate

import org.json.JSONObject

/** Native-only outgoing Sendspin text message serializer. */
interface SendspinOutgoingMessage {
    /** Serializes the native protocol message as a strict JSON object. */
    fun toJson(): JSONObject

    /** Serializes the native protocol message to a UTF-8 text frame payload. */
    fun toText(): String = toJson().toString()
}

/** Connection states that the native local player may publish through `client/state`. */
enum class SendspinClientConnectionState(val wireValue: String) {
    /** Handshake passed and the local native endpoint is connected; no audio is claimed. */
    READY("ready"),
}

/**
 * Minimal local-player state sent after a validated handshake.
 *
 * @property connectionState Required native connection state to serialize.
 * @property isPlaying Required playback flag. Issue #27 sends `false` because playback is deferred.
 * @property streamActive Required stream flag. Issue #27 sends `false` because buffering is deferred.
 * @property audioOutputActive Required audio flag. Issue #27 sends `false` because audio is deferred.
 */
data class SendspinClientState(
    val connectionState: SendspinClientConnectionState,
    val isPlaying: Boolean = false,
    val streamActive: Boolean = false,
    val audioOutputActive: Boolean = false,
) : SendspinOutgoingMessage {
    override fun toJson(): JSONObject = JSONObject()
        .put("type", CLIENT_STATE_TYPE)
        .put("connectionState", connectionState.wireValue)
        .put("isPlaying", isPlaying)
        .put("streamActive", streamActive)
        .put("audioOutputActive", audioOutputActive)

    companion object {
        /** Initial state for issue #27: connected/ready, idle, no stream or audio claims. */
        fun initialReady(): SendspinClientState = SendspinClientState(SendspinClientConnectionState.READY)
    }
}

/** Native time-sync statuses serialized through `client/time` without implementing clock sync. */
enum class SendspinClientTimeStatus(val wireValue: String) {
    /** Clock sync is intentionally unavailable until issue #28 owns it. */
    UNAVAILABLE("unavailable"),
}

/**
 * Native-only `client/time` serializer for pre-clock-sync diagnostics.
 *
 * @property status Required time-sync status. Issue #27 only serializes [UNAVAILABLE].
 * @property reason Required diagnostic reason; it is not synchronized clock quality.
 */
data class SendspinClientTime(
    val status: SendspinClientTimeStatus,
    val reason: String,
) : SendspinOutgoingMessage {
    override fun toJson(): JSONObject = JSONObject()
        .put("type", CLIENT_TIME_TYPE)
        .put("status", status.wireValue)
        .put("reason", reason)

    companion object {
        /** Placeholder payload that does not pretend clock sync has been implemented. */
        fun unavailableUntilClockSync(): SendspinClientTime = SendspinClientTime(
            status = SendspinClientTimeStatus.UNAVAILABLE,
            reason = "clock-sync-deferred",
        )
    }
}

/** Raw native command names serializable through `client/command`; not Flutter intent mapping. */
enum class SendspinClientCommandKind(val wireValue: String) {
    PLAY("play"),
    PAUSE("pause"),
    SEEK_TO("seekTo"),
    SET_VOLUME("setVolume"),
}

/**
 * Typed `client/command` protocol serializer.
 *
 * This builder exists for native Sendspin protocol shapes only. It is not wired to Flutter
 * `PlaybackIntent`, wheel preview, or command execution; that mapping remains issue #32.
 *
 * @property command Required raw native protocol command to serialize.
 * @property requestId Optional native request identifier.
 * @property positionMs Optional absolute position in milliseconds for seek-like commands.
 * @property volume Optional normalized volume for volume-like commands.
 */
data class SendspinClientCommand(
    val command: SendspinClientCommandKind,
    val requestId: String? = null,
    val positionMs: Long? = null,
    val volume: Double? = null,
) : SendspinOutgoingMessage {
    override fun toJson(): JSONObject = JSONObject()
        .put("type", CLIENT_COMMAND_TYPE)
        .put("command", command.wireValue)
        .putSendspinOptional("requestId", requestId)
        .putSendspinOptional("positionMs", positionMs)
        .putSendspinOptional("volume", volume)
}

private const val CLIENT_STATE_TYPE = "client/state"
private const val CLIENT_TIME_TYPE = "client/time"
private const val CLIENT_COMMAND_TYPE = "client/command"
