package dev.ztripez.massmate

import android.os.SystemClock

/** Monotonic local clock used for Sendspin scheduling calculations. */
fun interface SendspinMonotonicClock {
    /** Returns monotonically increasing local time in milliseconds. */
    fun nowMs(): Long
}

/** Android elapsed-realtime clock for local Sendspin timing. */
object AndroidSendspinMonotonicClock : SendspinMonotonicClock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}

/**
 * Quality of the current server-to-local clock synchronization estimate.
 *
 * @property bridgeValue String value serialized into native local-player debug snapshots.
 */
enum class SendspinClockQuality(val bridgeValue: String) {
    /** No accepted timing sample exists. */
    UNSYNCHRONIZED("unsynchronized"),

    /** Enough low-jitter samples exist for audio scheduling to use the mapping. */
    STABLE("stable"),

    /** Timing exists but is too sparse, jittery, or high-latency for stable scheduling. */
    DEGRADED("degraded"),

    /** The latest accepted sample is too old for scheduling. */
    STALE("stale"),

    /** A large offset discontinuity reset accepted samples. */
    RESET("reset"),
}

/** Immutable debug snapshot for native Sendspin clock synchronization. */
data class SendspinClockSnapshot(
    /** Current synchronization quality. */
    val quality: SendspinClockQuality,
    /** Estimated server-minus-local offset in milliseconds, or `null` without accepted samples. */
    val offsetMs: Long? = null,
    /** Round-trip time of the latest processed sample in milliseconds. */
    val rttMs: Long? = null,
    /** Offset range across accepted samples in milliseconds. */
    val jitterMs: Long? = null,
    /** Number of accepted samples contributing to [offsetMs]. */
    val sampleCount: Int = 0,
    /** Age of the latest accepted sample in milliseconds. */
    val lastSampleAgeMs: Long? = null,
    /** Count of discontinuity resets since this synchronizer was created. */
    val resetCount: Int = 0,
    /** Diagnostic reason for degraded, stale, or reset quality. */
    val reason: String? = null,
) {
    /** Converts this timing snapshot into a platform-channel debug map. */
    fun toBridgeMap(): Map<String, Any?> = mapOf(
        "quality" to quality.bridgeValue,
        "offsetMs" to offsetMs,
        "rttMs" to rttMs,
        "jitterMs" to jitterMs,
        "sampleCount" to sampleCount,
        "lastSampleAgeMs" to lastSampleAgeMs,
        "resetCount" to resetCount,
        "reason" to reason,
    )

    companion object {
        /** Creates an unsynchronized snapshot before any time response is accepted. */
        fun unsynchronized(): SendspinClockSnapshot = SendspinClockSnapshot(
            quality = SendspinClockQuality.UNSYNCHRONIZED,
            reason = "no-samples",
        )
    }
}

/**
 * Configuration thresholds for Sendspin NTP-style clock synchronization.
 *
 * @property sampleWindowSize Positive count of accepted samples retained for offset and jitter
 * calculations.
 * @property stableSampleCount Positive minimum accepted sample count required before reporting stable
 * quality.
 * @property maxStableJitterMs Non-negative maximum accepted-sample offset range allowed for stable
 * scheduling.
 * @property maxAcceptedRttMs Non-negative maximum round-trip time accepted into the offset window.
 * @property maxDiscontinuityMs Non-negative maximum offset jump before existing samples are reset.
 * @property staleAfterMs Non-negative maximum age of the latest sample before sync is reported stale.
 * @throws IllegalArgumentException when counts are not positive or thresholds are negative.
 */
data class SendspinClockSyncConfig(
    val sampleWindowSize: Int = 5,
    val stableSampleCount: Int = 3,
    val maxStableJitterMs: Long = 20L,
    val maxAcceptedRttMs: Long = 250L,
    val maxDiscontinuityMs: Long = 1_000L,
    val staleAfterMs: Long = 5_000L,
) {
    init {
        require(sampleWindowSize > 0) { "sampleWindowSize must be positive." }
        require(stableSampleCount > 0) { "stableSampleCount must be positive." }
        require(maxStableJitterMs >= 0) { "maxStableJitterMs must be non-negative." }
        require(maxAcceptedRttMs >= 0) { "maxAcceptedRttMs must be non-negative." }
        require(maxDiscontinuityMs >= 0) { "maxDiscontinuityMs must be non-negative." }
        require(staleAfterMs >= 0) { "staleAfterMs must be non-negative." }
    }
}

/** NTP-style Sendspin server-time to Android monotonic-time synchronizer. */
class SendspinClockSynchronizer(
    private val config: SendspinClockSyncConfig = SendspinClockSyncConfig(),
) {
    private val acceptedSamples = ArrayDeque<ClockSample>()
    private var resetCount = 0
    private var overrideQuality: SendspinClockQuality? = null
    private var overrideReason: String? = null
    private var latestRttMs: Long? = null

    /**
     * Processes [response] received at [localReceivedAtMs] and returns updated timing diagnostics.
     *
     * [response.clientSentAtMs] and [localReceivedAtMs] must be local monotonic milliseconds from
     * the same Android clock domain. Server timestamps must be non-negative and ordered in the
     * server clock domain. Samples with excessive round-trip time degrade quality without entering
     * the accepted offset window. Large offset discontinuities clear accepted samples and return a
     * reset snapshot.
     *
     * @throws SendspinConnectionException when timestamps are negative, out of order, overflow a
     * duration calculation, or imply impossible round-trip timing.
     */
    fun recordServerTime(
        response: SendspinServerTime,
        localReceivedAtMs: Long,
    ): SendspinClockSnapshot {
        validateOrdering(response, localReceivedAtMs)

        val serverProcessingMs = checkedSubtract(
            response.serverSentAtMs,
            response.serverReceivedAtMs,
            "serverProcessingMs",
        )
        val localElapsedMs = checkedSubtract(localReceivedAtMs, response.clientSentAtMs, "localElapsedMs")
        val rttMs = checkedSubtract(localElapsedMs, serverProcessingMs, "rttMs")
        if (rttMs < 0L) {
            throw SendspinProtocolJson.protocolError(
                "Sendspin time response has impossible round-trip timing.",
                mapOf("rttMs" to rttMs),
            )
        }
        latestRttMs = rttMs

        if (rttMs > config.maxAcceptedRttMs) {
            overrideQuality = SendspinClockQuality.DEGRADED
            overrideReason = "high-rtt"
            return snapshot(localReceivedAtMs)
        }

        val offsetMs = midpoint(response.serverReceivedAtMs, response.serverSentAtMs) -
            midpoint(response.clientSentAtMs, localReceivedAtMs)
        val currentOffset = averageOffsetMs()
        if (currentOffset != null && offsetDistanceExceeds(offsetMs, currentOffset, config.maxDiscontinuityMs)) {
            acceptedSamples.clear()
            resetCount += 1
            overrideQuality = SendspinClockQuality.RESET
            overrideReason = "offset-discontinuity"
            return snapshot(localReceivedAtMs)
        }

        acceptedSamples.addLast(ClockSample(offsetMs, rttMs, localReceivedAtMs))
        while (acceptedSamples.size > config.sampleWindowSize) acceptedSamples.removeFirst()
        overrideQuality = null
        overrideReason = null
        return snapshot(localReceivedAtMs)
    }

    /**
     * Returns the latest debug snapshot evaluated at [nowMs].
     *
     * @throws SendspinConnectionException when [nowMs] predates the latest accepted sample or age
     * arithmetic overflows.
     */
    fun snapshot(nowMs: Long): SendspinClockSnapshot {
        if (acceptedSamples.isEmpty()) {
            val quality = overrideQuality ?: SendspinClockQuality.UNSYNCHRONIZED
            return SendspinClockSnapshot(
                quality = quality,
                rttMs = latestRttMs,
                resetCount = resetCount,
                reason = overrideReason ?: "no-samples",
            )
        }

        val lastSampleAgeMs = sampleAgeMs(nowMs, acceptedSamples.last())
        if (lastSampleAgeMs > config.staleAfterMs) {
            return currentSnapshot(
                quality = SendspinClockQuality.STALE,
                nowMs = nowMs,
                reason = "stale-sync",
            )
        }

        val override = overrideQuality
        if (override != null) {
            return currentSnapshot(quality = override, nowMs = nowMs, reason = overrideReason)
        }

        val jitterMs = jitterMs()
        val quality = if (
            acceptedSamples.size >= config.stableSampleCount && jitterMs <= config.maxStableJitterMs
        ) {
            SendspinClockQuality.STABLE
        } else {
            SendspinClockQuality.DEGRADED
        }
        val reason = when (quality) {
            SendspinClockQuality.STABLE -> null
            SendspinClockQuality.DEGRADED -> if (acceptedSamples.size < config.stableSampleCount) {
                "insufficient-samples"
            } else {
                "high-jitter"
            }
            else -> null
        }
        return currentSnapshot(quality = quality, nowMs = nowMs, reason = reason)
    }

    /**
     * Maps [serverTimeMs] to local monotonic time only when the synchronizer is stable.
     *
     * [nowMs] is the local monotonic time used to evaluate stale sync before mapping. Returns `null`
     * when no stable, fresh offset exists.
     *
     * @throws SendspinConnectionException when mapping arithmetic overflows or [nowMs] predates the
     * latest accepted local sample.
     */
    fun stableServerTimeToLocalTimeMs(serverTimeMs: Long, nowMs: Long): Long? {
        val current = snapshot(nowMs)
        if (current.quality != SendspinClockQuality.STABLE) return null
        val offset = current.offsetMs ?: return null
        return checkedSubtract(serverTimeMs, offset, "mappedLocalTimeMs")
    }

    /** Clears all samples and reports unsynchronized state. */
    fun reset() {
        acceptedSamples.clear()
        latestRttMs = null
        overrideQuality = null
        overrideReason = null
    }

    private fun validateOrdering(response: SendspinServerTime, localReceivedAtMs: Long) {
        val timestamps = mapOf(
            "clientSentAtMs" to response.clientSentAtMs,
            "localReceivedAtMs" to localReceivedAtMs,
            "serverReceivedAtMs" to response.serverReceivedAtMs,
            "serverSentAtMs" to response.serverSentAtMs,
        )
        val negative = timestamps.filterValues { it < 0L }
        if (negative.isNotEmpty()) {
            throw SendspinProtocolJson.protocolError(
                "Sendspin time response contains negative timestamps.",
                negative,
            )
        }
        if (localReceivedAtMs < response.clientSentAtMs) {
            throw SendspinProtocolJson.protocolError(
                "Sendspin time response local receive time predates local send time.",
                mapOf(
                    "clientSentAtMs" to response.clientSentAtMs,
                    "localReceivedAtMs" to localReceivedAtMs,
                ),
            )
        }
        if (response.serverSentAtMs < response.serverReceivedAtMs) {
            throw SendspinProtocolJson.protocolError(
                "Sendspin time response server send time predates server receive time.",
                mapOf(
                    "serverReceivedAtMs" to response.serverReceivedAtMs,
                    "serverSentAtMs" to response.serverSentAtMs,
                ),
            )
        }
    }

    private fun currentSnapshot(
        quality: SendspinClockQuality,
        nowMs: Long,
        reason: String?,
    ): SendspinClockSnapshot = SendspinClockSnapshot(
        quality = quality,
        offsetMs = averageOffsetMs(),
        rttMs = latestRttMs,
        jitterMs = jitterMs(),
        sampleCount = acceptedSamples.size,
        lastSampleAgeMs = sampleAgeMs(nowMs, acceptedSamples.last()),
        resetCount = resetCount,
        reason = reason,
    )

    private fun averageOffsetMs(): Long? {
        if (acceptedSamples.isEmpty()) return null
        var sum = 0L
        for (sample in acceptedSamples) {
            sum = checkedAdd(sum, sample.offsetMs, "offsetSumMs")
        }
        return sum / acceptedSamples.size
    }

    private fun jitterMs(): Long {
        if (acceptedSamples.isEmpty()) return 0L
        val offsets = acceptedSamples.map { it.offsetMs }
        return checkedSubtract(offsets.max(), offsets.min(), "jitterMs")
    }

    private fun midpoint(first: Long, second: Long): Long = first / 2L + second / 2L + (first % 2L + second % 2L) / 2L

    private fun offsetDistanceExceeds(left: Long, right: Long, maximum: Long): Boolean =
        checkedSubtract(maxOf(left, right), minOf(left, right), "offsetDeltaMs") > maximum

    private fun sampleAgeMs(nowMs: Long, sample: ClockSample): Long {
        val ageMs = checkedSubtract(nowMs, sample.localReceivedAtMs, "lastSampleAgeMs")
        if (ageMs < 0L) {
            throw SendspinProtocolJson.protocolError(
                "Sendspin monotonic clock regressed while evaluating time synchronization.",
                mapOf("nowMs" to nowMs, "lastSampleAtMs" to sample.localReceivedAtMs),
            )
        }
        return ageMs
    }

    private fun checkedAdd(left: Long, right: Long, label: String): Long = try {
        Math.addExact(left, right)
    } catch (error: ArithmeticException) {
        throw SendspinProtocolJson.protocolError(
            "Sendspin time response overflowed `$label` calculation.",
            mapOf("left" to left, "right" to right),
        )
    }

    private fun checkedSubtract(left: Long, right: Long, label: String): Long = try {
        Math.subtractExact(left, right)
    } catch (error: ArithmeticException) {
        throw SendspinProtocolJson.protocolError(
            "Sendspin time response overflowed `$label` calculation.",
            mapOf("left" to left, "right" to right),
        )
    }

    private data class ClockSample(
        val offsetMs: Long,
        val rttMs: Long,
        val localReceivedAtMs: Long,
    )
}

/**
 * Owns active Sendspin time requests and validates `server/time` responses against them.
 *
 * @param monotonicClock Local Android monotonic clock used to stamp requests and receive times.
 * @param synchronizer Offset estimator that converts validated responses into timing diagnostics.
 * @param minimumRequestIntervalMs Non-negative minimum delay before automatic follow-up requests.
 * @throws IllegalArgumentException when [minimumRequestIntervalMs] is negative.
 */
class SendspinTimingController(
    private val monotonicClock: SendspinMonotonicClock = AndroidSendspinMonotonicClock,
    private val synchronizer: SendspinClockSynchronizer = SendspinClockSynchronizer(),
    private val minimumRequestIntervalMs: Long = 1_000L,
) {
    private val pendingRequests = mutableMapOf<String, Long>()
    private var requestSequence = 0L
    private var lastRequestSentAtMs: Long? = null

    init {
        require(minimumRequestIntervalMs >= 0L) { "minimumRequestIntervalMs must be non-negative." }
    }

    /** Clears pending requests and accepted synchronization samples for a fresh protocol session. */
    fun reset() {
        pendingRequests.clear()
        requestSequence = 0L
        lastRequestSentAtMs = null
        synchronizer.reset()
    }

    /**
     * Creates, tracks, and returns the next `client/time` request for [sessionId].
     *
     * The returned request contains a unique request id and the current [monotonicClock] timestamp.
     *
     * @throws SendspinConnectionException when another request is already awaiting a response.
     */
    fun createRequest(sessionId: Long): SendspinClientTime {
        if (pendingRequests.isNotEmpty()) {
            throw SendspinProtocolJson.protocolError(
                "Cannot create Sendspin time request while another request is pending.",
                mapOf("pendingRequestIds" to pendingRequests.keys.toList()),
            )
        }
        requestSequence += 1L
        val requestId = "time-$sessionId-$requestSequence"
        val sentAtMs = monotonicClock.nowMs()
        pendingRequests[requestId] = sentAtMs
        lastRequestSentAtMs = sentAtMs
        return SendspinClientTime.request(requestId, sentAtMs)
    }

    /**
     * Creates a follow-up `client/time` request for [sessionId] when pacing allows one.
     *
     * Returns `null` while another request is pending or the minimum request interval has not elapsed.
     *
     * @throws SendspinConnectionException when request interval arithmetic overflows.
     */
    fun createFollowUpRequest(sessionId: Long): SendspinClientTime? {
        if (pendingRequests.isNotEmpty()) return null
        val lastSentAtMs = lastRequestSentAtMs ?: return createRequest(sessionId)
        val elapsedMs = checkedControllerSubtract(monotonicClock.nowMs(), lastSentAtMs, "timeRequestIntervalMs")
        if (elapsedMs < minimumRequestIntervalMs) return null
        return createRequest(sessionId)
    }

    /**
     * Validates [response] against a pending request and records it in the synchronizer.
     *
     * Returns the updated timing snapshot after the response is accepted, degraded, or reset.
     *
     * @throws SendspinConnectionException when the request id is unknown, the echoed client send
     * timestamp does not match the locally recorded value, or timing arithmetic is invalid.
     */
    fun recordResponse(response: SendspinServerTime): SendspinClockSnapshot {
        val sentAtMs = pendingRequests.remove(response.requestId) ?: throw SendspinProtocolJson.protocolError(
            "Sendspin time response does not match an active client time request.",
            mapOf("requestId" to response.requestId),
        )
        if (response.clientSentAtMs != sentAtMs) {
            throw SendspinProtocolJson.protocolError(
                "Sendspin time response echoed a mismatched client send timestamp.",
                mapOf(
                    "requestId" to response.requestId,
                    "expectedClientSentAtMs" to sentAtMs,
                    "actualClientSentAtMs" to response.clientSentAtMs,
                ),
            )
        }
        return synchronizer.recordServerTime(response, monotonicClock.nowMs())
    }

    /**
     * Returns the latest debug timing snapshot evaluated against the current local clock.
     *
     * @throws SendspinConnectionException when the local clock appears to regress or age arithmetic
     * overflows while evaluating stale synchronization.
     */
    fun snapshot(): SendspinClockSnapshot = synchronizer.snapshot(monotonicClock.nowMs())

    /**
     * Maps server time to local monotonic time when current synchronization is stable and fresh.
     *
     * Returns `null` when the synchronizer is unsynchronized, degraded, stale, or recently reset.
     * Throws [SendspinConnectionException] when mapping arithmetic overflows or the local clock
     * appears to regress while evaluating freshness.
     */
    fun stableServerTimeToLocalTimeMs(serverTimeMs: Long): Long? =
        synchronizer.stableServerTimeToLocalTimeMs(serverTimeMs, monotonicClock.nowMs())

    private fun checkedControllerSubtract(left: Long, right: Long, label: String): Long = try {
        Math.subtractExact(left, right)
    } catch (error: ArithmeticException) {
        throw SendspinProtocolJson.protocolError(
            "Sendspin time request pacing overflowed `$label` calculation.",
            mapOf("left" to left, "right" to right),
        )
    }
}
