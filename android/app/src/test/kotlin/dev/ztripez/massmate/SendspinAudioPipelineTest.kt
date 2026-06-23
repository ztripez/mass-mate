package dev.ztripez.massmate

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SendspinAudioPipelineTest {
    private val clock = MutableAudioTestClock(1_000L)
    private val sinkFactory = FakeSendspinAudioSinkFactory()
    private val pipeline = SendspinAudioPipeline(
        sinkFactory = sinkFactory,
        monotonicClock = clock,
        config = SendspinAudioPipelineConfig(maxWriteAheadMs = 100L, lateFrameToleranceMs = 50L),
    )

    @Test
    fun pcmFramesWriteToSinkWhenStableMappingIsDue() {
        pipeline.start(streamStart())

        val snapshot = pipeline.receiveFrame(
            audioFrame(timestampMs = 5_000L, sequence = 1L, payload = byteArrayOf(1, 2, 3, 4)),
            mapping(offsetMs = 4_000L),
        )

        val sink = sinkFactory.sinks.single()
        assertEquals(SendspinPcmAudioFormat(48_000, 2), sink.format)
        assertEquals(1, sink.startCount)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), sink.writes.single())
        assertEquals(1L, snapshot.writtenFrameCount)
        assertEquals(4L, snapshot.writtenByteCount)
        assertEquals(0, snapshot.queuedFrameCount)
    }

    @Test
    fun framesWaitForStableClockBeforeWriting() {
        pipeline.start(streamStart())

        val waiting = pipeline.receiveFrame(audioFrame(timestampMs = 5_000L, sequence = 1L), noStableMapping())

        assertEquals(0, sinkFactory.sinks.single().writes.size)
        assertEquals(1, waiting.queuedFrameCount)
        assertEquals("waiting-for-stable-clock", waiting.lastIssue)

        val written = pipeline.drain(mapping(offsetMs = 4_000L))

        assertEquals(1, sinkFactory.sinks.single().writes.size)
        assertEquals(0, written.queuedFrameCount)
        assertEquals(null, written.lastIssue)
    }

    @Test
    fun futureFramesWaitUntilWithinWriteAheadWindow() {
        pipeline.start(streamStart())

        val queued = pipeline.receiveFrame(audioFrame(timestampMs = 5_500L, sequence = 1L), mapping(offsetMs = 4_000L))
        assertEquals(1, queued.queuedFrameCount)
        assertEquals(0, sinkFactory.sinks.single().writes.size)

        clock.nowMs = 1_410L
        val written = pipeline.drain(mapping(offsetMs = 4_000L))

        assertEquals(0, written.queuedFrameCount)
        assertEquals(1, sinkFactory.sinks.single().writes.size)
    }

    @Test
    fun lateFramesAreDroppedDeterministically() {
        pipeline.start(streamStart())
        clock.nowMs = 1_200L

        val snapshot = pipeline.receiveFrame(audioFrame(timestampMs = 5_000L, sequence = 1L), mapping(offsetMs = 4_000L))

        assertEquals(0, sinkFactory.sinks.single().writes.size)
        assertEquals(0, snapshot.queuedFrameCount)
        assertEquals(1L, snapshot.lateFrameCount)
        assertEquals("late-frame", snapshot.lastIssue)
    }

    @Test
    fun clearFlushesQueuedAndSinkBufferedAudioWithoutStoppingStream() {
        pipeline.start(streamStart())
        pipeline.receiveFrame(audioFrame(timestampMs = 5_500L, sequence = 1L), mapping(offsetMs = 4_000L))

        val snapshot = pipeline.clear(SendspinStreamClear("stream-1"))

        val sink = sinkFactory.sinks.single()
        assertEquals(1, sink.flushCount)
        assertEquals(0, sink.stopCount)
        assertEquals(true, snapshot.active)
        assertEquals(0, snapshot.queuedFrameCount)
    }

    @Test
    fun endStopsAndReleasesActiveSink() {
        pipeline.start(streamStart())

        val snapshot = pipeline.end(SendspinStreamEnd("stream-1"))

        val sink = sinkFactory.sinks.single()
        assertEquals(1, sink.stopCount)
        assertEquals(1, sink.releaseCount)
        assertEquals(false, snapshot.active)
    }

    @Test
    fun partialWriteFailsLoudlyAndTracksWriteFailure() {
        sinkFactory.nextSink = FakeSendspinAudioSink(acceptedByteCount = 1)
        pipeline.start(streamStart())

        val error = assertAudioError {
            pipeline.receiveFrame(
                audioFrame(timestampMs = 5_000L, sequence = 1L, payload = byteArrayOf(1, 2, 3, 4)),
                mapping(4_000L),
            )
        }

        assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_AUDIO_ERROR, error.code)
        assertEquals(1L, pipeline.snapshot().writeFailureCount)
    }

    @Test
    fun runtimeWriteFailureFailsLoudlyAndTracksWriteFailure() {
        sinkFactory.nextSink = FakeSendspinAudioSink(writeException = IllegalStateException("write exploded"))
        pipeline.start(streamStart())

        assertAudioError {
            pipeline.receiveFrame(audioFrame(timestampMs = 5_000L, sequence = 1L), mapping(4_000L))
        }

        assertEquals(1L, pipeline.snapshot().writeFailureCount)
    }

    @Test
    fun misalignedPcmPayloadFailsBeforeSinkWrite() {
        pipeline.start(streamStart())

        assertAudioError {
            pipeline.receiveFrame(
                audioFrame(timestampMs = 5_000L, sequence = 1L, payload = byteArrayOf(1, 2)),
                mapping(4_000L),
            )
        }

        assertEquals(0, sinkFactory.sinks.single().writes.size)
    }

    @Test
    fun sinkUnderrunsAreReportedInSnapshot() {
        pipeline.start(streamStart())
        sinkFactory.sinks.single().reportedUnderruns = 3L

        assertEquals(3L, pipeline.snapshot().underrunCount)
    }

    @Test
    fun lifecycleMismatchesFailLoudly() {
        pipeline.start(streamStart())

        assertAudioError { pipeline.clear(SendspinStreamClear("stream-2")) }
        assertAudioError { pipeline.end(SendspinStreamEnd("stream-2")) }
    }

    private fun streamStart(): SendspinStreamStart = SendspinStreamStart(
        streamId = "stream-1",
        codec = SendspinStreamCodec.PCM,
        sampleRateHz = 48_000,
        channels = 2,
    )

    private fun audioFrame(
        timestampMs: Long,
        sequence: Long,
        payload: ByteArray = byteArrayOf(0x01, 0x02, 0x03, 0x04),
    ): SendspinAudioFrame = SendspinAudioFrame("stream-1", timestampMs, sequence, payload)

    private fun mapping(offsetMs: Long): (Long) -> Long? = { serverTimeMs -> serverTimeMs - offsetMs }

    private fun noStableMapping(): (Long) -> Long? = { null }

    private fun assertAudioError(block: () -> Unit): SendspinConnectionException {
        try {
            block()
        } catch (error: SendspinConnectionException) {
            assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_AUDIO_ERROR, error.code)
            return error
        }
        throw AssertionError("Expected SendspinConnectionException")
    }
}

private class MutableAudioTestClock(
    var nowMs: Long,
) : SendspinMonotonicClock {
    override fun nowMs(): Long = nowMs
}
