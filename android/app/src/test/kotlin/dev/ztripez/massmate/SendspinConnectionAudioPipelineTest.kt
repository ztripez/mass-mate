package dev.ztripez.massmate

import java.net.URI
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SendspinConnectionAudioPipelineTest {
    @Test
    fun stablePcmStreamWritesAcceptedFramesToAudioSink() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val clock = MutableControllerAudioClock(1_000L)
        val audioSinkFactory = FakeSendspinAudioSinkFactory()
        val controller = audioController(transport, snapshots, clock, audioSinkFactory)

        connectReady(controller, transport)
        stabilizeClock(transport, clock)
        transport.receiveText(streamStart("stream-1"))
        clock.nowMs = 1_260L
        transport.receiveBinary(
            binaryFrame("stream-1", timestampMs = 5_260L, sequence = 1L, payload = byteArrayOf(0x10, 0x11, 0x12, 0x13)),
        )

        assertArrayEquals(byteArrayOf(0x10, 0x11, 0x12, 0x13), audioSinkFactory.sinks.single().writes.single())
        assertEquals(1L, snapshots.last().audio.writtenFrameCount)
        assertEquals(4L, snapshots.last().audio.writtenByteCount)
        assertEquals(0, snapshots.last().stream.frameCount)
    }

    @Test
    fun audioWriteFailureFailsControllerSessionVisibly() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val clock = MutableControllerAudioClock(1_000L)
        val audioSinkFactory = FakeSendspinAudioSinkFactory()
        audioSinkFactory.nextSink = FakeSendspinAudioSink(acceptedByteCount = 1)
        val controller = audioController(transport, snapshots, clock, audioSinkFactory)

        connectReady(controller, transport)
        stabilizeClock(transport, clock)
        transport.receiveText(streamStart("stream-1"))
        clock.nowMs = 1_260L
        transport.receiveBinary(
            binaryFrame("stream-1", timestampMs = 5_260L, sequence = 1L, payload = byteArrayOf(0x10, 0x11, 0x12, 0x13)),
        )

        assertEquals(SendspinConnectionStatus.FAILED, snapshots.last().status)
        assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_AUDIO_ERROR, snapshots.last().error?.code)
        assertEquals(1L, snapshots.last().audio.writeFailureCount)
        assertEquals(true, transport.closed)
    }

    @Test
    fun queuedFrameBeforeStableClockDrainsAfterServerTimeStabilizes() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val clock = MutableControllerAudioClock(1_000L)
        val audioSinkFactory = FakeSendspinAudioSinkFactory()
        val controller = audioController(transport, snapshots, clock, audioSinkFactory)

        connectReady(controller, transport)
        transport.receiveText(streamStart("stream-1"))
        transport.receiveBinary(
            binaryFrame("stream-1", timestampMs = 5_260L, sequence = 1L, payload = byteArrayOf(0x10, 0x11, 0x12, 0x13)),
        )

        assertEquals(0, audioSinkFactory.sinks.single().writes.size)
        assertEquals(1, snapshots.last().audio.queuedFrameCount)
        assertEquals("waiting-for-stable-clock", snapshots.last().audio.lastIssue)

        stabilizeClock(transport, clock)

        assertArrayEquals(byteArrayOf(0x10, 0x11, 0x12, 0x13), audioSinkFactory.sinks.single().writes.single())
        assertEquals(0, snapshots.last().audio.queuedFrameCount)
    }

    @Test
    fun streamDiagnosticBufferCapacityDoesNotStopForwardedAudio() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val clock = MutableControllerAudioClock(1_000L)
        val audioSinkFactory = FakeSendspinAudioSinkFactory()
        val controller = audioController(
            transport,
            snapshots,
            clock,
            audioSinkFactory,
            streamBuffer = SendspinStreamBuffer(SendspinStreamBufferConfig(maxBufferedFrames = 1)),
        )

        connectReady(controller, transport)
        stabilizeClock(transport, clock)
        transport.receiveText(streamStart("stream-1"))
        clock.nowMs = 1_260L
        transport.receiveBinary(
            binaryFrame("stream-1", timestampMs = 5_260L, sequence = 1L, payload = byteArrayOf(0x10, 0x11, 0x12, 0x13)),
        )
        transport.receiveBinary(
            binaryFrame("stream-1", timestampMs = 5_261L, sequence = 2L, payload = byteArrayOf(0x20, 0x21, 0x22, 0x23)),
        )

        assertEquals(2, audioSinkFactory.sinks.single().writes.size)
        assertEquals(2L, snapshots.last().audio.writtenFrameCount)
        assertEquals(0, snapshots.last().stream.frameCount)
    }

    @Test
    fun clearAndEndFlushStopAudioSinkThroughController() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val clock = MutableControllerAudioClock(1_000L)
        val audioSinkFactory = FakeSendspinAudioSinkFactory()
        val controller = audioController(transport, snapshots, clock, audioSinkFactory)

        connectReady(controller, transport)
        stabilizeClock(transport, clock)
        transport.receiveText(streamStart("stream-1"))
        transport.receiveText(streamClear("stream-1"))
        transport.receiveText(streamEnd("stream-1"))

        val sink = audioSinkFactory.sinks.single()
        assertEquals(1, sink.flushCount)
        assertEquals(1, sink.stopCount)
        assertEquals(1, sink.releaseCount)
    }

    @Test
    fun bridgeSnapshotContainsExactAudioDiagnosticsMap() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val clock = MutableControllerAudioClock(1_000L)
        val audioSinkFactory = FakeSendspinAudioSinkFactory()
        val controller = audioController(transport, snapshots, clock, audioSinkFactory)

        connectReady(controller, transport)
        stabilizeClock(transport, clock)
        transport.receiveText(streamStart("stream-1"))

        val bridge = LocalPlayerEnvelope.snapshot(snapshots.last())
        @Suppress("UNCHECKED_CAST")
        val audio = bridge.getValue("audio") as Map<String, Any?>

        assertEquals(
            setOf(
                "active",
                "streamId",
                "codec",
                "sampleRateHz",
                "channels",
                "queuedFrameCount",
                "writtenFrameCount",
                "writtenByteCount",
                "lateFrameCount",
                "underrunCount",
                "writeFailureCount",
                "lastIssue",
            ),
            audio.keys,
        )
        assertEquals(true, audio["active"])
        assertEquals("stream-1", audio["streamId"])
        assertEquals("pcm", audio["codec"])
        assertEquals(48_000, audio["sampleRateHz"])
        assertEquals(2, audio["channels"])
        assertEquals(0, audio["queuedFrameCount"])
        assertEquals(0L, audio["writtenFrameCount"])
        assertEquals(0L, audio["writtenByteCount"])
        assertEquals(0L, audio["lateFrameCount"])
        assertEquals(0L, audio["underrunCount"])
        assertEquals(0L, audio["writeFailureCount"])
        assertEquals(null, audio["lastIssue"])
    }

    private fun audioController(
        transport: FakeSendspinTransport,
        snapshots: MutableList<SendspinConnectionSnapshot>,
        clock: MutableControllerAudioClock,
        audioSinkFactory: FakeSendspinAudioSinkFactory,
        streamBuffer: SendspinStreamBuffer = SendspinStreamBuffer(),
    ): SendspinConnectionController = SendspinConnectionController(
        transportFactory = SendspinTransportFactory { transport },
        onSnapshot = snapshots::add,
        timingController = SendspinTimingController(
            monotonicClock = clock,
            minimumRequestIntervalMs = 0L,
        ),
        audioPipeline = SendspinAudioPipeline(
            sinkFactory = audioSinkFactory,
            monotonicClock = clock,
            config = SendspinAudioPipelineConfig(maxWriteAheadMs = 100L, lateFrameToleranceMs = 50L),
        ),
        streamBuffer = streamBuffer,
        streamSnapshotFrameInterval = 1,
    )

    private fun connectReady(controller: SendspinConnectionController, transport: FakeSendspinTransport) {
        controller.connect(URI("ws://music.example.local/sendspin"))
        transport.opened()
        transport.receiveText(serverHello())
    }

    private fun stabilizeClock(transport: FakeSendspinTransport, clock: MutableControllerAudioClock) {
        val first = lastClientTimeRequest(transport)
        clock.nowMs = 1_050L
        transport.receiveText(serverTime(first, 5_000L, 5_002L))
        val second = lastClientTimeRequest(transport)
        clock.nowMs = 1_150L
        transport.receiveText(serverTime(second, 5_075L, 5_077L))
        val third = lastClientTimeRequest(transport)
        clock.nowMs = 1_250L
        transport.receiveText(serverTime(third, 5_175L, 5_177L))
    }

    private fun serverHello(): String = JSONObject()
        .put("type", "server/hello")
        .put("protocolVersion", 1)
        .put("activatedRoles", listOf("audio", "controller", "state", "time"))
        .toString()

    private fun serverTime(
        request: AudioTimeRequest,
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

    private fun binaryFrame(
        streamId: String,
        timestampMs: Long,
        sequence: Long,
        payload: ByteArray,
    ): ByteArray = sendspinBinaryFrameBytes(streamId, timestampMs, sequence, payload)

    private fun lastClientTimeRequest(transport: FakeSendspinTransport): AudioTimeRequest {
        val json = JSONObject(transport.sentTexts.last())
        assertEquals("client/time", json.getString("type"))
        return AudioTimeRequest(json.getString("requestId"), json.getLong("clientSentAtMs"))
    }
}

private class MutableControllerAudioClock(
    var nowMs: Long,
) : SendspinMonotonicClock {
    override fun nowMs(): Long = nowMs
}

private data class AudioTimeRequest(
    val requestId: String,
    val clientSentAtMs: Long,
)
