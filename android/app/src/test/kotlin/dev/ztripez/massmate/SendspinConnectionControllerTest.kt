package dev.ztripez.massmate

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendspinConnectionControllerTest {
    @Test
    fun endpointBuilderConvertsServerSettingsToWebSocketUrl() {
        val endpoint = SendspinEndpointBuilder.buildEndpoint(
            SendspinServerSettings(
                serverUrl = "https://music.example.local/api/",
                sendspinPath = "/sendspin",
            ),
        )

        assertEquals("wss://music.example.local/api/sendspin", endpoint.toString())
    }

    @Test
    fun handshakeSuccessMovesConnectionToReady() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
        )

        controller.connect(URI("ws://music.example.local/sendspin"))
        transport.opened()
        transport.receiveText(serverHello())

        assertEquals(
            listOf(SendspinConnectionStatus.CONNECTING, SendspinConnectionStatus.READY),
            snapshots.map { it.status },
        )
        assertTrue(transport.sentTexts.first().contains("client/hello"))
    }

    @Test
    fun roleMismatchFailsLoudly() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
        )

        controller.connect(URI("ws://music.example.local/sendspin"))
        transport.opened()
        transport.receiveText(serverHello(roles = listOf("audio", "controller", "state")))

        assertEquals(SendspinConnectionStatus.FAILED, snapshots.last().status)
        assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_ROLE_MISMATCH, snapshots.last().error?.code)
        assertEquals(true, snapshots.last().error?.details?.containsKey("missingRoles"))
    }

    @Test
    fun deliberateDisconnectSendsGoodbyeAfterClientHello() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
        )

        controller.connect(URI("ws://music.example.local/sendspin"))
        transport.opened()

        val error = controller.disconnect()

        assertEquals(null, error)
        assertTrue(transport.sentTexts.any { it.contains("client/goodbye") })
        assertEquals(true, transport.closed)
        assertEquals(SendspinConnectionStatus.DISCONNECTED, snapshots.last().status)
    }

    @Test
    fun reconnectClosesPreviousSessionAndIgnoresStaleCallbacks() {
        val transports = ArrayDeque<FakeSendspinTransport>()
        val first = FakeSendspinTransport()
        val second = FakeSendspinTransport()
        transports.add(first)
        transports.add(second)
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transports.removeFirst() },
            onSnapshot = snapshots::add,
        )

        controller.connect(URI("ws://music.example.local/sendspin"))
        first.opened()
        controller.connect(URI("ws://music.example.local/sendspin"))
        first.receiveText(serverHello())
        second.opened()
        second.receiveText(serverHello())

        assertTrue(first.sentTexts.any { it.contains("client/goodbye") })
        assertEquals(true, first.closed)
        assertEquals(SendspinConnectionStatus.READY, snapshots.last().status)
        assertEquals(1, snapshots.count { it.status == SendspinConnectionStatus.READY })
    }

    private fun serverHello(
        roles: List<String> = listOf("audio", "controller", "state", "time"),
    ): String {
        val roleJson = roles.joinToString(",") { role -> "\"$role\"" }
        return "{" +
            "\"type\":\"server/hello\"," +
            "\"protocolVersion\":1," +
            "\"activatedRoles\":[$roleJson]" +
            "}"
    }
}

private class FakeSendspinTransport : SendspinTransport {
    val sentTexts = mutableListOf<String>()
    var closed = false
    private var listener: SendspinTransport.Listener? = null

    override fun open(endpoint: URI, listener: SendspinTransport.Listener) {
        this.listener = listener
    }

    override fun sendText(text: String) {
        sentTexts.add(text)
    }

    override fun close(code: Int, reason: String?) {
        closed = true
    }

    fun opened() {
        listener?.onOpen()
    }

    fun receiveText(text: String) {
        listener?.onText(text)
    }
}
