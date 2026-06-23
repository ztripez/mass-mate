package dev.ztripez.massmate

/**
 * Protocol event adapter that keeps `server/time` and stream lifecycle events controller-owned.
 *
 * `server/time` is first offered to [delegate] when present. If the delegate throws, controller
 * timing is not updated and the caller sees the same visible protocol failure path as other event
 * failures. If the delegate accepts or is absent, [handleServerTime] updates controller-owned
 * timing state. Stream lifecycle events follow the same delegate-first rule before updating the
 * controller-owned stream buffer.
 *
 * @param delegate Optional native event owner used by focused tests or future feature owners.
 * @param failHardEvents Production fail-hard owner for supported families that are not implemented.
 * @param handleServerTime Controller-owned handler for validated timing responses.
 * @param handleStreamStart Controller-owned handler for validated stream start descriptors.
 * @param handleStreamClear Controller-owned handler for validated stream clear descriptors.
 * @param handleStreamEnd Controller-owned handler for validated stream end descriptors.
 */
class SendspinControllerProtocolEvents(
    private val delegate: SendspinProtocolEvents?,
    private val failHardEvents: SendspinProtocolEvents,
    private val handleServerTime: (SendspinServerTime) -> Unit,
    private val handleStreamStart: (SendspinStreamStart) -> Unit,
    private val handleStreamClear: (SendspinStreamClear) -> Unit,
    private val handleStreamEnd: (SendspinStreamEnd) -> Unit,
) : SendspinProtocolEvents {
    override fun onServerState(state: SendspinServerState) {
        (delegate ?: failHardEvents).onServerState(state)
    }

    override fun onMetadata(metadata: SendspinMetadata) {
        (delegate ?: failHardEvents).onMetadata(metadata)
    }

    override fun onServerTime(time: SendspinServerTime) {
        delegate?.onServerTime(time)
        handleServerTime(time)
    }

    override fun onStreamStart(stream: SendspinStreamStart) {
        delegate?.onStreamStart(stream)
        handleStreamStart(stream)
    }

    override fun onStreamClear(stream: SendspinStreamClear) {
        delegate?.onStreamClear(stream)
        handleStreamClear(stream)
    }

    override fun onStreamEnd(stream: SendspinStreamEnd) {
        delegate?.onStreamEnd(stream)
        handleStreamEnd(stream)
    }

    override fun onServerCommand(command: SendspinServerCommand) {
        (delegate ?: failHardEvents).onServerCommand(command)
    }

    override fun onServerStatus(status: SendspinServerStatus) {
        (delegate ?: failHardEvents).onServerStatus(status)
    }

    override fun onServerProtocolError(error: SendspinServerProtocolError) {
        (delegate ?: failHardEvents).onServerProtocolError(error)
    }
}
