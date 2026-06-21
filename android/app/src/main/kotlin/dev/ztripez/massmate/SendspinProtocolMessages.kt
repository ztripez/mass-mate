package dev.ztripez.massmate

import java.util.logging.Logger
import org.json.JSONException
import org.json.JSONObject

/** Native-only Sendspin text protocol models, parsers, serializers, and dispatcher. */

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

/** Callback surface for typed Sendspin events owned by later native player slices. */
interface SendspinProtocolEvents {
    /** Server playback/state update event. */
    fun onServerState(state: SendspinServerState) = Unit

    /** Server metadata update event. Missing fields are absent, not inferred by UI code. */
    fun onMetadata(metadata: SendspinMetadata) = Unit

    /** Stream descriptor announcing a scheduled stream; buffering/audio remain out of scope here. */
    fun onStreamStart(stream: SendspinStreamStart) = Unit

    /** Stream clear descriptor; actual buffer clearing belongs to the stream slice. */
    fun onStreamClear(stream: SendspinStreamClear) = Unit

    /** Stream end descriptor; actual audio teardown belongs to the stream/audio slices. */
    fun onStreamEnd(stream: SendspinStreamEnd) = Unit

    /** Server command event; intent mapping and command execution belong to later slices. */
    fun onServerCommand(command: SendspinServerCommand) = Unit

    /** Non-fatal server protocol/status event. */
    fun onServerStatus(status: SendspinServerStatus) = Unit

    /** Server-reported protocol error event. */
    fun onServerProtocolError(error: SendspinServerProtocolError) = Unit
}

/** Default event sink for production paths that have not wired later protocol owners yet. */
object NoopSendspinProtocolEvents : SendspinProtocolEvents

/** Minimal typed server state event emitted by `server/state`. */
data class SendspinServerState(
    val playbackState: String,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val volume: Double? = null,
)

/** Minimal typed media metadata event emitted by `server/metadata`. */
data class SendspinMetadata(
    val title: String? = null,
    val subtitle: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
)

/** Descriptor for a `stream/start` event; this slice does not buffer or decode frames. */
data class SendspinStreamStart(
    val streamId: String,
    val codec: String,
    val sampleRateHz: Int,
    val channels: Int,
)

/** Descriptor for a `stream/clear` event. A missing [streamId] means clear all stream state. */
data class SendspinStreamClear(val streamId: String? = null)

/** Descriptor for a `stream/end` event. */
data class SendspinStreamEnd(
    val streamId: String,
    val reason: String? = null,
)

/** Server-originated command event. Mapping this to Mass Mate intents is deferred. */
data class SendspinServerCommand(
    val command: String,
    val requestId: String? = null,
    val positionMs: Long? = null,
    val volume: Double? = null,
)

/** Server status event for native debug/diagnostic owners. */
data class SendspinServerStatus(
    val status: String,
    val message: String? = null,
)

/** Server-reported protocol error event. */
data class SendspinServerProtocolError(
    val code: String,
    val message: String,
)

/** Outgoing Sendspin text message model. */
interface SendspinOutgoingMessage {
    /** Serializes the message as a strict JSON object. */
    fun toJson(): JSONObject

    /** Serializes the message to the UTF-8 text frame payload. */
    fun toText(): String = toJson().toString()
}

/** Connection states published by the local player through `client/state`. */
enum class SendspinClientConnectionState(val wireValue: String) {
    READY("ready"),
}

/** Minimal honest local-player state sent after a validated handshake. */
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

/** Time-sync status published through `client/time`; actual clock sync belongs to issue #28. */
enum class SendspinClientTimeStatus(val wireValue: String) {
    UNAVAILABLE("unavailable"),
}

/** Explicitly typed `client/time` payload for the pre-clock-sync slice. */
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

/** Raw command families supported by the native protocol model; intent mapping is deferred. */
enum class SendspinClientCommandKind(val wireValue: String) {
    PLAY("play"),
    PAUSE("pause"),
    SEEK_TO("seekTo"),
    SET_VOLUME("setVolume"),
}

/** Typed `client/command` payload builder. Dispatch from Flutter intents belongs to issue #32. */
data class SendspinClientCommand(
    val command: SendspinClientCommandKind,
    val requestId: String? = null,
    val positionMs: Long? = null,
    val volume: Double? = null,
) : SendspinOutgoingMessage {
    override fun toJson(): JSONObject = JSONObject()
        .put("type", CLIENT_COMMAND_TYPE)
        .put("command", command.wireValue)
        .putOptional("requestId", requestId)
        .putOptional("positionMs", positionMs)
        .putOptional("volume", volume)
}

/** Parses and dispatches post-handshake Sendspin text protocol messages. */
class SendspinProtocolDispatcher(
    private val events: SendspinProtocolEvents = NoopSendspinProtocolEvents,
    private val logger: SendspinProtocolLogger = JavaUtilSendspinProtocolLogger,
) {
    /** Parses [text] and routes known messages; unknown types are logged and ignored. */
    fun dispatch(text: String) {
        val json = try {
            JSONObject(text)
        } catch (error: JSONException) {
            throw protocolError("Malformed Sendspin protocol JSON.", mapOf("message" to error.message))
        }

        when (val type = json.requiredString("type")) {
            SERVER_STATE_TYPE -> events.onServerState(parseServerState(json))
            SERVER_METADATA_TYPE -> events.onMetadata(parseMetadata(json))
            STREAM_START_TYPE -> events.onStreamStart(parseStreamStart(json))
            STREAM_CLEAR_TYPE -> events.onStreamClear(parseStreamClear(json))
            STREAM_END_TYPE -> events.onStreamEnd(parseStreamEnd(json))
            SERVER_COMMAND_TYPE -> events.onServerCommand(parseServerCommand(json))
            SERVER_STATUS_TYPE -> events.onServerStatus(parseServerStatus(json))
            SERVER_ERROR_TYPE -> events.onServerProtocolError(parseServerProtocolError(json))
            else -> logger.warn("Ignoring unknown Sendspin message type.", mapOf("type" to type))
        }
    }

    private fun parseServerState(json: JSONObject): SendspinServerState = SendspinServerState(
        playbackState = json.requiredString("playbackState"),
        positionMs = json.optionalLong("positionMs"),
        durationMs = json.optionalLong("durationMs"),
        volume = json.optionalDouble("volume"),
    )

    private fun parseMetadata(json: JSONObject): SendspinMetadata = SendspinMetadata(
        title = json.optionalString("title"),
        subtitle = json.optionalString("subtitle"),
        artist = json.optionalString("artist"),
        album = json.optionalString("album"),
        artworkUrl = json.optionalString("artworkUrl"),
    )

    private fun parseStreamStart(json: JSONObject): SendspinStreamStart = SendspinStreamStart(
        streamId = json.requiredString("streamId"),
        codec = json.requiredString("codec"),
        sampleRateHz = json.requiredInt("sampleRateHz"),
        channels = json.requiredInt("channels"),
    )

    private fun parseStreamClear(json: JSONObject): SendspinStreamClear = SendspinStreamClear(
        streamId = json.optionalString("streamId"),
    )

    private fun parseStreamEnd(json: JSONObject): SendspinStreamEnd = SendspinStreamEnd(
        streamId = json.requiredString("streamId"),
        reason = json.optionalString("reason"),
    )

    private fun parseServerCommand(json: JSONObject): SendspinServerCommand = SendspinServerCommand(
        command = json.requiredString("command"),
        requestId = json.optionalString("requestId"),
        positionMs = json.optionalLong("positionMs"),
        volume = json.optionalDouble("volume"),
    )

    private fun parseServerStatus(json: JSONObject): SendspinServerStatus = SendspinServerStatus(
        status = json.requiredString("status"),
        message = json.optionalString("message"),
    )

    private fun parseServerProtocolError(json: JSONObject): SendspinServerProtocolError =
        SendspinServerProtocolError(
            code = json.requiredString("code"),
            message = json.requiredString("message"),
        )
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

private fun JSONObject.requiredString(field: String): String {
    val value = requiredValue(field)
    if (value !is String) throw protocolFieldError(field, "string")
    return value
}

private fun JSONObject.optionalString(field: String): String? {
    if (!has(field) || isNull(field)) return null
    val value = get(field)
    if (value !is String) throw protocolFieldError(field, "string")
    return value
}

private fun JSONObject.requiredInt(field: String): Int {
    val value = requiredValue(field)
    if (value !is Int) throw protocolFieldError(field, "integer")
    return value
}

private fun JSONObject.optionalLong(field: String): Long? {
    if (!has(field) || isNull(field)) return null
    return when (val value = get(field)) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw protocolFieldError(field, "integer")
    }
}

private fun JSONObject.optionalDouble(field: String): Double? {
    if (!has(field) || isNull(field)) return null
    return when (val value = get(field)) {
        is Number -> value.toDouble()
        else -> throw protocolFieldError(field, "number")
    }
}

private fun JSONObject.requiredValue(field: String): Any {
    if (!has(field) || isNull(field)) {
        throw protocolError("Sendspin protocol message is missing required field `$field`.")
    }
    return get(field)
}

private fun JSONObject.putOptional(field: String, value: Any?): JSONObject {
    if (value != null) put(field, value)
    return this
}

private fun protocolFieldError(field: String, expectedType: String): SendspinConnectionException =
    protocolError("Sendspin protocol field `$field` must be a $expectedType.")

private fun protocolError(
    message: String,
    details: Map<String, Any?>? = null,
): SendspinConnectionException = SendspinConnectionException(
    LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR,
    message,
    details,
)
