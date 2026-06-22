package dev.ztripez.massmate

/**
 * Protocol event adapter that keeps `server/time` controller-owned and delegates other families.
 *
 * `server/time` is first offered to [delegate] when present. If the delegate throws, controller
 * timing is not updated and the caller sees the same visible protocol failure path as other event
 * failures. If the delegate accepts or is absent, [handleServerTime] updates controller-owned
 * timing state.
 *
 * @param delegate Optional native event owner used by focused tests or future feature owners.
 * @param failHardEvents Production fail-hard owner for supported families that are not implemented.
 * @param handleServerTime Controller-owned handler for validated timing responses.
 */
class SendspinControllerProtocolEvents(
    private val delegate: SendspinProtocolEvents?,
    private val failHardEvents: SendspinProtocolEvents,
    private val handleServerTime: (SendspinServerTime) -> Unit,
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
        (delegate ?: failHardEvents).onStreamStart(stream)
    }

    override fun onStreamClear(stream: SendspinStreamClear) {
        (delegate ?: failHardEvents).onStreamClear(stream)
    }

    override fun onStreamEnd(stream: SendspinStreamEnd) {
        (delegate ?: failHardEvents).onStreamEnd(stream)
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
