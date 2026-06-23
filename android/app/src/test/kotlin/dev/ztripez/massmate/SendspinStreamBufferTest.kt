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
        val expectedFrame = buffer.bufferedFrames().single()
        val expectedSnapshot = buffer.snapshot()
        val duplicate = buffer.receiveBinary(frameBytes(streamId = "stream-1", timestampMs = 1_100L, sequence = 1L))
        assertBufferUnchanged(buffer, expectedFrame, expectedSnapshot, droppedFrameCount = 1L)
        val wrongStream = buffer.receiveBinary(frameBytes(streamId = "stream-2", timestampMs = 1_200L, sequence = 2L))
        assertBufferUnchanged(buffer, expectedFrame, expectedSnapshot, droppedFrameCount = 2L)
        val full = buffer.receiveBinary(frameBytes(streamId = "stream-1", timestampMs = 1_300L, sequence = 3L))
        assertBufferUnchanged(buffer, expectedFrame, expectedSnapshot, droppedFrameCount = 3L)

        assertEquals("duplicate-sequence", duplicate.lastDropReason)
        assertEquals("stream-mismatch", wrongStream.lastDropReason)
        assertEquals("buffer-full", full.lastDropReason)
        assertEquals(3L, full.droppedFrameCount)
        assertEquals(1, full.frameCount)
    }

    @Test
    fun parserReadsIndependentBigEndianWireFixture() {
        val bytes = byteArrayOf(
            0x53, 0x53, 0x41, 0x46,
            0x01,
            0x08,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0xe8.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02,
            0x00, 0x00, 0x00, 0x03,
            0x73, 0x74, 0x72, 0x65, 0x61, 0x6d, 0x2d, 0x31,
            0x0a, 0x0b, 0x0c,
        )

        val frame = SendspinBinaryFrameParser.parse(bytes)

        assertEquals("stream-1", frame.streamId)
        assertEquals(1_000L, frame.timestampMs)
        assertEquals(2L, frame.sequence)
        assertArrayEquals(byteArrayOf(0x0a, 0x0b, 0x0c), frame.payload)
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
    fun mismatchedStreamClearAndEndFailLoudly() {
        val buffer = SendspinStreamBuffer()

        buffer.start(streamStart("stream-1"))

        assertProtocolError {
            buffer.clear(SendspinStreamClear("stream-2"))
        }
        assertProtocolError {
            buffer.end(SendspinStreamEnd("stream-2"))
        }
    }

    @Test
    fun streamEndWithoutActiveStreamFailsLoudly() {
        val buffer = SendspinStreamBuffer()

        assertProtocolError {
            buffer.end(SendspinStreamEnd("stream-1"))
        }
    }

    @Test
    fun malformedBinaryFrameFailsLoudlyWhenStreamIsActive() {
        val buffer = SendspinStreamBuffer()

        buffer.start(streamStart("stream-1"))

        assertProtocolError {
            buffer.receiveBinary(byteArrayOf(0x01, 0x02))
        }
    }

    @Test
    fun malformedUtf8StreamIdFailsLoudlyWhenStreamIsActive() {
        val buffer = SendspinStreamBuffer()

        buffer.start(streamStart("stream-1"))

        assertProtocolError {
            buffer.receiveBinary(frameWithMalformedUtf8StreamId())
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
    ): ByteArray = sendspinBinaryFrameBytes(streamId, timestampMs, sequence, payload)

    private fun frameWithMalformedUtf8StreamId(): ByteArray = byteArrayOf(
        0x53, 0x53, 0x41, 0x46,
        0x01,
        0x01,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0xe8.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01,
        0x00, 0x00, 0x00, 0x01,
        0x80.toByte(),
        0x01,
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

    private fun assertBufferUnchanged(
        buffer: SendspinStreamBuffer,
        expectedFrame: SendspinAudioFrame,
        expectedSnapshot: SendspinStreamBufferSnapshot,
        droppedFrameCount: Long,
    ) {
        val frames = buffer.bufferedFrames()
        val snapshot = buffer.snapshot()
        assertEquals(1, frames.size)
        assertEquals(expectedFrame.streamId, frames.single().streamId)
        assertEquals(expectedFrame.timestampMs, frames.single().timestampMs)
        assertEquals(expectedFrame.sequence, frames.single().sequence)
        assertArrayEquals(expectedFrame.payload, frames.single().payload)
        assertEquals(expectedSnapshot.active, snapshot.active)
        assertEquals(expectedSnapshot.streamId, snapshot.streamId)
        assertEquals(expectedSnapshot.codec, snapshot.codec)
        assertEquals(expectedSnapshot.frameCount, snapshot.frameCount)
        assertEquals(expectedSnapshot.bufferDepthMs, snapshot.bufferDepthMs)
        assertEquals(expectedSnapshot.missingFrameCount, snapshot.missingFrameCount)
        assertEquals(droppedFrameCount, snapshot.droppedFrameCount)
    }
}
