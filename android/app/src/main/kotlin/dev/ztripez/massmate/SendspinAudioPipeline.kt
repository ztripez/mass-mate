package dev.ztripez.massmate

import java.util.TreeMap

/** Immutable PCM format negotiated by a Sendspin `stream/start` descriptor. */
data class SendspinPcmAudioFormat(
    /** PCM sample rate in hertz. */
    val sampleRateHz: Int,
    /** PCM channel count; only mono and stereo are valid for the Android sink. */
    val channels: Int,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive." }
        require(channels == 1 || channels == 2) { "channels must be mono or stereo." }
    }

    /** Number of bytes per PCM16 audio frame. */
    val bytesPerAudioFrame: Int = channels * PCM_16_BYTES_PER_SAMPLE
}

/** Configuration for deterministic Sendspin audio scheduling and diagnostics. */
data class SendspinAudioPipelineConfig(
    /** Maximum positive local-time lead accepted for immediate AudioTrack writes. */
    val maxWriteAheadMs: Long = 120L,
    /** Maximum negative local-time lateness before a frame is dropped instead of written. */
    val lateFrameToleranceMs: Long = 80L,
    /** Positive maximum count of accepted frames queued by the audio pipeline. */
    val maxQueuedFrames: Int = 128,
) {
    init {
        require(maxWriteAheadMs >= 0L) { "maxWriteAheadMs must be non-negative." }
        require(lateFrameToleranceMs >= 0L) { "lateFrameToleranceMs must be non-negative." }
        require(maxQueuedFrames > 0) { "maxQueuedFrames must be positive." }
    }
}

/** Debug snapshot for Sendspin PCM audio output state. */
data class SendspinAudioPipelineSnapshot(
    /** Whether an audio sink is currently open for an active stream. */
    val active: Boolean,
    /** Active stream identifier, or `null` when no stream is active. */
    val streamId: String? = null,
    /** Active codec wire value, or `null` when no stream is active. */
    val codec: String? = null,
    /** Active sample rate in hertz, or `null` when no stream is active. */
    val sampleRateHz: Int? = null,
    /** Active channel count, or `null` when no stream is active. */
    val channels: Int? = null,
    /** Count of accepted frames waiting for stable timing or due write time. */
    val queuedFrameCount: Int = 0,
    /** Count of frames fully accepted by the audio sink. */
    val writtenFrameCount: Long = 0L,
    /** Count of PCM bytes fully accepted by the audio sink. */
    val writtenByteCount: Long = 0L,
    /** Count of frames dropped because they were too late for deterministic playback. */
    val lateFrameCount: Long = 0L,
    /** Count of sink-reported underruns. */
    val underrunCount: Long = 0L,
    /** Count of sink write failures that moved the session into an explicit error state. */
    val writeFailureCount: Long = 0L,
    /** Latest audio diagnostic reason, or `null` when no issue is active. */
    val lastIssue: String? = null,
) {
    /** Converts this audio snapshot into a platform-channel debug map. */
    fun toBridgeMap(): Map<String, Any?> = mapOf(
        "active" to active,
        "streamId" to streamId,
        "codec" to codec,
        "sampleRateHz" to sampleRateHz,
        "channels" to channels,
        "queuedFrameCount" to queuedFrameCount,
        "writtenFrameCount" to writtenFrameCount,
        "writtenByteCount" to writtenByteCount,
        "lateFrameCount" to lateFrameCount,
        "underrunCount" to underrunCount,
        "writeFailureCount" to writeFailureCount,
        "lastIssue" to lastIssue,
    )

    companion object {
        /** Creates the initial inactive audio snapshot. */
        fun inactive(): SendspinAudioPipelineSnapshot = SendspinAudioPipelineSnapshot(active = false)
    }
}

/** Creates one sink for each Sendspin PCM stream. */
fun interface SendspinAudioSinkFactory {
    /** Creates an unopened sink for [format]. */
    fun create(format: SendspinPcmAudioFormat): SendspinAudioSink
}

/** Fakeable sink boundary around Android stream-mode audio output. */
interface SendspinAudioSink {
    /** Starts accepting PCM writes for the current stream. */
    fun start()

    /** Writes [bytes] and returns the number of bytes accepted by the sink. */
    fun write(bytes: ByteArray): Int

    /** Flushes pending sink data so cleared or stopped streams do not leave stale audio. */
    fun flush()

    /** Stops playback for the current stream. */
    fun stop()

    /** Releases native audio resources. */
    fun release()

    /** Returns sink-reported underrun count when available. */
    fun underrunCount(): Long = 0L
}

/**
 * Queues scheduled PCM frames and writes due frames to a fakeable audio sink.
 *
 * @param sinkFactory Factory creating a fresh sink for each active Sendspin PCM stream.
 * @param monotonicClock Local monotonic clock used for due/late frame scheduling.
 * @param config Scheduling thresholds for write-ahead, late-frame handling, and queue capacity.
 */
class SendspinAudioPipeline(
    private val sinkFactory: SendspinAudioSinkFactory = AndroidPcmAudioSinkFactory(),
    private val monotonicClock: SendspinMonotonicClock = AndroidSendspinMonotonicClock,
    private val config: SendspinAudioPipelineConfig = SendspinAudioPipelineConfig(),
) {
    private var activeStream: SendspinStreamStart? = null
    private var activeFormat: SendspinPcmAudioFormat? = null
    private var activeSink: SendspinAudioSink? = null
    private val queuedFrames = TreeMap<AudioFrameKey, SendspinAudioFrame>()
    private var writtenFrameCount = 0L
    private var writtenByteCount = 0L
    private var lateFrameCount = 0L
    private var writeFailureCount = 0L
    private var lastIssue: String? = null

    /**
     * Starts a PCM audio sink for [stream] and clears any previous stream-owned audio state.
     *
     * @return Updated audio diagnostics after the sink starts.
     * @throws SendspinConnectionException when [stream] is not PCM, the previous sink cannot be
     * stopped/released, or the new sink cannot be created or started.
     */
    fun start(stream: SendspinStreamStart): SendspinAudioPipelineSnapshot {
        if (stream.codec != SendspinStreamCodec.PCM) {
            throw audioError(
                "Sendspin audio pipeline only supports negotiated PCM streams in this slice.",
                mapOf("codec" to stream.codec.wireValue),
            )
        }
        stopAndReleaseActiveSink()
        queuedFrames.clear()
        activeStream = stream
        activeFormat = SendspinPcmAudioFormat(stream.sampleRateHz, stream.channels)
        activeSink = startSink(activeFormat ?: throw audioError("Sendspin PCM format was not initialized."))
        lastIssue = null
        return snapshot()
    }

    /**
     * Queues [frame] for the active stream and writes due frames when timing allows.
     *
     * @return Updated audio diagnostics after queuing and any due writes.
     * @throws SendspinConnectionException when no matching active stream exists, PCM bytes are not
     * frame-aligned, the audio queue is full, timing arithmetic overflows, or the sink write fails.
     */
    fun receiveFrame(
        frame: SendspinAudioFrame,
        serverToLocalTimeMs: (Long) -> Long?,
    ): SendspinAudioPipelineSnapshot {
        val stream = activeStream ?: throw audioError(
            "Sendspin PCM frame arrived before audio stream start.",
            mapOf("streamId" to frame.streamId),
        )
        if (stream.streamId != frame.streamId) {
            throw audioError(
                "Sendspin PCM frame targeted a stream other than the active audio stream.",
                mapOf("streamId" to frame.streamId, "activeStreamId" to stream.streamId),
            )
        }
        val format = activeFormat ?: throw audioError("Sendspin PCM format is not active for audio write.")
        if (frame.payload.size % format.bytesPerAudioFrame != 0) {
            throw audioError(
                "Sendspin PCM frame payload is not aligned to the negotiated channel count.",
                mapOf("payloadBytes" to frame.payload.size, "bytesPerAudioFrame" to format.bytesPerAudioFrame),
            )
        }
        if (queuedFrames.size >= config.maxQueuedFrames) {
            throw audioError(
                "Sendspin audio pipeline queue is full.",
                mapOf("maxQueuedFrames" to config.maxQueuedFrames),
            )
        }
        queuedFrames[AudioFrameKey(frame.timestampMs, frame.sequence)] = frame
        return drain(serverToLocalTimeMs)
    }

    /**
     * Writes queued frames that are due according to [serverToLocalTimeMs].
     *
     * @return Updated audio diagnostics after due frames are written or late frames are dropped.
     * @throws SendspinConnectionException when scheduling arithmetic overflows or the sink write
     * fails.
     */
    fun drain(serverToLocalTimeMs: (Long) -> Long?): SendspinAudioPipelineSnapshot {
        if (activeStream == null || activeSink == null) return snapshot()
        while (queuedFrames.isNotEmpty()) {
            val frame = queuedFrames.firstEntry()?.value ?: break
            val targetLocalTimeMs = serverToLocalTimeMs(frame.timestampMs)
            if (targetLocalTimeMs == null) {
                lastIssue = "waiting-for-stable-clock"
                break
            }
            val writeLeadMs = checkedSubtract(targetLocalTimeMs, monotonicClock.nowMs(), "audioWriteLeadMs")
            if (writeLeadMs > config.maxWriteAheadMs) break
            queuedFrames.pollFirstEntry()
            val latenessMs = checkedSubtract(0L, writeLeadMs, "audioFrameLatenessMs")
            if (latenessMs > config.lateFrameToleranceMs) {
                lateFrameCount = checkedAdd(lateFrameCount, 1L, "lateFrameCount")
                lastIssue = "late-frame"
                continue
            }
            writeFrame(frame)
        }
        return snapshot()
    }

    /**
     * Flushes queued and sink-buffered audio for [clear] without ending a matching active stream.
     *
     * @return Updated audio diagnostics after queued and sink-buffered PCM data is flushed.
     * @throws SendspinConnectionException when [clear] targets a different stream or sink flushing
     * fails.
     */
    fun clear(clear: SendspinStreamClear): SendspinAudioPipelineSnapshot {
        val stream = activeStream
        when {
            stream == null && clear.streamId != null -> throw audioLifecycleMismatch("stream/clear", clear.streamId, null)
            stream != null && clear.streamId != null && clear.streamId != stream.streamId -> {
                throw audioLifecycleMismatch("stream/clear", clear.streamId, stream.streamId)
            }
        }
        queuedFrames.clear()
        flushActiveSink()
        lastIssue = null
        return snapshot()
    }

    /**
     * Stops and releases audio for [end]'s matching active stream.
     *
     * @return Updated audio diagnostics after active audio resources are released.
     * @throws SendspinConnectionException when no matching stream is active or sink stop/release
     * fails.
     */
    fun end(end: SendspinStreamEnd): SendspinAudioPipelineSnapshot {
        val stream = activeStream ?: throw audioLifecycleMismatch("stream/end", end.streamId, null)
        if (stream.streamId != end.streamId) throw audioLifecycleMismatch("stream/end", end.streamId, stream.streamId)
        stopAndReleaseActiveSink()
        activeStream = null
        activeFormat = null
        queuedFrames.clear()
        lastIssue = null
        return snapshot()
    }

    /**
     * Stops, flushes, and releases active audio resources for disconnect or fatal failure.
     *
     * @return Updated audio diagnostics after active audio resources are released.
     * @throws SendspinConnectionException when sink stop/release fails.
     */
    fun release(): SendspinAudioPipelineSnapshot {
        stopAndReleaseActiveSink()
        activeStream = null
        activeFormat = null
        queuedFrames.clear()
        lastIssue = null
        return snapshot()
    }

    /**
     * Clears all audio counters and releases active sink resources for a new protocol session.
     *
     * @return Initial inactive audio diagnostics after reset.
     * @throws SendspinConnectionException when active sink stop/release fails.
     */
    fun reset(): SendspinAudioPipelineSnapshot {
        release()
        writtenFrameCount = 0L
        writtenByteCount = 0L
        lateFrameCount = 0L
        writeFailureCount = 0L
        lastIssue = null
        return snapshot()
    }

    /** Returns the latest audio diagnostics. */
    fun snapshot(): SendspinAudioPipelineSnapshot {
        val stream = activeStream
        return SendspinAudioPipelineSnapshot(
            active = stream != null && activeSink != null,
            streamId = stream?.streamId,
            codec = stream?.codec?.wireValue,
            sampleRateHz = stream?.sampleRateHz,
            channels = stream?.channels,
            queuedFrameCount = queuedFrames.size,
            writtenFrameCount = writtenFrameCount,
            writtenByteCount = writtenByteCount,
            lateFrameCount = lateFrameCount,
            underrunCount = activeSink?.underrunCount() ?: 0L,
            writeFailureCount = writeFailureCount,
            lastIssue = lastIssue,
        )
    }

    private fun writeFrame(frame: SendspinAudioFrame) {
        val sink = activeSink ?: throw audioError("Sendspin audio sink is not active for PCM write.")
        val accepted = try {
            sink.write(frame.payload)
        } catch (error: SendspinConnectionException) {
            writeFailureCount = checkedAdd(writeFailureCount, 1L, "writeFailureCount")
            throw error
        } catch (error: RuntimeException) {
            writeFailureCount = checkedAdd(writeFailureCount, 1L, "writeFailureCount")
            throw audioError("Sendspin audio sink write failed.", mapOf("message" to error.message))
        }
        if (accepted != frame.payload.size) {
            writeFailureCount = checkedAdd(writeFailureCount, 1L, "writeFailureCount")
            throw audioError(
                "Sendspin audio sink accepted a partial PCM frame.",
                mapOf("acceptedBytes" to accepted, "expectedBytes" to frame.payload.size),
            )
        }
        writtenFrameCount = checkedAdd(writtenFrameCount, 1L, "writtenFrameCount")
        writtenByteCount = checkedAdd(writtenByteCount, frame.payload.size.toLong(), "writtenByteCount")
        lastIssue = null
    }

    private fun startSink(format: SendspinPcmAudioFormat): SendspinAudioSink = try {
        sinkFactory.create(format).also { sink -> sink.start() }
    } catch (error: SendspinConnectionException) {
        throw error
    } catch (error: RuntimeException) {
        throw audioError("Sendspin audio sink could not be started.", errorDetails(error))
    }

    private fun flushActiveSink() {
        val sink = activeSink ?: return
        try {
            sink.flush()
        } catch (error: SendspinConnectionException) {
            throw error
        } catch (error: RuntimeException) {
            throw audioError("Sendspin audio sink flush failed.", errorDetails(error))
        }
    }

    private fun stopAndReleaseActiveSink() {
        val sink = activeSink ?: return
        val stopFailure = captureSinkFailure("stop") { sink.stop() }
        val releaseFailure = captureSinkFailure("release") { sink.release() }
        activeSink = null
        val failures = listOfNotNull(
            stopFailure,
            releaseFailure,
        )
        if (failures.isNotEmpty()) {
            throw audioError("Sendspin audio sink failed during stop/release.", mapOf("failures" to failures))
        }
    }

    private fun captureSinkFailure(stage: String, block: () -> Unit): Map<String, Any?>? = try {
        block()
        null
    } catch (error: SendspinConnectionException) {
        mapOf(
            "stage" to stage,
            "code" to error.code,
            "message" to error.message,
            "details" to error.details,
        )
    } catch (error: RuntimeException) {
        mapOf(
            "stage" to stage,
            "exception" to error.javaClass.name,
            "message" to error.message,
        )
    }

    private fun audioLifecycleMismatch(
        type: String,
        receivedStreamId: String?,
        activeStreamId: String?,
    ): SendspinConnectionException = audioError(
        "Sendspin audio `$type` targeted a stream other than the active audio stream.",
        mapOf("streamId" to receivedStreamId, "activeStreamId" to activeStreamId),
    )

    private fun checkedAdd(left: Long, right: Long, label: String): Long = try {
        Math.addExact(left, right)
    } catch (error: ArithmeticException) {
        throw audioError("Sendspin audio pipeline overflowed `$label` calculation.", mapOf("left" to left, "right" to right))
    }

    private fun checkedSubtract(left: Long, right: Long, label: String): Long = try {
        Math.subtractExact(left, right)
    } catch (error: ArithmeticException) {
        throw audioError("Sendspin audio pipeline overflowed `$label` calculation.", mapOf("left" to left, "right" to right))
    }

    private data class AudioFrameKey(
        val timestampMs: Long,
        val sequence: Long,
    ) : Comparable<AudioFrameKey> {
        override fun compareTo(other: AudioFrameKey): Int =
            compareValuesBy(this, other, AudioFrameKey::timestampMs, AudioFrameKey::sequence)
    }
}

private const val PCM_16_BYTES_PER_SAMPLE = 2
private fun audioError(message: String, details: Map<String, Any?>? = null): SendspinConnectionException =
    SendspinConnectionException(LocalPlayerEnvelope.LOCAL_PLAYER_AUDIO_ERROR, message, details)

private fun errorDetails(error: RuntimeException): Map<String, Any?> = mapOf(
    "exception" to error.javaClass.name,
    "message" to error.message,
)
