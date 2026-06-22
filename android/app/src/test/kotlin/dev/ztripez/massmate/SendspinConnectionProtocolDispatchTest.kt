package dev.ztripez.massmate

import java.net.URI
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendspinConnectionProtocolDispatchTest {
    @Test
    fun postReadyUnknownTextFailsVisibly() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val logger = CapturingProtocolLogger()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
            protocolLogger = logger,
            timingController = SendspinTimingController(monotonicClock = SendspinMonotonicClock { 1_000L }),
        )

        connectReady(controller, transport)
        transport.receiveText("{\"type\":\"server/future\",\"value\":1}")

        assertProtocolFailure(snapshots)
        assertEquals(listOf("server/future"), logger.unknownTypes())
    }

    @Test
    fun postReadyInvalidKnownTextFailsVisibly() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
            timingController = SendspinTimingController(monotonicClock = SendspinMonotonicClock { 1_000L }),
        )

        connectReady(controller, transport)
        transport.receiveText("{\"type\":\"stream/start\",\"codec\":\"pcm\",\"sampleRateHz\":48000,\"channels\":2}")

        assertProtocolFailure(snapshots)
        assertTrue(snapshots.last().error?.message.orEmpty().contains("streamId"))
    }

    @Test
    fun productionDispatchFailsKnownUnsupportedFamilies() {
        val stateSnapshots = readySnapshotsBefore("{\"type\":\"server/state\",\"playbackState\":\"playing\"}")
        val metadataSnapshots = readySnapshotsBefore("{\"type\":\"server/metadata\",\"title\":\"Track\"}")
        val commandSnapshots = readySnapshotsBefore("{\"type\":\"server/command\",\"command\":\"pause\"}")
        val statusSnapshots = readySnapshotsBefore("{\"type\":\"server/status\",\"status\":\"ok\"}")

        assertProtocolFailure(stateSnapshots)
        assertTrue(stateSnapshots.last().error?.message.orEmpty().contains("server/state"))
        assertProtocolFailure(metadataSnapshots)
        assertTrue(metadataSnapshots.last().error?.message.orEmpty().contains("server/metadata"))
        assertProtocolFailure(commandSnapshots)
        assertTrue(commandSnapshots.last().error?.message.orEmpty().contains("server/command"))
        assertProtocolFailure(statusSnapshots)
        assertTrue(statusSnapshots.last().error?.message.orEmpty().contains("server/status"))
    }

    @Test
    fun streamLifecycleAndBinaryFramesUpdateSnapshots() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
            timingController = SendspinTimingController(monotonicClock = SendspinMonotonicClock { 1_000L }),
        )

        connectReady(controller, transport)
        transport.receiveText(streamStart("stream-1"))
        transport.receiveBinary(binaryFrame("stream-1", timestampMs = 2_000L, sequence = 2L))
        transport.receiveBinary(binaryFrame("stream-1", timestampMs = 1_000L, sequence = 1L))

        val buffered = snapshots.last().stream
        assertEquals(SendspinConnectionStatus.READY, snapshots.last().status)
        assertTrue(buffered.active)
        assertEquals("stream-1", buffered.streamId)
        assertEquals(2, buffered.frameCount)
        assertEquals(1_000L, buffered.bufferDepthMs)

        transport.receiveText(streamClear("stream-1"))
        val cleared = snapshots.last().stream
        assertTrue(cleared.active)
        assertEquals(0, cleared.frameCount)

        transport.receiveText(streamEnd("stream-1"))
        val ended = snapshots.last().stream
        assertEquals(false, ended.active)
        assertEquals(0, ended.frameCount)
    }

    @Test
    fun binaryFramesBeforeStreamStartAreIgnoredSafelyByController() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
            timingController = SendspinTimingController(monotonicClock = SendspinMonotonicClock { 1_000L }),
        )

        connectReady(controller, transport)
        transport.receiveBinary(binaryFrame("stream-1", timestampMs = 1_000L, sequence = 1L))

        assertEquals(SendspinConnectionStatus.READY, snapshots.last().status)
        assertEquals(1L, snapshots.last().stream.droppedFrameCount)
        assertEquals("no-active-stream", snapshots.last().stream.lastDropReason)
    }

    @Test
    fun malformedBinaryFrameFailsVisiblyWhenStreamIsActive() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
            timingController = SendspinTimingController(monotonicClock = SendspinMonotonicClock { 1_000L }),
        )

        connectReady(controller, transport)
        transport.receiveText(streamStart("stream-1"))
        transport.receiveBinary(byteArrayOf(0x01, 0x02))

        assertProtocolFailure(snapshots)
        assertTrue(snapshots.last().error?.message.orEmpty().contains("binary frame"))
    }

    @Test
    fun serverErrorAlwaysFailsSession() {
        val events = CapturingProtocolEvents()
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
            protocolEvents = events,
            timingController = SendspinTimingController(monotonicClock = SendspinMonotonicClock { 1_000L }),
        )

        connectReady(controller, transport)
        transport.receiveText(
            JSONObject()
                .put("type", "server/error")
                .put("code", "BAD_FRAME")
                .put("message", "bad frame")
                .toString(),
        )

        assertProtocolFailure(snapshots)
        assertEquals(SendspinServerProtocolError("BAD_FRAME", "bad frame"), events.protocolErrors.single())
    }

    @Test
    fun postReadyKnownTextRoutesTypedEventsWhenNativeOwnerExists() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val events = CapturingProtocolEvents()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
            protocolEvents = events,
            timingController = SendspinTimingController(monotonicClock = SendspinMonotonicClock { 1_000L }),
        )

        connectReady(controller, transport)
        transport.receiveText(
            JSONObject()
                .put("type", "server/metadata")
                .put("title", "Captured Song")
                .put("artist", "Captured Artist")
                .toString(),
        )

        assertEquals(SendspinConnectionStatus.READY, snapshots.last().status)
        assertEquals(SendspinMetadata(title = "Captured Song", artist = "Captured Artist"), events.metadata.single())
    }

    @Test
    fun serverTimeResponsesUpdateTimingSnapshots() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val clock = MutableTestClock(1_000L)
        val timingController = SendspinTimingController(monotonicClock = clock, minimumRequestIntervalMs = 0L)
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
            timingController = timingController,
        )

        connectReady(controller, transport)
        val firstRequest = lastClientTimeRequest(transport)
        clock.nowMs = 1_050L
        transport.receiveText(serverTime(firstRequest, 5_000L, 5_002L))
        val secondRequest = lastClientTimeRequest(transport)
        clock.nowMs = 1_150L
        transport.receiveText(serverTime(secondRequest, 5_075L, 5_077L))
        val thirdRequest = lastClientTimeRequest(transport)
        clock.nowMs = 1_250L
        transport.receiveText(serverTime(thirdRequest, 5_175L, 5_177L))

        val timing = snapshots.last().timing
        assertEquals(SendspinConnectionStatus.READY, snapshots.last().status)
        assertEquals(SendspinClockQuality.STABLE, timing.quality)
        assertEquals(3_976L, timing.offsetMs)
        assertEquals(3, timing.sampleCount)
    }

    @Test
    fun fastServerTimeResponseDoesNotSendImmediateFollowUpRequest() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val clock = MutableTestClock(1_000L)
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
            timingController = SendspinTimingController(monotonicClock = clock),
        )

        connectReady(controller, transport)
        val sentCountAfterHandshake = transport.sentTexts.size
        val request = lastClientTimeRequest(transport)
        clock.nowMs = 1_050L
        transport.receiveText(serverTime(request, 5_000L, 5_002L))

        assertEquals(sentCountAfterHandshake, transport.sentTexts.size)
        assertEquals(SendspinClockQuality.DEGRADED, snapshots.last().timing.quality)
    }

    @Test
    fun unknownServerTimeRequestFailsSessionVisibly() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
            timingController = SendspinTimingController(monotonicClock = SendspinMonotonicClock { 1_050L }),
        )

        connectReady(controller, transport)
        transport.receiveText(
            JSONObject()
                .put("type", "server/time")
                .put("requestId", "unknown-time-request")
                .put("clientSentAtMs", 1_000L)
                .put("serverReceivedAtMs", 5_000L)
                .put("serverSentAtMs", 5_002L)
                .toString(),
        )

        assertProtocolFailure(snapshots)
        assertTrue(snapshots.last().error?.message.orEmpty().contains("active client time request"))
    }

    @Test
    fun invalidServerTimeOrderingFailsSessionVisibly() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val clock = MutableTestClock(1_000L)
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
            timingController = SendspinTimingController(monotonicClock = clock),
        )

        connectReady(controller, transport)
        val request = lastClientTimeRequest(transport)
        clock.nowMs = 1_050L
        transport.receiveText(serverTime(request, serverReceivedAtMs = 5_010L, serverSentAtMs = 5_002L))

        assertProtocolFailure(snapshots)
        assertTrue(snapshots.last().error?.message.orEmpty().contains("server send time"))
    }

    @Test
    fun rawNativeClientCommandSendsOnlyWhenReady() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
            timingController = SendspinTimingController(monotonicClock = SendspinMonotonicClock { 1_000L }),
        )
        var sendError: SendspinConnectionException? = SendspinConnectionException("unset", "unset")

        connectReady(controller, transport)
        controller.sendRawClientCommand(
            SendspinClientCommand(
                command = SendspinClientCommandKind.SEEK_TO,
                requestId = "request-1",
                positionMs = 42000L,
            ),
        ) { sendError = it }

        val command = JSONObject(transport.sentTexts.last())
        assertEquals(null, sendError)
        assertEquals(SendspinConnectionStatus.READY, snapshots.last().status)
        assertEquals("client/command", command.getString("type"))
        assertEquals("seekTo", command.getString("command"))
        assertEquals("request-1", command.getString("requestId"))
        assertEquals(42000L, command.getLong("positionMs"))
    }

    @Test
    fun rawNativeClientCommandFailsVisiblyBeforeReady() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
            timingController = SendspinTimingController(monotonicClock = SendspinMonotonicClock { 1_000L }),
        )
        var sendError: SendspinConnectionException? = null

        controller.sendRawClientCommand(SendspinClientCommand(SendspinClientCommandKind.PAUSE)) {
            sendError = it
        }

        assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_NOT_CONNECTED, sendError?.code)
        assertEquals(SendspinConnectionStatus.FAILED, snapshots.last().status)
        assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_NOT_CONNECTED, snapshots.last().error?.code)
        assertEquals(true, transport.sentTexts.isEmpty())
    }

    @Test
    fun rawNativeClientCommandFailsVisiblyDuringActiveHandshakeBeforeReady() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
            timingController = SendspinTimingController(monotonicClock = SendspinMonotonicClock { 1_000L }),
        )
        var sendError: SendspinConnectionException? = null

        controller.connect(URI("ws://music.example.local/sendspin"))
        transport.opened()
        controller.sendRawClientCommand(SendspinClientCommand(SendspinClientCommandKind.PAUSE)) {
            sendError = it
        }

        assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_NOT_CONNECTED, sendError?.code)
        assertEquals(SendspinConnectionStatus.FAILED, snapshots.last().status)
        assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_NOT_CONNECTED, snapshots.last().error?.code)
        assertEquals(false, transport.sentTexts.any { JSONObject(it).getString("type") == "client/command" })
    }

    @Test
    fun rawNativeClientCommandSendFailureFailsSession() {
        val transport = FakeSendspinTransport(throwOnSendContaining = "client/command")
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
            timingController = SendspinTimingController(monotonicClock = SendspinMonotonicClock { 1_000L }),
        )
        var sendError: SendspinConnectionException? = null

        connectReady(controller, transport)
        controller.sendRawClientCommand(SendspinClientCommand(SendspinClientCommandKind.PAUSE)) {
            sendError = it
        }

        assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_TRANSPORT_ERROR, sendError?.code)
        assertEquals(SendspinConnectionStatus.FAILED, snapshots.last().status)
        assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_TRANSPORT_ERROR, snapshots.last().error?.code)
        assertEquals(true, transport.closed)
    }

    private fun readySnapshotsBefore(text: String): List<SendspinConnectionSnapshot> {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
            timingController = SendspinTimingController(monotonicClock = SendspinMonotonicClock { 1_000L }),
        )
        connectReady(controller, transport)
        transport.receiveText(text)
        return snapshots
    }

    private fun connectReady(controller: SendspinConnectionController, transport: FakeSendspinTransport) {
        controller.connect(URI("ws://music.example.local/sendspin"))
        transport.opened()
        transport.receiveText(serverHello())
    }

    private fun serverHello(): String = JSONObject()
        .put("type", "server/hello")
        .put("protocolVersion", 1)
        .put("activatedRoles", listOf("audio", "controller", "state", "time"))
        .toString()

    private fun serverTime(
        request: TimeRequest,
        serverReceivedAtMs: Long,
        serverSentAtMs: Long,
    ): String = JSONObject()
        .put("type", "server/time")
        .put("requestId", request.requestId)
        .put("clientSentAtMs", request.clientSentAtMs)
        .put("serverReceivedAtMs", serverReceivedAtMs)
        .put("serverSentAtMs", serverSentAtMs)
        .toString()

    private fun streamStart(streamId: String): String = JSONObject()
        .put("type", "stream/start")
        .put("streamId", streamId)
        .put("codec", "pcm")
        .put("sampleRateHz", 48_000)
        .put("channels", 2)
        .toString()

    private fun streamClear(streamId: String): String = JSONObject()
        .put("type", "stream/clear")
        .put("streamId", streamId)
        .toString()

    private fun streamEnd(streamId: String): String = JSONObject()
        .put("type", "stream/end")
        .put("streamId", streamId)
        .toString()

    private fun binaryFrame(streamId: String, timestampMs: Long, sequence: Long): ByteArray =
        SendspinBinaryFrameParser.encode(
            SendspinAudioFrame(
                streamId = streamId,
                timestampMs = timestampMs,
                sequence = sequence,
                payload = byteArrayOf(0x01),
            ),
        )

    private fun lastClientTimeRequest(transport: FakeSendspinTransport): TimeRequest {
        val json = JSONObject(transport.sentTexts.last())
        assertEquals("client/time", json.getString("type"))
        return TimeRequest(
            requestId = json.getString("requestId"),
            clientSentAtMs = json.getLong("clientSentAtMs"),
        )
    }

    private fun assertProtocolFailure(snapshots: List<SendspinConnectionSnapshot>) {
        assertEquals(SendspinConnectionStatus.FAILED, snapshots.last().status)
        assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR, snapshots.last().error?.code)
    }
}

private class CapturingProtocolEvents : SendspinProtocolEvents {
    val metadata = mutableListOf<SendspinMetadata>()
    val times = mutableListOf<SendspinServerTime>()
    val protocolErrors = mutableListOf<SendspinServerProtocolError>()

    override fun onServerState(state: SendspinServerState) = Unit

    override fun onMetadata(metadata: SendspinMetadata) {
        this.metadata.add(metadata)
    }

    override fun onServerTime(time: SendspinServerTime) {
        times.add(time)
    }

    override fun onStreamStart(stream: SendspinStreamStart) = Unit

    override fun onStreamClear(stream: SendspinStreamClear) = Unit

    override fun onStreamEnd(stream: SendspinStreamEnd) = Unit

    override fun onServerCommand(command: SendspinServerCommand) = Unit

    override fun onServerStatus(status: SendspinServerStatus) = Unit

    override fun onServerProtocolError(error: SendspinServerProtocolError) {
        protocolErrors.add(error)
    }
}

private class CapturingProtocolLogger : SendspinProtocolLogger {
    private val warnings = mutableListOf<Map<String, Any?>?>()

    override fun warn(message: String, details: Map<String, Any?>?) {
        warnings.add(details)
    }

    fun unknownTypes(): List<String> = warnings.mapNotNull { it?.get("type") as? String }
}

private class MutableTestClock(
    var nowMs: Long,
) : SendspinMonotonicClock {
    override fun nowMs(): Long = nowMs
}

private data class TimeRequest(
    val requestId: String,
    val clientSentAtMs: Long,
)
