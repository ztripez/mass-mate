package dev.ztripez.massmate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendspinClockSynchronizerTest {
    private val config = SendspinClockSyncConfig(
        sampleWindowSize = 5,
        stableSampleCount = 3,
        maxStableJitterMs = 20L,
        maxAcceptedRttMs = 100L,
        maxDiscontinuityMs = 500L,
        staleAfterMs = 1_000L,
    )

    @Test
    fun stableSamplesMapServerTimeToLocalMonotonicTime() {
        val synchronizer = SendspinClockSynchronizer(config)

        synchronizer.recordServerTime(time("a", 1_000L, 5_000L, 5_002L), localReceivedAtMs = 1_050L)
        synchronizer.recordServerTime(time("b", 1_100L, 5_100L, 5_102L), localReceivedAtMs = 1_150L)
        val snapshot = synchronizer.recordServerTime(
            time("c", 1_200L, 5_200L, 5_202L),
            localReceivedAtMs = 1_250L,
        )

        assertEquals(SendspinClockQuality.STABLE, snapshot.quality)
        assertEquals(3_976L, snapshot.offsetMs)
        assertEquals(48L, snapshot.rttMs)
        assertEquals(0L, snapshot.jitterMs)
        assertEquals(5_024L, synchronizer.stableServerTimeToLocalTimeMs(9_000L, nowMs = 1_250L))
    }

    @Test
    fun highJitterKeepsMappingDegraded() {
        val synchronizer = SendspinClockSynchronizer(config)

        synchronizer.recordServerTime(time("a", 1_000L, 5_000L, 5_002L), localReceivedAtMs = 1_050L)
        synchronizer.recordServerTime(time("b", 1_100L, 5_140L, 5_142L), localReceivedAtMs = 1_150L)
        val snapshot = synchronizer.recordServerTime(
            time("c", 1_200L, 5_250L, 5_252L),
            localReceivedAtMs = 1_250L,
        )

        assertEquals(SendspinClockQuality.DEGRADED, snapshot.quality)
        assertTrue(snapshot.jitterMs ?: 0L > config.maxStableJitterMs)
        assertNull(synchronizer.stableServerTimeToLocalTimeMs(9_000L, nowMs = 1_250L))
    }

    @Test
    fun highRttOutlierIsVisibleAndNotAccepted() {
        val synchronizer = SendspinClockSynchronizer(config)

        val snapshot = synchronizer.recordServerTime(
            time("slow", 1_000L, 5_000L, 5_002L),
            localReceivedAtMs = 1_500L,
        )

        assertEquals(SendspinClockQuality.DEGRADED, snapshot.quality)
        assertEquals("high-rtt", snapshot.reason)
        assertEquals(498L, snapshot.rttMs)
        assertEquals(0, snapshot.sampleCount)
    }

    @Test
    fun staleSyncDisablesStableMapping() {
        val synchronizer = SendspinClockSynchronizer(config)

        synchronizer.recordServerTime(time("a", 1_000L, 5_000L, 5_002L), localReceivedAtMs = 1_050L)
        synchronizer.recordServerTime(time("b", 1_100L, 5_100L, 5_102L), localReceivedAtMs = 1_150L)
        synchronizer.recordServerTime(time("c", 1_200L, 5_200L, 5_202L), localReceivedAtMs = 1_250L)
        val stale = synchronizer.snapshot(nowMs = 2_500L)

        assertEquals(SendspinClockQuality.STALE, stale.quality)
        assertEquals("stale-sync", stale.reason)
        assertNull(synchronizer.stableServerTimeToLocalTimeMs(9_000L, nowMs = 2_500L))
    }

    @Test
    fun largeDiscontinuityResetsAcceptedSamples() {
        val synchronizer = SendspinClockSynchronizer(config)

        synchronizer.recordServerTime(time("a", 1_000L, 5_000L, 5_002L), localReceivedAtMs = 1_050L)
        synchronizer.recordServerTime(time("b", 1_100L, 5_100L, 5_102L), localReceivedAtMs = 1_150L)
        val reset = synchronizer.recordServerTime(
            time("jump", 1_200L, 7_200L, 7_202L),
            localReceivedAtMs = 1_250L,
        )

        assertEquals(SendspinClockQuality.RESET, reset.quality)
        assertEquals("offset-discontinuity", reset.reason)
        assertEquals(1, reset.resetCount)
        assertEquals(0, reset.sampleCount)
    }

    @Test
    fun invalidTimestampOrderingFailsLoudly() {
        val synchronizer = SendspinClockSynchronizer(config)

        assertProtocolError {
            synchronizer.recordServerTime(time("local", 1_000L, 5_000L, 5_002L), localReceivedAtMs = 999L)
        }
        assertProtocolError {
            synchronizer.recordServerTime(time("server", 1_000L, 5_010L, 5_002L), localReceivedAtMs = 1_050L)
        }
    }

    @Test
    fun offsetDeltaOverflowFailsLoudly() {
        val synchronizer = SendspinClockSynchronizer(
            config.copy(stableSampleCount = 1, maxDiscontinuityMs = Long.MAX_VALUE),
        )

        synchronizer.recordServerTime(
            time("positive", 0L, Long.MAX_VALUE, Long.MAX_VALUE),
            localReceivedAtMs = 0L,
        )

        assertProtocolError {
            synchronizer.recordServerTime(
                time("negative", Long.MAX_VALUE, 0L, 0L),
                localReceivedAtMs = Long.MAX_VALUE,
            )
        }
    }

    @Test
    fun stableMappingOverflowFailsLoudly() {
        val synchronizer = SendspinClockSynchronizer(config.copy(stableSampleCount = 1))

        synchronizer.recordServerTime(
            time("negative-offset", Long.MAX_VALUE, 0L, 0L),
            localReceivedAtMs = Long.MAX_VALUE,
        )

        assertProtocolError {
            synchronizer.stableServerTimeToLocalTimeMs(Long.MAX_VALUE, nowMs = Long.MAX_VALUE)
        }
    }

    @Test
    fun regressedLocalClockFailsLoudlyDuringSnapshotEvaluation() {
        val synchronizer = SendspinClockSynchronizer(config.copy(stableSampleCount = 1))

        synchronizer.recordServerTime(time("sample", 1_000L, 5_000L, 5_002L), localReceivedAtMs = 1_050L)

        assertProtocolError {
            synchronizer.snapshot(nowMs = 1_049L)
        }
    }

    private fun time(
        requestId: String,
        clientSentAtMs: Long,
        serverReceivedAtMs: Long,
        serverSentAtMs: Long,
    ): SendspinServerTime = SendspinServerTime(
        requestId = requestId,
        clientSentAtMs = clientSentAtMs,
        serverReceivedAtMs = serverReceivedAtMs,
        serverSentAtMs = serverSentAtMs,
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
