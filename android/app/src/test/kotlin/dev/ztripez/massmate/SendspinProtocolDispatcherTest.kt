package dev.ztripez.massmate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendspinProtocolDispatcherTest {
    @Test
    fun dispatcherRoutesSupportedIncomingMessagesToTypedCallbacks() {
        val events = RecordingEvents()
        val dispatcher = SendspinProtocolDispatcher(events = events, logger = RecordingLogger())

        dispatcher.dispatch(
            json("server/state")
                .put("playbackState", "playing")
                .put("positionMs", 1234L)
                .put("durationMs", 5678L)
                .put("volume", 0.7)
                .toString(),
        )
        dispatcher.dispatch(
            json("server/metadata")
                .put("title", "Track")
                .put("subtitle", "Live")
                .put("artist", "Artist")
                .put("album", "Album")
                .put("artworkUrl", "https://example.test/art.png")
                .toString(),
        )
        dispatcher.dispatch(
            json("stream/start")
                .put("streamId", "stream-1")
                .put("codec", "pcm")
                .put("sampleRateHz", 48000)
                .put("channels", 2)
                .toString(),
        )
        dispatcher.dispatch(json("stream/clear").put("streamId", "stream-1").toString())
        dispatcher.dispatch(json("stream/end").put("streamId", "stream-1").put("reason", "complete").toString())
        dispatcher.dispatch(
            json("server/command")
                .put("command", "pause")
                .put("requestId", "request-7")
                .put("positionMs", 2000L)
                .toString(),
        )
        dispatcher.dispatch(json("server/status").put("status", "ok").put("message", "ready").toString())

        assertEquals(SendspinServerState("playing", 1234L, 5678L, 0.7), events.states.single())
        assertEquals(
            SendspinMetadata("Track", "Live", "Artist", "Album", "https://example.test/art.png"),
            events.metadata.single(),
        )
        assertEquals(SendspinStreamStart("stream-1", SendspinStreamCodec.PCM, 48000, 2), events.streamStarts.single())
        assertEquals(SendspinStreamClear("stream-1"), events.streamClears.single())
        assertEquals(SendspinStreamEnd("stream-1", "complete"), events.streamEnds.single())
        assertEquals(SendspinServerCommand(SendspinServerCommandKind.PAUSE, "request-7", 2000L), events.commands.single())
        assertEquals(SendspinServerStatus("ok", "ready"), events.statuses.single())
    }

    @Test
    fun dispatcherReportsServerErrorsThenFails() {
        val events = RecordingEvents()

        assertProtocolError {
            SendspinProtocolDispatcher(events = events, logger = RecordingLogger()).dispatch(
                json("server/error").put("code", "BAD_FRAME").put("message", "bad frame").toString(),
            )
        }

        assertEquals(SendspinServerProtocolError("BAD_FRAME", "bad frame"), events.errors.single())
    }

    @Test
    fun dispatcherLogsAndFailsUnknownMessages() {
        val events = RecordingEvents()
        val logger = RecordingLogger()
        val dispatcher = SendspinProtocolDispatcher(events = events, logger = logger)

        assertProtocolError {
            dispatcher.dispatch(json("server/future").put("payload", true).toString())
        }

        assertEquals(listOf("server/future"), logger.unknownTypes())
        assertFalse(events.hasAnyEvent())
    }

    @Test
    fun dispatcherFailsMalformedJsonAndInvalidKnownMessages() {
        assertProtocolError {
            SendspinProtocolDispatcher(logger = RecordingLogger()).dispatch("{not-json")
        }
        assertProtocolError {
            SendspinProtocolDispatcher(logger = RecordingLogger()).dispatch(json("server/state").toString())
        }
        assertProtocolError {
            SendspinProtocolDispatcher(logger = RecordingLogger()).dispatch(
                json("stream/start")
                    .put("streamId", "stream-1")
                    .put("codec", "pcm")
                    .put("sampleRateHz", "48000")
                    .put("channels", 2)
                    .toString(),
            )
        }
        assertProtocolError {
            SendspinProtocolDispatcher(logger = RecordingLogger()).dispatch(
                json("stream/start")
                    .put("streamId", "stream-1")
                    .put("codec", "flac")
                    .put("sampleRateHz", 48000)
                    .put("channels", 2)
                    .toString(),
            )
        }
        assertProtocolError {
            SendspinProtocolDispatcher(logger = RecordingLogger()).dispatch(
                json("stream/start")
                    .put("streamId", "stream-1")
                    .put("codec", "pcm")
                    .put("sampleRateHz", 96000)
                    .put("channels", 2)
                    .toString(),
            )
        }
        assertProtocolError {
            SendspinProtocolDispatcher(logger = RecordingLogger()).dispatch(
                json("stream/start")
                    .put("streamId", "stream-1")
                    .put("codec", "pcm")
                    .put("sampleRateHz", 48000)
                    .put("channels", 6)
                    .toString(),
            )
        }
        assertProtocolError {
            SendspinProtocolDispatcher(logger = RecordingLogger()).dispatch(
                json("server/command")
                    .put("command", "shuffle")
                    .toString(),
            )
        }
    }

    @Test
    fun outgoingMessagesHaveStrictJsonShapes() {
        val state = JSONObject(SendspinClientState.initialReady().toText())
        assertEquals("client/state", state.getString("type"))
        assertEquals("ready", state.getString("connectionState"))
        assertEquals(false, state.getBoolean("isPlaying"))
        assertEquals(false, state.getBoolean("streamActive"))
        assertEquals(false, state.getBoolean("audioOutputActive"))
        assertEquals(
            setOf("type", "connectionState", "isPlaying", "streamActive", "audioOutputActive"),
            state.keys().asSequence().toSet(),
        )

        val time = JSONObject(SendspinClientTime.unavailableUntilClockSync().toText())
        assertEquals("client/time", time.getString("type"))
        assertEquals("unavailable", time.getString("status"))
        assertEquals("clock-sync-deferred", time.getString("reason"))

        val command = JSONObject(
            SendspinClientCommand(
                command = SendspinClientCommandKind.SEEK_TO,
                requestId = "request-1",
                positionMs = 42000L,
            ).toText(),
        )
        assertEquals("client/command", command.getString("type"))
        assertEquals("seekTo", command.getString("command"))
        assertEquals("request-1", command.getString("requestId"))
        assertEquals(42000L, command.getLong("positionMs"))
        assertFalse(command.has("volume"))
    }

    private fun json(type: String): JSONObject = JSONObject().put("type", type)

    private fun assertProtocolError(block: () -> Unit) {
        try {
            block()
        } catch (error: SendspinConnectionException) {
            assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR, error.code)
            return
        }
        throw AssertionError("Expected SendspinConnectionException")
    }
}

private class RecordingEvents : SendspinProtocolEvents {
    val states = mutableListOf<SendspinServerState>()
    val metadata = mutableListOf<SendspinMetadata>()
    val streamStarts = mutableListOf<SendspinStreamStart>()
    val streamClears = mutableListOf<SendspinStreamClear>()
    val streamEnds = mutableListOf<SendspinStreamEnd>()
    val commands = mutableListOf<SendspinServerCommand>()
    val statuses = mutableListOf<SendspinServerStatus>()
    val errors = mutableListOf<SendspinServerProtocolError>()

    override fun onServerState(state: SendspinServerState) {
        states.add(state)
    }

    override fun onMetadata(metadata: SendspinMetadata) {
        this.metadata.add(metadata)
    }

    override fun onStreamStart(stream: SendspinStreamStart) {
        streamStarts.add(stream)
    }

    override fun onStreamClear(stream: SendspinStreamClear) {
        streamClears.add(stream)
    }

    override fun onStreamEnd(stream: SendspinStreamEnd) {
        streamEnds.add(stream)
    }

    override fun onServerCommand(command: SendspinServerCommand) {
        commands.add(command)
    }

    override fun onServerStatus(status: SendspinServerStatus) {
        statuses.add(status)
    }

    override fun onServerProtocolError(error: SendspinServerProtocolError) {
        errors.add(error)
    }

    fun hasAnyEvent(): Boolean = listOf(
        states,
        metadata,
        streamStarts,
        streamClears,
        streamEnds,
        commands,
        statuses,
        errors,
    ).any { it.isNotEmpty() }
}

private class RecordingLogger : SendspinProtocolLogger {
    private val warnings = mutableListOf<Map<String, Any?>?>()

    override fun warn(message: String, details: Map<String, Any?>?) {
        warnings.add(details)
    }

    fun unknownTypes(): List<String> = warnings.mapNotNull { it?.get("type") as? String }
}
