package dev.ztripez.massmate

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.TreeMap

/** Parsed binary Sendspin audio frame before codec decode or audio output. */
data class SendspinAudioFrame(
    /** Server stream identifier this frame belongs to. */
    val streamId: String,
    /** Server-clock presentation timestamp in milliseconds. */
    val timestampMs: Long,
    /** Monotonic sequence number within the stream. */
    val sequence: Long,
    /** Encoded audio payload bytes owned by the stream buffer. */
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SendspinAudioFrame) return false
        return streamId == other.streamId &&
            timestampMs == other.timestampMs &&
            sequence == other.sequence &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = streamId.hashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + sequence.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/** Debug snapshot for native stream lifecycle and buffer state. */
data class SendspinStreamBufferSnapshot(
    /** Whether a stream is currently active. */
    val active: Boolean,
    /** Active server stream identifier, or `null` when no stream is active. */
    val streamId: String? = null,
    /** Active stream codec wire value, or `null` when no stream is active. */
    val codec: String? = null,
    /** Number of queued frames in timestamp order. */
    val frameCount: Int = 0,
    /** Milliseconds between earliest and latest queued frame timestamps. */
    val bufferDepthMs: Long = 0L,
    /** Count of frames dropped or ignored by stream ownership rules. */
    val droppedFrameCount: Long = 0L,
    /** Count of sequence gaps observed on accepted active-stream frames. */
    val missingFrameCount: Long = 0L,
    /** Diagnostic reason for the latest dropped frame, or `null` when no frame was dropped. */
    val lastDropReason: String? = null,
) {
    /** Converts this stream snapshot into a platform-channel debug map. */
    fun toBridgeMap(): Map<String, Any?> = mapOf(
        "active" to active,
        "streamId" to streamId,
        "codec" to codec,
        "frameCount" to frameCount,
        "bufferDepthMs" to bufferDepthMs,
        "droppedFrameCount" to droppedFrameCount,
        "missingFrameCount" to missingFrameCount,
        "lastDropReason" to lastDropReason,
    )

    companion object {
        /** Creates the initial inactive stream snapshot. */
        fun inactive(): SendspinStreamBufferSnapshot = SendspinStreamBufferSnapshot(active = false)
    }
}

/** Result of parsing and accepting or dropping one binary Sendspin frame. */
data class SendspinStreamBufferReceiveResult(
    /** Updated stream buffer diagnostics after the frame was processed. */
    val snapshot: SendspinStreamBufferSnapshot,
    /** Accepted frame ready for the audio pipeline, or `null` when the frame was dropped. */
    val acceptedFrame: SendspinAudioFrame? = null,
)

/** Configuration for native Sendspin stream buffering. */
data class SendspinStreamBufferConfig(
    /** Positive maximum number of frames retained before newer frames are dropped. */
    val maxBufferedFrames: Int = 128,
) {
    init {
        require(maxBufferedFrames > 0) { "maxBufferedFrames must be positive." }
    }
}

/** Parses binary Sendspin frame envelopes into typed native frame descriptors. */
object SendspinBinaryFrameParser {
    private val magic = byteArrayOf('S'.code.toByte(), 'S'.code.toByte(), 'A'.code.toByte(), 'F'.code.toByte())
    private const val VERSION: Byte = 1
    private const val HEADER_SIZE = 4 + 1 + 1 + 8 + 8 + 4

    /**
     * Parses [bytes] into a [SendspinAudioFrame].
     *
     * The frame envelope is big-endian: four magic bytes `SSAF`, one version byte, one stream-id
     * length byte, an eight-byte timestamp, an eight-byte sequence, a four-byte payload length,
     * UTF-8 stream id bytes, and payload bytes.
     *
     * @throws SendspinConnectionException when the binary frame is malformed or unsupported.
     */
    fun parse(bytes: ByteArray): SendspinAudioFrame {
        if (bytes.size < HEADER_SIZE) throw protocolError("Sendspin binary frame is shorter than the header.")
        if (!bytes.copyOfRange(0, magic.size).contentEquals(magic)) {
            throw protocolError("Sendspin binary frame has invalid magic bytes.")
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.position(magic.size)
        val version = buffer.get()
        if (version != VERSION) {
            throw protocolError("Sendspin binary frame has unsupported version.", mapOf("version" to version.toInt()))
        }
        val streamIdLength = buffer.get().toInt() and 0xff
        val timestampMs = buffer.long
        val sequence = buffer.long
        val payloadLength = buffer.int
        if (streamIdLength <= 0) throw protocolError("Sendspin binary frame has an empty stream id.")
        if (timestampMs < 0L) throw protocolError("Sendspin binary frame timestamp must be non-negative.")
        if (sequence < 0L) throw protocolError("Sendspin binary frame sequence must be non-negative.")
        if (payloadLength <= 0) throw protocolError("Sendspin binary frame payload must be non-empty.")
        val expectedSize = checkedFrameSize(streamIdLength, payloadLength)
        if (bytes.size != expectedSize) {
            throw protocolError(
                "Sendspin binary frame size does not match declared payload length.",
                mapOf("actualSize" to bytes.size, "expectedSize" to expectedSize),
            )
        }
        val streamIdBytes = ByteArray(streamIdLength)
        buffer.get(streamIdBytes)
        val streamId = decodeStreamId(streamIdBytes)
        val payload = ByteArray(payloadLength)
        buffer.get(payload)
        return SendspinAudioFrame(streamId, timestampMs, sequence, payload)
    }

    private fun checkedFrameSize(streamIdLength: Int, payloadLength: Int): Int = try {
        Math.addExact(HEADER_SIZE, Math.addExact(streamIdLength, payloadLength))
    } catch (error: ArithmeticException) {
        throw protocolError("Sendspin binary frame declared size overflows integer bounds.")
    }

    private fun protocolError(message: String, details: Map<String, Any?>? = null): SendspinConnectionException =
        SendspinProtocolJson.protocolError(message, details)

    private fun decodeStreamId(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: CharacterCodingException) {
            throw protocolError("Sendspin binary frame stream id is not valid UTF-8.")
        }
    }
}

/**
 * Owns active Sendspin stream lifecycle and the timestamp-ordered frame buffer.
 *
 * Instances are mutable and must be called by a single serialized owner, such as the Sendspin
 * connection controller queue.
 *
 * @param config Buffer capacity and validation thresholds for queued audio frames.
 */
class SendspinStreamBuffer(
    private val config: SendspinStreamBufferConfig = SendspinStreamBufferConfig(),
) {
    private var activeStream: SendspinStreamStart? = null
    private val framesByKey = TreeMap<FrameKey, SendspinAudioFrame>()
    private val acceptedSequences = mutableSetOf<Long>()
    private var highestSequence: Long? = null
    private var droppedFrameCount = 0L
    private var missingFrameCount = 0L
    private var lastDropReason: String? = null

    /** Clears active stream state, queued frames, counters, and diagnostics for a fresh session. */
    fun reset(): SendspinStreamBufferSnapshot {
        activeStream = null
        clearBuffer()
        droppedFrameCount = 0L
        missingFrameCount = 0L
        lastDropReason = null
        return snapshot()
    }

    /** Starts [stream] and clears any previously queued stream-owned frames. */
    fun start(stream: SendspinStreamStart): SendspinStreamBufferSnapshot {
        activeStream = stream
        clearBuffer()
        lastDropReason = null
        return snapshot()
    }

    /**
     * Clears queued frames for [clear] while preserving a matching active stream.
     *
     * @throws SendspinConnectionException when [clear] names a stream other than the active stream,
     * or names a stream while no stream is active.
     */
    fun clear(clear: SendspinStreamClear): SendspinStreamBufferSnapshot {
        val active = activeStream
        when {
            active == null && clear.streamId != null -> throw lifecycleMismatch("stream/clear", clear.streamId, null)
            active == null || clear.streamId == null || clear.streamId == active.streamId -> clearBuffer()
            else -> throw lifecycleMismatch("stream/clear", clear.streamId, active.streamId)
        }
        return snapshot()
    }

    /**
     * Ends [end]'s matching active stream and clears stream-owned state.
     *
     * @throws SendspinConnectionException when no stream is active or [end] names a different
     * stream than the active stream.
     */
    fun end(end: SendspinStreamEnd): SendspinStreamBufferSnapshot {
        val active = activeStream ?: throw lifecycleMismatch("stream/end", end.streamId, null)
        if (active.streamId == end.streamId) {
            activeStream = null
            clearBuffer()
        } else {
            throw lifecycleMismatch("stream/end", end.streamId, active.streamId)
        }
        return snapshot()
    }

    /**
     * Parses and buffers [bytes], returning updated stream diagnostics.
     *
     * Binary frames are dropped with visible counters when no stream is active, the stream id does
     * not match, the sequence is duplicate, or the bounded buffer is full. Malformed active-stream
     * frames throw [SendspinConnectionException] with `LOCAL_PLAYER_PROTOCOL_ERROR` before any
     * lifecycle drop handling.
     */
    fun receiveBinary(bytes: ByteArray): SendspinStreamBufferSnapshot = receiveBinaryResult(bytes).snapshot

    /**
     * Parses and buffers [bytes], returning diagnostics and the accepted frame when audio may consume it.
     *
     * Dropped frames return `null` for [SendspinStreamBufferReceiveResult.acceptedFrame]. Malformed
     * frames throw [SendspinConnectionException] before any lifecycle drop handling.
     */
    fun receiveBinaryResult(bytes: ByteArray): SendspinStreamBufferReceiveResult {
        val frame = SendspinBinaryFrameParser.parse(bytes)
        val active = activeStream
        if (active == null) {
            drop("no-active-stream")
            return SendspinStreamBufferReceiveResult(snapshot())
        }
        if (frame.streamId != active.streamId) {
            drop("stream-mismatch")
            return SendspinStreamBufferReceiveResult(snapshot())
        }
        if (frame.sequence in acceptedSequences) {
            drop("duplicate-sequence")
            return SendspinStreamBufferReceiveResult(snapshot())
        }
        if (framesByKey.size >= config.maxBufferedFrames) {
            drop("buffer-full")
            return SendspinStreamBufferReceiveResult(snapshot())
        }
        recordMissingFrames(frame.sequence)
        acceptedSequences.add(frame.sequence)
        framesByKey[FrameKey(frame.timestampMs, frame.sequence)] = frame
        lastDropReason = null
        return SendspinStreamBufferReceiveResult(snapshot(), frame)
    }

    /** Returns buffered frames in deterministic timestamp then sequence order. */
    fun bufferedFrames(): List<SendspinAudioFrame> = framesByKey.values.toList()

    /**
     * Removes [frame] after ownership has been transferred to the audio pipeline.
     *
     * @return Updated stream diagnostics after the frame is consumed.
     * @throws SendspinConnectionException when [frame] is not currently retained by this buffer.
     */
    fun consume(frame: SendspinAudioFrame): SendspinStreamBufferSnapshot {
        val key = FrameKey(frame.timestampMs, frame.sequence)
        val removed = framesByKey.remove(key)
        if (removed == null || removed.streamId != frame.streamId) {
            throw SendspinProtocolJson.protocolError(
                "Sendspin stream buffer cannot consume a frame it does not own.",
                mapOf("streamId" to frame.streamId, "timestampMs" to frame.timestampMs, "sequence" to frame.sequence),
            )
        }
        return snapshot()
    }

    /** Returns the current stream/buffer debug snapshot. */
    fun snapshot(): SendspinStreamBufferSnapshot {
        val active = activeStream
        val depth = if (framesByKey.size < 2) {
            0L
        } else {
            framesByKey.lastKey().timestampMs - framesByKey.firstKey().timestampMs
        }
        return SendspinStreamBufferSnapshot(
            active = active != null,
            streamId = active?.streamId,
            codec = active?.codec?.wireValue,
            frameCount = framesByKey.size,
            bufferDepthMs = depth,
            droppedFrameCount = droppedFrameCount,
            missingFrameCount = missingFrameCount,
            lastDropReason = lastDropReason,
        )
    }

    private fun clearBuffer() {
        framesByKey.clear()
        acceptedSequences.clear()
        highestSequence = null
    }

    private fun recordMissingFrames(sequence: Long) {
        val previous = highestSequence
        if (previous != null && sequence > previous) {
            val gap = checkedSubtract(sequence, previous, "streamSequenceGap")
            if (gap > 1L) {
                missingFrameCount = checkedAdd(missingFrameCount, gap - 1L, "missingFrameCount")
            }
        }
        if (previous == null || sequence > previous) highestSequence = sequence
    }

    private fun checkedAdd(left: Long, right: Long, label: String): Long = try {
        Math.addExact(left, right)
    } catch (error: ArithmeticException) {
        throw SendspinProtocolJson.protocolError(
            "Sendspin stream buffer overflowed `$label` calculation.",
            mapOf("left" to left, "right" to right),
        )
    }

    private fun checkedSubtract(left: Long, right: Long, label: String): Long = try {
        Math.subtractExact(left, right)
    } catch (error: ArithmeticException) {
        throw SendspinProtocolJson.protocolError(
            "Sendspin stream buffer overflowed `$label` calculation.",
            mapOf("left" to left, "right" to right),
        )
    }

    private fun drop(reason: String) {
        droppedFrameCount = checkedAdd(droppedFrameCount, 1L, "droppedFrameCount")
        lastDropReason = reason
    }

    private fun lifecycleMismatch(
        type: String,
        receivedStreamId: String?,
        activeStreamId: String?,
    ): SendspinConnectionException = SendspinProtocolJson.protocolError(
        "Sendspin `$type` targeted a stream other than the active stream.",
        mapOf("streamId" to receivedStreamId, "activeStreamId" to activeStreamId),
    )

    private data class FrameKey(
        val timestampMs: Long,
        val sequence: Long,
    ) : Comparable<FrameKey> {
        override fun compareTo(other: FrameKey): Int = compareValuesBy(this, other, FrameKey::timestampMs, FrameKey::sequence)
    }
}
