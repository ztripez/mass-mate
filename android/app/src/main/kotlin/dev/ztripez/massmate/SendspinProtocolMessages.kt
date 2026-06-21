package dev.ztripez.massmate

import java.util.logging.Logger
import org.json.JSONObject

/** Receives non-fatal protocol diagnostics such as ignored unknown message types. */
interface SendspinProtocolLogger {
    /** Records [message] and optional structured [details] for native debugging. */
    fun warn(message: String, details: Map<String, Any?>? = null)
}

/** JVM/Android logger used outside focused parser tests. */
object JavaUtilSendspinProtocolLogger : SendspinProtocolLogger {
    private val logger = Logger.getLogger("dev.ztripez.massmate.sendspin.protocol")

    override fun warn(message: String, details: Map<String, Any?>?) {
        logger.warning(if (details == null) message else "$message $details")
    }
}

/**
 * Native-owner callback surface for parsed Sendspin protocol events.
 *
 * Implementations are native Android owners only: Flutter widgets must not consume raw Sendspin
 * message names or payloads. A later stream, timing, command, or snapshot owner may implement this
 * interface to take ownership of a supported family. Until that owner exists, production uses
 * [FailHardSendspinProtocolEvents] so unsupported known families fail visibly instead of being
 * accepted as successful player state.
 */
interface SendspinProtocolEvents {
    /**
     * Receives a parsed server playback/state [state] update for a native snapshot/debug owner.
     *
     * This callback is a handoff boundary only; it must not be wired directly to Flutter widgets or
     * used to create a parallel Flutter player-state model.
     */
    fun onServerState(state: SendspinServerState)

    /**
     * Receives parsed server media [metadata] for a future native snapshot owner.
     *
     * Missing optional fields mean the server did not provide that field in this message; Flutter UI
     * mapping remains deferred to the native snapshot bridge slice.
     */
    fun onMetadata(metadata: SendspinMetadata)

    /**
     * Receives a validated stream [stream] start descriptor for a future native stream owner.
     *
     * This is not an audio-buffer or decoder callback, and Flutter widgets must not consume the raw
     * stream descriptor.
     */
    fun onStreamStart(stream: SendspinStreamStart)

    /**
     * Receives a validated stream clear [stream] descriptor for a future native stream owner.
     *
     * The actual buffer clear behavior belongs to the stream/buffer issue, not this dispatcher.
     */
    fun onStreamClear(stream: SendspinStreamClear)

    /**
     * Receives a validated stream end [stream] descriptor for a future native stream owner.
     *
     * The actual audio or buffer teardown belongs to later native slices.
     */
    fun onStreamEnd(stream: SendspinStreamEnd)

    /**
     * Receives a validated server-originated [command] for a future native command owner.
     *
     * This callback is raw Sendspin protocol handling. It is not Flutter intent mapping, and it must
     * not be exposed to wheel/UI widgets.
     */
    fun onServerCommand(command: SendspinServerCommand)

    /**
     * Receives parsed server [status] diagnostics for a native debug owner.
     *
     * Status messages are diagnostic; they do not imply UI playback-state changes in this slice.
     */
    fun onServerStatus(status: SendspinServerStatus)

    /**
     * Receives parsed server protocol [error] details before the dispatcher fails the session.
     *
     * A `server/error` message always becomes a visible protocol failure through the controller even
     * when an implementation records this callback for diagnostics.
     */
    fun onServerProtocolError(error: SendspinServerProtocolError)
}

/** Production event sink for known families that have no native owner in issue #27. */
class FailHardSendspinProtocolEvents(
    private val logger: SendspinProtocolLogger,
) : SendspinProtocolEvents {
    override fun onServerState(state: SendspinServerState) {
        logger.warn("Received Sendspin server state before snapshot mapping exists.")
    }

    override fun onMetadata(metadata: SendspinMetadata) {
        logger.warn("Received Sendspin metadata before snapshot mapping exists.")
    }

    override fun onStreamStart(stream: SendspinStreamStart) {
        throw unsupportedFamily("stream/start")
    }

    override fun onStreamClear(stream: SendspinStreamClear) {
        throw unsupportedFamily("stream/clear")
    }

    override fun onStreamEnd(stream: SendspinStreamEnd) {
        throw unsupportedFamily("stream/end")
    }

    override fun onServerCommand(command: SendspinServerCommand) {
        throw unsupportedFamily("server/command")
    }

    override fun onServerStatus(status: SendspinServerStatus) {
        logger.warn("Received Sendspin server status.", mapOf("status" to status.status))
    }

    override fun onServerProtocolError(error: SendspinServerProtocolError) {
        // The dispatcher also throws after this callback so server errors cannot be swallowed.
        logger.warn("Received Sendspin server protocol error.", mapOf("code" to error.code))
    }

    private fun unsupportedFamily(type: String): SendspinConnectionException =
        SendspinProtocolJson.protocolError(
            "Sendspin message family `$type` has no native owner in this implementation slice.",
            mapOf("type" to type),
        )
}

/**
 * Minimal typed server state event emitted by `server/state`.
 *
 * @property playbackState Required protocol playback-state string supplied by the server. This is a
 * native protocol value for a future snapshot owner, not a Flutter UI state enum.
 * @property positionMs Optional committed playback position in milliseconds. `null` means the field
 * was omitted and must not clear previously known native state by itself.
 * @property durationMs Optional media duration in milliseconds. `null` means unknown or omitted.
 * @property volume Optional normalized volume reported by the server. `null` means omitted; this
 * slice does not map it to the Dart bridge.
 */
data class SendspinServerState(
    val playbackState: String,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val volume: Double? = null,
)

/**
 * Minimal typed media metadata event emitted by `server/metadata`.
 *
 * @property title Optional track title. `null` means the field was omitted, not an instruction to
 * erase existing UI metadata.
 * @property subtitle Optional display subtitle supplied by the server.
 * @property artist Optional artist name supplied by the server.
 * @property album Optional album name supplied by the server.
 * @property artworkUrl Optional artwork URL string supplied by the server.
 */
data class SendspinMetadata(
    val title: String? = null,
    val subtitle: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
)

/** Stream codecs accepted as valid protocol descriptors without enabling audio output. */
enum class SendspinStreamCodec(val wireValue: String) {
    /** Baseline PCM stream descriptor; buffering and audio writes remain deferred. */
    PCM("pcm"),
}

/**
 * Descriptor for a `stream/start` event parsed for a future native stream owner.
 *
 * @property streamId Required server stream identifier. It scopes later binary frames, but this
 * slice does not buffer binary frames.
 * @property codec Required validated codec descriptor. Validation does not advertise working audio.
 * @property sampleRateHz Required sample rate in hertz. Only descriptor values accepted by this
 * client are parsed; unsupported values fail with `LOCAL_PLAYER_PROTOCOL_ERROR`.
 * @property channels Required channel count. Unsupported counts fail before reaching owners.
 */
data class SendspinStreamStart(
    val streamId: String,
    val codec: SendspinStreamCodec,
    val sampleRateHz: Int,
    val channels: Int,
)

/**
 * Descriptor for a `stream/clear` event parsed for a future native stream owner.
 *
 * @property streamId Optional server stream identifier. `null` means the server requested a clear of
 * all stream-owned native state; this slice does not perform buffer operations.
 */
data class SendspinStreamClear(val streamId: String? = null)

/**
 * Descriptor for a `stream/end` event parsed for a future native stream owner.
 *
 * @property streamId Required server stream identifier that ended.
 * @property reason Optional server reason string. `null` means no reason was supplied.
 */
data class SendspinStreamEnd(
    val streamId: String,
    val reason: String? = null,
)

/** Raw server command names accepted as protocol descriptors without mapping Flutter intents. */
enum class SendspinServerCommandKind(val wireValue: String) {
    /** Server requested play behavior; execution is deferred to a native command owner. */
    PLAY("play"),

    /** Server requested pause behavior; execution is deferred to a native command owner. */
    PAUSE("pause"),

    /** Server requested an absolute seek; execution is deferred to command mapping. */
    SEEK_TO("seekTo"),

    /** Server requested a volume change; execution is deferred to command mapping. */
    SET_VOLUME("setVolume"),
}

/**
 * Server-originated command event parsed for a future native command owner.
 *
 * @property command Required validated raw Sendspin command. This is not a Flutter
 * `PlaybackIntent` and must not be exposed to widgets.
 * @property requestId Optional server request identifier used for native replies when implemented.
 * @property positionMs Optional absolute position in milliseconds for seek-like commands.
 * @property volume Optional normalized volume value for volume-like commands.
 */
data class SendspinServerCommand(
    val command: SendspinServerCommandKind,
    val requestId: String? = null,
    val positionMs: Long? = null,
    val volume: Double? = null,
)

/**
 * Server status event for native debug/diagnostic owners.
 *
 * @property status Required server status string.
 * @property message Optional human-readable diagnostic message. `null` means no message was sent.
 */
data class SendspinServerStatus(
    val status: String,
    val message: String? = null,
)

/**
 * Server-reported protocol error event.
 *
 * @property code Required server error code string.
 * @property message Required server error message. Receipt always fails the active session visibly.
 */
data class SendspinServerProtocolError(
    val code: String,
    val message: String,
)

/** Native-only outgoing Sendspin text message serializer. */
interface SendspinOutgoingMessage {
    /** Serializes the native protocol message as a strict JSON object. */
    fun toJson(): JSONObject

    /** Serializes the native protocol message to a UTF-8 text frame payload. */
    fun toText(): String = toJson().toString()
}

/** Connection states that the native local player may publish through `client/state`. */
enum class SendspinClientConnectionState(val wireValue: String) {
    /** Handshake passed and the local native endpoint is connected, but no audio is claimed. */
    READY("ready"),
}

/**
 * Minimal honest local-player state sent after a validated handshake.
 *
 * @property connectionState Required native connection state to serialize.
 * @property isPlaying Required playback flag. This slice sends `false` because audio playback is not
 * implemented.
 * @property streamActive Required stream flag. This slice sends `false` because buffering is not
 * implemented.
 * @property audioOutputActive Required audio-output flag. This slice sends `false` because audio is
 * not implemented.
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
    /** Clock sync is intentionally unavailable until the dedicated timing slice owns it. */
    UNAVAILABLE("unavailable"),
}

/**
 * Native-only `client/time` serializer for pre-clock-sync diagnostics.
 *
 * @property status Required time-sync status. Issue #27 only serializes [UNAVAILABLE].
 * @property reason Required native diagnostic reason. It must not be interpreted as synchronized
 * clock quality.
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
    /** Native protocol serialization for play. */
    PLAY("play"),

    /** Native protocol serialization for pause. */
    PAUSE("pause"),

    /** Native protocol serialization for absolute seek. */
    SEEK_TO("seekTo"),

    /** Native protocol serialization for absolute volume. */
    SET_VOLUME("setVolume"),
}

/**
 * Typed `client/command` protocol serializer.
 *
 * This builder exists to cover native Sendspin protocol shapes for issue #27. It is not wired to
 * Flutter `PlaybackIntent`, wheel preview, or command execution; that mapping remains issue #32.
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

/** Parses and dispatches post-handshake Sendspin text protocol messages. */
class SendspinProtocolDispatcher(
    private val logger: SendspinProtocolLogger = JavaUtilSendspinProtocolLogger,
    private val events: SendspinProtocolEvents = FailHardSendspinProtocolEvents(logger),
) {
    /**
     * Parses [text] and routes known messages to native protocol owners.
     *
     * Unknown message types are logged and ignored. Malformed JSON, missing required fields, invalid
     * known messages, unsupported descriptor values, unsupported production families, and
     * `server/error` messages throw [SendspinConnectionException] with
     * [LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR].
     */
    fun dispatch(text: String) {
        val json = SendspinProtocolJson.parseObject(text, "Sendspin protocol message")

        when (val type = requiredString(json, "type")) {
            SERVER_STATE_TYPE -> events.onServerState(parseServerState(json))
            SERVER_METADATA_TYPE -> events.onMetadata(parseMetadata(json))
            STREAM_START_TYPE -> events.onStreamStart(parseStreamStart(json))
            STREAM_CLEAR_TYPE -> events.onStreamClear(parseStreamClear(json))
            STREAM_END_TYPE -> events.onStreamEnd(parseStreamEnd(json))
            SERVER_COMMAND_TYPE -> events.onServerCommand(parseServerCommand(json))
            SERVER_STATUS_TYPE -> events.onServerStatus(parseServerStatus(json))
            SERVER_ERROR_TYPE -> dispatchServerProtocolError(parseServerProtocolError(json))
            else -> logger.warn("Ignoring unknown Sendspin message type.", mapOf("type" to type))
        }
    }

    private fun parseServerState(json: JSONObject): SendspinServerState = SendspinServerState(
        playbackState = requiredString(json, "playbackState"),
        positionMs = optionalLong(json, "positionMs"),
        durationMs = optionalLong(json, "durationMs"),
        volume = optionalDouble(json, "volume"),
    )

    private fun parseMetadata(json: JSONObject): SendspinMetadata = SendspinMetadata(
        title = optionalString(json, "title"),
        subtitle = optionalString(json, "subtitle"),
        artist = optionalString(json, "artist"),
        album = optionalString(json, "album"),
        artworkUrl = optionalString(json, "artworkUrl"),
    )

    private fun parseStreamStart(json: JSONObject): SendspinStreamStart {
        val codec = parseStreamCodec(requiredString(json, "codec"))
        val sampleRateHz = supportedInt(
            field = "sampleRateHz",
            value = requiredInt(json, "sampleRateHz"),
            supportedValues = supportedSampleRateHz,
        )
        val channels = supportedInt(
            field = "channels",
            value = requiredInt(json, "channels"),
            supportedValues = supportedChannelCounts,
        )
        return SendspinStreamStart(
            streamId = requiredString(json, "streamId"),
            codec = codec,
            sampleRateHz = sampleRateHz,
            channels = channels,
        )
    }

    private fun parseStreamClear(json: JSONObject): SendspinStreamClear = SendspinStreamClear(
        streamId = optionalString(json, "streamId"),
    )

    private fun parseStreamEnd(json: JSONObject): SendspinStreamEnd = SendspinStreamEnd(
        streamId = requiredString(json, "streamId"),
        reason = optionalString(json, "reason"),
    )

    private fun parseServerCommand(json: JSONObject): SendspinServerCommand = SendspinServerCommand(
        command = parseServerCommandKind(requiredString(json, "command")),
        requestId = optionalString(json, "requestId"),
        positionMs = optionalLong(json, "positionMs"),
        volume = optionalDouble(json, "volume"),
    )

    private fun parseServerStatus(json: JSONObject): SendspinServerStatus = SendspinServerStatus(
        status = requiredString(json, "status"),
        message = optionalString(json, "message"),
    )

    private fun parseServerProtocolError(json: JSONObject): SendspinServerProtocolError =
        SendspinServerProtocolError(
            code = requiredString(json, "code"),
            message = requiredString(json, "message"),
        )

    private fun dispatchServerProtocolError(error: SendspinServerProtocolError) {
        events.onServerProtocolError(error)
        throw SendspinProtocolJson.protocolError(
            "Sendspin server reported a protocol error.",
            mapOf("code" to error.code, "message" to error.message),
        )
    }
}

private const val CLIENT_STATE_TYPE = "client/state"
private const val CLIENT_TIME_TYPE = "client/time"
private const val CLIENT_COMMAND_TYPE = "client/command"
private const val SERVER_STATE_TYPE = "server/state"
private const val SERVER_METADATA_TYPE = "server/metadata"
private const val STREAM_START_TYPE = "stream/start"
private const val STREAM_CLEAR_TYPE = "stream/clear"
private const val STREAM_END_TYPE = "stream/end"
private const val SERVER_COMMAND_TYPE = "server/command"
private const val SERVER_STATUS_TYPE = "server/status"
private const val SERVER_ERROR_TYPE = "server/error"

private val supportedSampleRateHz = setOf(44100, 48000)
private val supportedChannelCounts = setOf(1, 2)

private fun requiredString(json: JSONObject, field: String): String =
    SendspinProtocolJson.requiredString(json, field, PROTOCOL_MESSAGE_DESCRIPTION)

private fun optionalString(json: JSONObject, field: String): String? =
    SendspinProtocolJson.optionalString(json, field, PROTOCOL_MESSAGE_DESCRIPTION)

private fun requiredInt(json: JSONObject, field: String): Int =
    SendspinProtocolJson.requiredInt(json, field, PROTOCOL_MESSAGE_DESCRIPTION)

private fun optionalLong(json: JSONObject, field: String): Long? =
    SendspinProtocolJson.optionalLong(json, field, PROTOCOL_MESSAGE_DESCRIPTION)

private fun optionalDouble(json: JSONObject, field: String): Double? =
    SendspinProtocolJson.optionalDouble(json, field, PROTOCOL_MESSAGE_DESCRIPTION)

private fun supportedInt(field: String, value: Int, supportedValues: Set<Int>): Int =
    SendspinProtocolJson.requireSupported(field, value, supportedValues, PROTOCOL_MESSAGE_DESCRIPTION)

private fun parseStreamCodec(value: String): SendspinStreamCodec {
    val supported = SendspinStreamCodec.entries.associateBy { it.wireValue }
    val wireValue = SendspinProtocolJson.requireSupported(
        field = "codec",
        value = value,
        supportedValues = supported.keys,
        description = PROTOCOL_MESSAGE_DESCRIPTION,
    )
    return supported.getValue(wireValue)
}

private fun parseServerCommandKind(value: String): SendspinServerCommandKind {
    val supported = SendspinServerCommandKind.entries.associateBy { it.wireValue }
    val wireValue = SendspinProtocolJson.requireSupported(
        field = "command",
        value = value,
        supportedValues = supported.keys,
        description = PROTOCOL_MESSAGE_DESCRIPTION,
    )
    return supported.getValue(wireValue)
}

private const val PROTOCOL_MESSAGE_DESCRIPTION = "Sendspin protocol message"
