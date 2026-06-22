package dev.ztripez.massmate

import java.net.URI

/**
 * Native Sendspin connection status reported through local-player snapshots.
 *
 * @property bridgeValue String value serialized into Dart snapshot envelopes.
 */
enum class SendspinConnectionStatus(val bridgeValue: String) {
    /** No active Sendspin transport session is owned by the native local-player service. */
    DISCONNECTED("disconnected"),

    /** A WebSocket transport is opening or waiting for a valid server hello. */
    CONNECTING("connecting"),

    /** The client hello and server hello exchange passed version and required-role gates. */
    READY("ready"),

    /** The transport, endpoint, protocol, or role gate failed visibly. */
    FAILED("failed"),
}

/**
 * Single native representation of Sendspin connection state emitted to Flutter.
 *
 * @property generation Controller-owned ordering value for service-side stale post rejection.
 * @property status Typed lifecycle state.
 * @property connectionLabel User-visible connection label.
 * @property mediaTitle Placeholder title sent through the existing player snapshot bridge.
 * @property mediaSubtitle Placeholder subtitle sent through the existing player snapshot bridge.
 * @property timing Native clock synchronization diagnostics for local-player debug snapshots.
 * @property stream Native stream lifecycle and frame-buffer diagnostics for local-player snapshots.
 * @property error Failure code, message, and details for [SendspinConnectionStatus.FAILED].
 */
data class SendspinConnectionSnapshot(
    val generation: Long,
    val status: SendspinConnectionStatus,
    val connectionLabel: String,
    val mediaTitle: String,
    val mediaSubtitle: String,
    val timing: SendspinClockSnapshot = SendspinClockSnapshot.unsynchronized(),
    val stream: SendspinStreamBufferSnapshot = SendspinStreamBufferSnapshot.inactive(),
    val error: SendspinConnectionException? = null,
) {
    companion object {
        /** Creates the initial disconnected native connection state. */
        fun disconnected(generation: Long = 0L): SendspinConnectionSnapshot = SendspinConnectionSnapshot(
            generation = generation,
            status = SendspinConnectionStatus.DISCONNECTED,
            connectionLabel = "Native local player disconnected",
            mediaTitle = "Local player ready",
            mediaSubtitle = "Not connected",
        )

        /** Creates a connecting snapshot for [endpoint]. */
        fun connecting(generation: Long, endpoint: URI): SendspinConnectionSnapshot =
            SendspinConnectionSnapshot(
                generation = generation,
                status = SendspinConnectionStatus.CONNECTING,
                connectionLabel = "Connecting to Sendspin",
                mediaTitle = "Connecting local player",
                mediaSubtitle = endpoint.host,
            )

        /**
         * Creates a ready snapshot after a validated handshake.
         *
         * [timing] carries native clock synchronization diagnostics for debug snapshot consumers and
         * [stream] carries native stream-buffer diagnostics.
         */
        fun ready(
            generation: Long,
            timing: SendspinClockSnapshot = SendspinClockSnapshot.unsynchronized(),
            stream: SendspinStreamBufferSnapshot = SendspinStreamBufferSnapshot.inactive(),
        ): SendspinConnectionSnapshot = SendspinConnectionSnapshot(
            generation = generation,
            status = SendspinConnectionStatus.READY,
            connectionLabel = "Sendspin local player ready",
            mediaTitle = "Local player ready",
            mediaSubtitle = "Handshake complete",
            timing = timing,
            stream = stream,
        )

        /** Creates a failed snapshot carrying [error] details. */
        fun failed(
            generation: Long,
            error: SendspinConnectionException,
        ): SendspinConnectionSnapshot = SendspinConnectionSnapshot(
            generation = generation,
            status = SendspinConnectionStatus.FAILED,
            connectionLabel = "Sendspin local player failed",
            mediaTitle = "Local player failed",
            mediaSubtitle = error.message,
            error = error,
        )
    }
}

/** Serial executor used to keep transport callbacks and lifecycle requests ordered. */
fun interface SendspinConnectionQueue {
    /** Enqueues [task] on the controller-owned serial thread. */
    fun execute(task: () -> Unit)
}

/**
 * Callback receiving the platform-channel result for an enqueued lifecycle request.
 *
 * A `null` argument means the lifecycle request was accepted. A non-null
 * [SendspinConnectionException] means the connect/disconnect request failed and should be returned
 * as a typed bridge error.
 */
typealias SendspinLifecycleResult = (SendspinConnectionException?) -> Unit

/**
 * Callback receiving the result of a native-only Sendspin protocol send.
 *
 * A `null` argument means the protocol message was accepted by the active transport. A non-null
 * [SendspinConnectionException] is emitted through snapshots and returned to the native caller.
 */
typealias SendspinProtocolSendResult = (SendspinConnectionException?) -> Unit
