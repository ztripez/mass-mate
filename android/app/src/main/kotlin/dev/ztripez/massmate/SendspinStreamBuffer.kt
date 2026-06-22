package dev.ztripez.massmate

import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        val streamId = String(streamIdBytes, StandardCharsets.UTF_8)
        val payload = ByteArray(payloadLength)
        buffer.get(payload)
        return SendspinAudioFrame(streamId, timestampMs, sequence, payload)
    }

    /** Builds a binary frame envelope for deterministic fake transport tests. */
    fun encode(frame: SendspinAudioFrame): ByteArray {
        val streamIdBytes = frame.streamId.toByteArray(StandardCharsets.UTF_8)
        require(streamIdBytes.size in 1..255) { "streamId must encode to 1..255 bytes." }
        require(frame.timestampMs >= 0L) { "timestampMs must be non-negative." }
        require(frame.sequence >= 0L) { "sequence must be non-negative." }
        require(frame.payload.isNotEmpty()) { "payload must be non-empty." }
        val buffer = ByteBuffer.allocate(HEADER_SIZE + streamIdBytes.size + frame.payload.size)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(magic)
        buffer.put(VERSION)
        buffer.put(streamIdBytes.size.toByte())
        buffer.putLong(frame.timestampMs)
        buffer.putLong(frame.sequence)
        buffer.putInt(frame.payload.size)
        buffer.put(streamIdBytes)
        buffer.put(frame.payload)
        return buffer.array()
    }

    private fun checkedFrameSize(streamIdLength: Int, payloadLength: Int): Int = try {
        Math.addExact(HEADER_SIZE, Math.addExact(streamIdLength, payloadLength))
    } catch (error: ArithmeticException) {
        throw protocolError("Sendspin binary frame declared size overflows integer bounds.")
    }

    private fun protocolError(message: String, details: Map<String, Any?>? = null): SendspinConnectionException =
        SendspinProtocolJson.protocolError(message, details)
}

/** Owns active Sendspin stream lifecycle and the timestamp-ordered frame buffer. */
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

    /** Clears queued frames for [clear] while preserving a matching active stream. */
    fun clear(clear: SendspinStreamClear): SendspinStreamBufferSnapshot {
        val active = activeStream
        if (active == null || clear.streamId == null || clear.streamId == active.streamId) {
            clearBuffer()
        }
        return snapshot()
    }

    /** Ends [end]'s matching active stream and clears stream-owned state. */
    fun end(end: SendspinStreamEnd): SendspinStreamBufferSnapshot {
        if (activeStream?.streamId == end.streamId) {
            activeStream = null
            clearBuffer()
        }
        return snapshot()
    }

    /** Parses and buffers [bytes], returning updated stream diagnostics. */
    fun receiveBinary(bytes: ByteArray): SendspinStreamBufferSnapshot {
        val active = activeStream
        if (active == null) {
            drop("no-active-stream")
            return snapshot()
        }
        val frame = SendspinBinaryFrameParser.parse(bytes)
        if (frame.streamId != active.streamId) {
            drop("stream-mismatch")
            return snapshot()
        }
        if (frame.sequence in acceptedSequences) {
            drop("duplicate-sequence")
            return snapshot()
        }
        if (framesByKey.size >= config.maxBufferedFrames) {
            drop("buffer-full")
            return snapshot()
        }
        recordMissingFrames(frame.sequence)
        acceptedSequences.add(frame.sequence)
        framesByKey[FrameKey(frame.timestampMs, frame.sequence)] = frame
        lastDropReason = null
        return snapshot()
    }

    /** Returns buffered frames in deterministic timestamp then sequence order. */
    fun bufferedFrames(): List<SendspinAudioFrame> = framesByKey.values.toList()

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
        droppedFrameCount += 1L
        lastDropReason = reason
    }

    private data class FrameKey(
        val timestampMs: Long,
        val sequence: Long,
    ) : Comparable<FrameKey> {
        override fun compareTo(other: FrameKey): Int = compareValuesBy(this, other, FrameKey::timestampMs, FrameKey::sequence)
    }
}
