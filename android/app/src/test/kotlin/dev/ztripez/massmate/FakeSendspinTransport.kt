package dev.ztripez.massmate

import java.net.URI

/**
 * Fake text transport for deterministic hello/goodbye, dispatch, and stale-callback tests.
 *
 * @param throwOnSendContaining Optional text fragment that makes [sendText] throw when matched.
 * @param throwOnClose Whether [close] should throw instead of recording close state.
 */
class FakeSendspinTransport(
    private val throwOnSendContaining: String? = null,
    private val throwOnClose: Boolean = false,
) : SendspinTransport {
    /** Text frames accepted by [sendText], in send order, for protocol shape assertions. */
    val sentTexts = mutableListOf<String>()

    /** Endpoint passed to [open], or `null` when the fake was never opened. */
    var endpoint: URI? = null

    /** Whether [close] was called without a configured close failure. */
    var closed = false

    /** WebSocket close code passed to [close], or `null` before close. */
    var closeCode: Int? = null

    /** WebSocket close reason passed to [close], or `null` before close. */
    var closeReason: String? = null
    private var listener: SendspinTransport.Listener? = null

    override fun open(endpoint: URI, listener: SendspinTransport.Listener) {
        this.endpoint = endpoint
        this.listener = listener
    }

    override fun sendText(text: String) {
        if (throwOnSendContaining != null && text.contains(throwOnSendContaining)) {
            throw IllegalStateException("send refused for $throwOnSendContaining")
        }
        sentTexts.add(text)
    }

    override fun close(code: Int, reason: String?) {
        if (throwOnClose) throw IllegalStateException("close refused")
        closed = true
        closeCode = code
        closeReason = reason
    }

    /** Emits an `onOpen` callback to the controller listener registered by [open]. */
    fun opened() {
        listener?.onOpen()
    }

    /** Emits an incoming text [text] frame to the registered controller listener. */
    fun receiveText(text: String) {
        listener?.onText(text)
    }

    /** Emits a transport [error] callback to the registered controller listener. */
    fun fail(error: Throwable) {
        listener?.onFailure(error)
    }

    /** Emits a peer close callback with WebSocket [code] and [reason]. */
    fun closedByPeer(code: Int, reason: String) {
        listener?.onClosed(code, reason)
    }
}
