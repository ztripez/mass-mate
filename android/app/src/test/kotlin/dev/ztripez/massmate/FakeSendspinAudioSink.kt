package dev.ztripez.massmate

/** Fake audio sink factory that records created PCM sinks for deterministic controller tests. */
class FakeSendspinAudioSinkFactory : SendspinAudioSinkFactory {
    /** Sinks created by this factory in creation order. */
    val sinks = mutableListOf<FakeSendspinAudioSink>()

    /** Optional preconfigured sink returned by the next [create] call. */
    var nextSink: FakeSendspinAudioSink? = null

    override fun create(format: SendspinPcmAudioFormat): SendspinAudioSink {
        val sink = nextSink ?: FakeSendspinAudioSink()
        nextSink = null
        sink.format = format
        sinks.add(sink)
        return sink
    }
}

/** Fake PCM sink that records lifecycle calls and configurable write behavior. */
class FakeSendspinAudioSink(
    /** Optional accepted byte count for each write; `null` means accept the full payload. */
    var acceptedByteCount: Int? = null,
    /** Optional exception thrown from [write]. */
    var writeException: RuntimeException? = null,
) : SendspinAudioSink {
    /** Format used to create this sink. */
    var format: SendspinPcmAudioFormat? = null

    /** Payloads passed to [write]. */
    val writes = mutableListOf<ByteArray>()

    /** Count of [start] calls. */
    var startCount = 0

    /** Count of [flush] calls. */
    var flushCount = 0

    /** Count of [stop] calls. */
    var stopCount = 0

    /** Count of [release] calls. */
    var releaseCount = 0

    /** Sink-reported underruns returned from [underrunCount]. */
    var reportedUnderruns = 0L

    override fun start() {
        startCount += 1
    }

    override fun write(bytes: ByteArray): Int {
        writeException?.let { throw it }
        writes.add(bytes.copyOf())
        return acceptedByteCount ?: bytes.size
    }

    override fun flush() {
        flushCount += 1
    }

    override fun stop() {
        stopCount += 1
    }

    override fun release() {
        releaseCount += 1
    }

    override fun underrunCount(): Long = reportedUnderruns
}
