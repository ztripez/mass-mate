package dev.ztripez.massmate

import java.util.logging.Logger

/** Receives native protocol diagnostics such as ignored unknown message types. */
interface SendspinProtocolLogger {
    /** Records [message] and optional structured [details] for native debugging. */
    fun warn(message: String, details: Map<String, Any?>? = null)
}

/** JVM/Android logger used outside focused parser tests. */
object JavaUtilSendspinProtocolLogger : SendspinProtocolLogger {
    private val logger = Logger.getLogger("dev.ztripez.massmate.sendspin.protocol")

    override fun warn(message: String, details: Map<String, Any?>?) {
        logger.warning(if (details == null) message else "$message $details")
    }
}

/**
 * Native-owner callback surface for parsed Sendspin protocol events.
 *
 * Implementations are native Android owners only; Flutter widgets must not consume raw Sendspin
 * message names or payloads. Later stream, timing, command, and snapshot owners may implement this
 * interface to claim supported families. Until those owners exist, production uses
 * [FailHardSendspinProtocolEvents] so unsupported known families fail visibly.
 */
interface SendspinProtocolEvents {
    /** Handoff for server playback/state [state]; direct Flutter UI consumption is forbidden. */
    fun onServerState(state: SendspinServerState)

    /** Handoff for server media [metadata]; native-to-Dart snapshot mapping is not performed here. */
    fun onMetadata(metadata: SendspinMetadata)

    /** Handoff for server time synchronization [time] responses. */
    fun onServerTime(time: SendspinServerTime)

    /** Handoff for validated stream [stream] start descriptors; buffering/audio are deferred. */
    fun onStreamStart(stream: SendspinStreamStart)

    /** Handoff for validated stream clear [stream] descriptors; buffer ownership is deferred. */
    fun onStreamClear(stream: SendspinStreamClear)

    /** Handoff for validated stream end [stream] descriptors; audio teardown is deferred. */
    fun onStreamEnd(stream: SendspinStreamEnd)

    /** Handoff for raw server [command] events; Flutter intent mapping is not performed here. */
    fun onServerCommand(command: SendspinServerCommand)

    /** Handoff for diagnostic server [status] messages that do not update UI state here. */
    fun onServerStatus(status: SendspinServerStatus)

    /** Handoff for parsed server [error] details before dispatch fails the active session. */
    fun onServerProtocolError(error: SendspinServerProtocolError)
}

/**
 * Production event sink for known families that have no native owner yet.
 *
 * @param logger Native logger used for diagnostics immediately before visible protocol failures.
 */
class FailHardSendspinProtocolEvents(
    private val logger: SendspinProtocolLogger,
) : SendspinProtocolEvents {
    override fun onServerState(state: SendspinServerState) {
        logger.warn("Received Sendspin server state before native snapshot ownership exists.")
        throw unsupportedFamily("server/state")
    }

    override fun onMetadata(metadata: SendspinMetadata) {
        logger.warn("Received Sendspin metadata before native snapshot ownership exists.")
        throw unsupportedFamily("server/metadata")
    }

    override fun onServerTime(time: SendspinServerTime) {
        throw unsupportedFamily("server/time")
    }

    override fun onStreamStart(stream: SendspinStreamStart) {
        throw unsupportedFamily("stream/start")
    }

    override fun onStreamClear(stream: SendspinStreamClear) {
        throw unsupportedFamily("stream/clear")
    }

    override fun onStreamEnd(stream: SendspinStreamEnd) {
        throw unsupportedFamily("stream/end")
    }

    override fun onServerCommand(command: SendspinServerCommand) {
        throw unsupportedFamily("server/command")
    }

    override fun onServerStatus(status: SendspinServerStatus) {
        logger.warn("Received Sendspin server status.", mapOf("status" to status.status))
        throw unsupportedFamily("server/status")
    }

    override fun onServerProtocolError(error: SendspinServerProtocolError) {
        logger.warn("Received Sendspin server protocol error.", mapOf("code" to error.code))
    }

    private fun unsupportedFamily(type: String): SendspinConnectionException =
        SendspinProtocolJson.protocolError(
            "Sendspin message family `$type` has no native owner in the Android local-player service.",
            mapOf("type" to type),
        )
}
