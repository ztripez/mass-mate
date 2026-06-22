package dev.ztripez.massmate

import org.json.JSONObject

/**
 * Parses and dispatches post-handshake Sendspin text protocol messages.
 *
 * @param logger Native logger for unknown message diagnostics before visible failure.
 * @param events Native callback sink that owns supported parsed message families.
 */
class SendspinProtocolDispatcher(
    private val logger: SendspinProtocolLogger = JavaUtilSendspinProtocolLogger,
    private val events: SendspinProtocolEvents = FailHardSendspinProtocolEvents(logger),
) {
    /**
     * Parses [text] and routes known messages to native protocol owners.
     *
     * Unknown message types, malformed JSON, missing required fields, invalid known messages,
     * unsupported descriptor values, unsupported production families, and `server/error` messages
     * throw [SendspinConnectionException] with
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
            else -> throw unknownMessageType(type)
        }
    }

    private fun unknownMessageType(type: String): SendspinConnectionException {
        logger.warn("Received unsupported Sendspin message type.", mapOf("type" to type))
        return SendspinProtocolJson.protocolError(
            "Unsupported Sendspin message type `$type`.",
            mapOf("type" to type),
        )
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
        return SendspinStreamStart(
            streamId = requiredString(json, "streamId"),
            codec = parseStreamCodec(requiredString(json, "codec")),
            sampleRateHz = supportedInt("sampleRateHz", requiredInt(json, "sampleRateHz"), supportedSampleRateHz),
            channels = supportedInt("channels", requiredInt(json, "channels"), supportedChannelCounts),
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

private const val SERVER_STATE_TYPE = "server/state"
private const val SERVER_METADATA_TYPE = "server/metadata"
private const val STREAM_START_TYPE = "stream/start"
private const val STREAM_CLEAR_TYPE = "stream/clear"
private const val STREAM_END_TYPE = "stream/end"
private const val SERVER_COMMAND_TYPE = "server/command"
private const val SERVER_STATUS_TYPE = "server/status"
private const val SERVER_ERROR_TYPE = "server/error"

private const val PROTOCOL_MESSAGE_DESCRIPTION = "Sendspin protocol message"
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
