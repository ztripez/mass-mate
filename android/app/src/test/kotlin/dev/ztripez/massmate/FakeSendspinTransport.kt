package dev.ztripez.massmate

import java.net.URI

/** Fake text transport for deterministic hello/goodbye, dispatch, and stale-callback tests. */
class FakeSendspinTransport(
    private val throwOnSendContaining: String? = null,
    private val throwOnClose: Boolean = false,
) : SendspinTransport {
    val sentTexts = mutableListOf<String>()
    var endpoint: URI? = null
    var closed = false
    var closeCode: Int? = null
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

    fun opened() {
        listener?.onOpen()
    }

    fun receiveText(text: String) {
        listener?.onText(text)
    }

    fun fail(error: Throwable) {
        listener?.onFailure(error)
    }

    fun closedByPeer(code: Int, reason: String) {
        listener?.onClosed(code, reason)
    }
}
