package dev.ztripez.massmate

import java.net.URI
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendspinConnectionStreamBufferingTest {
    @Test
    fun streamLifecycleAndBinaryFramesUpdateCoalescedSnapshots() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = streamController(transport, snapshots, streamSnapshotFrameInterval = 2)

        connectReady(controller, transport)
        transport.receiveText(streamStart("stream-1"))
        val snapshotsAfterStart = snapshots.size
        transport.receiveBinary(binaryFrame("stream-1", timestampMs = 2_000L, sequence = 2L))

        assertEquals(snapshotsAfterStart, snapshots.size)

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
    fun binaryDropDiagnosticsAreCoalescedBeforeStreamStart() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = streamController(transport, snapshots, streamSnapshotFrameInterval = 2)

        connectReady(controller, transport)
        val snapshotsAfterReady = snapshots.size
        transport.receiveBinary(binaryFrame("stream-1", timestampMs = 1_000L, sequence = 1L))

        assertEquals(snapshotsAfterReady, snapshots.size)

        transport.receiveBinary(binaryFrame("stream-1", timestampMs = 1_100L, sequence = 2L))

        assertEquals(SendspinConnectionStatus.READY, snapshots.last().status)
        assertEquals(2L, snapshots.last().stream.droppedFrameCount)
        assertEquals("no-active-stream", snapshots.last().stream.lastDropReason)
    }

    @Test
    fun malformedBinaryFrameFailsVisiblyWhenStreamIsActive() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = streamController(transport, snapshots)

        connectReady(controller, transport)
        transport.receiveText(streamStart("stream-1"))
        transport.receiveBinary(byteArrayOf(0x01, 0x02))

        assertProtocolFailure(snapshots)
        assertTrue(snapshots.last().error?.message.orEmpty().contains("binary frame"))
    }

    @Test
    fun mismatchedStreamLifecycleMessagesFailControllerSession() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = streamController(transport, snapshots)

        connectReady(controller, transport)
        transport.receiveText(streamStart("stream-1"))
        transport.receiveText(streamClear("stream-2"))

        assertProtocolFailure(snapshots)
        assertTrue(snapshots.last().error?.message.orEmpty().contains("active stream"))
    }

    @Test
    fun bridgeSnapshotContainsExactStreamDiagnosticsMap() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = streamController(transport, snapshots, streamSnapshotFrameInterval = 1)

        connectReady(controller, transport)
        transport.receiveText(streamStart("stream-1"))
        transport.receiveBinary(binaryFrame("stream-1", timestampMs = 1_000L, sequence = 1L))

        val bridge = LocalPlayerEnvelope.snapshot(snapshots.last())
        @Suppress("UNCHECKED_CAST")
        val stream = bridge.getValue("stream") as Map<String, Any?>

        assertEquals(
            setOf(
                "active",
                "streamId",
                "codec",
                "frameCount",
                "bufferDepthMs",
                "droppedFrameCount",
                "missingFrameCount",
                "lastDropReason",
            ),
            stream.keys,
        )
        assertEquals(true, stream["active"])
        assertEquals("stream-1", stream["streamId"])
        assertEquals("pcm", stream["codec"])
        assertEquals(1, stream["frameCount"])
        assertEquals(0L, stream["bufferDepthMs"])
        assertEquals(0L, stream["droppedFrameCount"])
        assertEquals(0L, stream["missingFrameCount"])
        assertEquals(null, stream["lastDropReason"])
    }

    private fun streamController(
        transport: FakeSendspinTransport,
        snapshots: MutableList<SendspinConnectionSnapshot>,
        streamSnapshotFrameInterval: Int = 64,
    ): SendspinConnectionController = SendspinConnectionController(
        transportFactory = SendspinTransportFactory { transport },
        onSnapshot = snapshots::add,
        timingController = SendspinTimingController(monotonicClock = SendspinMonotonicClock { 1_000L }),
        streamSnapshotFrameInterval = streamSnapshotFrameInterval,
    )

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
        sendspinBinaryFrameBytes(streamId, timestampMs, sequence)

    private fun assertProtocolFailure(snapshots: List<SendspinConnectionSnapshot>) {
        assertEquals(SendspinConnectionStatus.FAILED, snapshots.last().status)
        assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR, snapshots.last().error?.code)
    }
}
