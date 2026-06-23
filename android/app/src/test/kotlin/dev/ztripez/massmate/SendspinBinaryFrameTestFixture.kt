package dev.ztripez.massmate

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** Builds deterministic binary Sendspin frame envelopes for fake transport tests. */
fun sendspinBinaryFrameBytes(
    streamId: String,
    timestampMs: Long,
    sequence: Long,
    payload: ByteArray = byteArrayOf(0x01),
): ByteArray {
    val streamIdBytes = streamId.toByteArray(StandardCharsets.UTF_8)
    require(streamIdBytes.size in 1..255) { "streamId must encode to 1..255 bytes." }
    require(timestampMs >= 0L) { "timestampMs must be non-negative." }
    require(sequence >= 0L) { "sequence must be non-negative." }
    require(payload.isNotEmpty()) { "payload must be non-empty." }

    return ByteBuffer.allocate(SENDSPIN_BINARY_HEADER_SIZE + streamIdBytes.size + payload.size)
        .order(ByteOrder.BIG_ENDIAN)
        .put('S'.code.toByte())
        .put('S'.code.toByte())
        .put('A'.code.toByte())
        .put('F'.code.toByte())
        .put(1)
        .put(streamIdBytes.size.toByte())
        .putLong(timestampMs)
        .putLong(sequence)
        .putInt(payload.size)
        .put(streamIdBytes)
        .put(payload)
        .array()
}

private const val SENDSPIN_BINARY_HEADER_SIZE = 4 + 1 + 1 + 8 + 8 + 4
