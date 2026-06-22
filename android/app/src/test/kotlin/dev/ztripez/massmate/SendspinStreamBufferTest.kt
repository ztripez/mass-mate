package dev.ztripez.massmate

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendspinStreamBufferTest {
    @Test
    fun binaryFramesBeforeStreamStartAreIgnoredSafely() {
        val buffer = SendspinStreamBuffer()

        val snapshot = buffer.receiveBinary(frameBytes(streamId = "stream-1", timestampMs = 1_000L, sequence = 1L))

        assertFalse(snapshot.active)
        assertEquals(0, snapshot.frameCount)
        assertEquals(1L, snapshot.droppedFrameCount)
        assertEquals("no-active-stream", snapshot.lastDropReason)
    }

    @Test
    fun framesAreBufferedInTimestampThenSequenceOrder() {
        val buffer = SendspinStreamBuffer()

        buffer.start(streamStart("stream-1"))
        buffer.receiveBinary(frameBytes(streamId = "stream-1", timestampMs = 2_000L, sequence = 2L, payload = byteArrayOf(2)))
        buffer.receiveBinary(frameBytes(streamId = "stream-1", timestampMs = 1_000L, sequence = 1L, payload = byteArrayOf(1)))
        buffer.receiveBinary(frameBytes(streamId = "stream-1", timestampMs = 2_000L, sequence = 3L, payload = byteArrayOf(3)))

        val frames = buffer.bufferedFrames()
        assertEquals(listOf(1_000L, 2_000L, 2_000L), frames.map { it.timestampMs })
        assertEquals(listOf(1L, 2L, 3L), frames.map { it.sequence })
        assertArrayEquals(byteArrayOf(1), frames[0].payload)
        assertEquals(1_000L, buffer.snapshot().bufferDepthMs)
    }

    @Test
    fun duplicateWrongStreamAndFullBufferFramesAreDropped() {
        val buffer = SendspinStreamBuffer(SendspinStreamBufferConfig(maxBufferedFrames = 1))

        buffer.start(streamStart("stream-1"))
        buffer.receiveBinary(frameBytes(streamId = "stream-1", timestampMs = 1_000L, sequence = 1L))
        val duplicate = buffer.receiveBinary(frameBytes(streamId = "stream-1", timestampMs = 1_100L, sequence = 1L))
        val wrongStream = buffer.receiveBinary(frameBytes(streamId = "stream-2", timestampMs = 1_200L, sequence = 2L))
        val full = buffer.receiveBinary(frameBytes(streamId = "stream-1", timestampMs = 1_300L, sequence = 3L))

        assertEquals("duplicate-sequence", duplicate.lastDropReason)
        assertEquals("stream-mismatch", wrongStream.lastDropReason)
        assertEquals("buffer-full", full.lastDropReason)
        assertEquals(3L, full.droppedFrameCount)
        assertEquals(1, full.frameCount)
    }

    @Test
    fun missingSequenceCounterIsTrackedWithoutReorderingFailure() {
        val buffer = SendspinStreamBuffer()

        buffer.start(streamStart("stream-1"))
        buffer.receiveBinary(frameBytes(streamId = "stream-1", timestampMs = 1_000L, sequence = 1L))
        val snapshot = buffer.receiveBinary(frameBytes(streamId = "stream-1", timestampMs = 1_100L, sequence = 4L))

        assertEquals(2L, snapshot.missingFrameCount)
        assertEquals(2, snapshot.frameCount)
    }

    @Test
    fun streamClearClearsQueuedFramesWithoutEndingActiveStream() {
        val buffer = SendspinStreamBuffer()

        buffer.start(streamStart("stream-1"))
        buffer.receiveBinary(frameBytes(streamId = "stream-1", timestampMs = 1_000L, sequence = 1L))
        val snapshot = buffer.clear(SendspinStreamClear("stream-1"))

        assertTrue(snapshot.active)
        assertEquals("stream-1", snapshot.streamId)
        assertEquals(0, snapshot.frameCount)
    }

    @Test
    fun streamEndStopsActiveStreamAndClearsOwnedState() {
        val buffer = SendspinStreamBuffer()

        buffer.start(streamStart("stream-1"))
        buffer.receiveBinary(frameBytes(streamId = "stream-1", timestampMs = 1_000L, sequence = 1L))
        val snapshot = buffer.end(SendspinStreamEnd("stream-1"))

        assertFalse(snapshot.active)
        assertEquals(null, snapshot.streamId)
        assertEquals(0, snapshot.frameCount)
    }

    @Test
    fun malformedBinaryFrameFailsLoudlyWhenStreamIsActive() {
        val buffer = SendspinStreamBuffer()

        buffer.start(streamStart("stream-1"))

        assertProtocolError {
            buffer.receiveBinary(byteArrayOf(0x01, 0x02))
        }
    }

    private fun streamStart(streamId: String): SendspinStreamStart = SendspinStreamStart(
        streamId = streamId,
        codec = SendspinStreamCodec.PCM,
        sampleRateHz = 48_000,
        channels = 2,
    )

    private fun frameBytes(
        streamId: String,
        timestampMs: Long,
        sequence: Long,
        payload: ByteArray = byteArrayOf(0x01),
    ): ByteArray = SendspinBinaryFrameParser.encode(
        SendspinAudioFrame(
            streamId = streamId,
            timestampMs = timestampMs,
            sequence = sequence,
            payload = payload,
        ),
    )

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
