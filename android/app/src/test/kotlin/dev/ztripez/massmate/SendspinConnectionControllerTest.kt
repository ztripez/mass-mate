package dev.ztripez.massmate

import java.net.URI
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendspinConnectionControllerTest {
    @Test
    fun endpointBuilderConvertsAcceptedSchemesAndPaths() {
        assertEquals(
            "ws://music.example.local/sendspin",
            endpoint("http://music.example.local", "/sendspin"),
        )
        assertEquals(
            "wss://music.example.local/sendspin",
            endpoint("https://music.example.local", "/sendspin"),
        )
        assertEquals(
            "ws://music.example.local:8095/sendspin",
            endpoint("ws://music.example.local:8095", "/sendspin"),
        )
        assertEquals(
            "wss://music.example.local/api/sendspin",
            endpoint("wss://music.example.local/api/", "/sendspin"),
        )
        assertEquals(
            "wss://music.example.local/api/sendspin",
            SendspinEndpointBuilder.fromBridgeArguments(
                mapOf("serverUrl" to "https://music.example.local/api"),
            ).toString(),
        )
    }

    @Test
    fun endpointBuilderRejectsInvalidSettings() {
        assertConnectionError(LocalPlayerEnvelope.LOCAL_PLAYER_ENDPOINT_INVALID) {
            SendspinEndpointBuilder.fromBridgeArguments(null)
        }
        assertConnectionError(LocalPlayerEnvelope.LOCAL_PLAYER_ENDPOINT_INVALID) {
            SendspinEndpointBuilder.fromBridgeArguments(mapOf("serverUrl" to "   "))
        }
        assertConnectionError(LocalPlayerEnvelope.LOCAL_PLAYER_ENDPOINT_INVALID) {
            SendspinEndpointBuilder.buildEndpoint(SendspinServerSettings("ftp://music.example.local", "/sendspin"))
        }
        assertConnectionError(LocalPlayerEnvelope.LOCAL_PLAYER_ENDPOINT_INVALID) {
            SendspinEndpointBuilder.buildEndpoint(SendspinServerSettings("https:///sendspin", "/sendspin"))
        }
        assertConnectionError(LocalPlayerEnvelope.LOCAL_PLAYER_ENDPOINT_INVALID) {
            SendspinEndpointBuilder.buildEndpoint(SendspinServerSettings("https://music.example.local", "sendspin"))
        }
    }

    @Test
    fun clientHelloHasExactMinimalJsonShape() {
        val hello = JSONObject(SendspinProtocolHandshake.clientHello())

        assertEquals("client/hello", hello.getString("type"))
        assertEquals(1, hello.getInt("protocolVersion"))
        assertEquals("mass-mate-android", hello.getString("clientId"))
        assertEquals(
            listOf("audio", "controller", "state", "time"),
            (0 until hello.getJSONArray("roles").length()).map { index ->
                hello.getJSONArray("roles").getString(index)
            },
        )
        assertEquals(setOf("type", "protocolVersion", "clientId", "roles"), hello.keys().asSequence().toSet())
    }

    @Test
    fun serverHelloFailureCasesFailStrictly() {
        assertConnectionError(LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR) {
            SendspinProtocolHandshake.parseServerHello(serverHello(type = "server/state"))
        }
        assertConnectionError(LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR) {
            SendspinProtocolHandshake.parseServerHello(serverHello(protocolVersion = 2))
        }
        assertConnectionError(LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR) {
            SendspinProtocolHandshake.parseServerHello("{\"type\":\"server/hello\",\"protocolVersion\":1}")
        }
        assertConnectionError(LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR) {
            SendspinProtocolHandshake.parseServerHello("{not-json")
        }
        assertConnectionError(LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR) {
            SendspinProtocolHandshake.parseServerHello(
                "{\"type\":\"server/hello\",\"protocolVersion\":1,\"activatedRoles\":[\"audio\",7]}",
            )
        }
        assertConnectionError(LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR) {
            SendspinProtocolHandshake.parseServerHello(
                "{\"type\":\"server/hello\",\"protocolVersion\":1,\"activatedRoles\":{}}",
            )
        }
        assertConnectionError(LocalPlayerEnvelope.LOCAL_PLAYER_ROLE_MISMATCH) {
            val hello = SendspinProtocolHandshake.parseServerHello(
                serverHello(roles = listOf("audio", "controller", "state", "time", "browse")),
            )
            SendspinProtocolHandshake.validateServerHello(hello)
        }
        assertConnectionError(LocalPlayerEnvelope.LOCAL_PLAYER_ROLE_MISMATCH) {
            val hello = SendspinProtocolHandshake.parseServerHello(
                serverHello(roles = listOf("audio", "controller", "state")),
            )
            SendspinProtocolHandshake.validateServerHello(hello)
        }
    }

    @Test
    fun handshakeSuccessMovesConnectionToReady() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = controller(transport, snapshots)
        var connectError: SendspinConnectionException? = SendspinConnectionException("unset", "unset")

        controller.connect(URI("ws://music.example.local/sendspin")) { connectError = it }
        transport.opened()
        transport.receiveText(serverHello())

        assertEquals(null, connectError)
        assertEquals(
            listOf(SendspinConnectionStatus.CONNECTING, SendspinConnectionStatus.READY),
            snapshots.map { it.status },
        )
        assertTrue(transport.sentTexts.first().contains("client/hello"))
        assertTrue(snapshots[1].generation > snapshots[0].generation)
    }

    @Test
    fun controllerReportsTransportFailureAndPrematureClose() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = controller(transport, snapshots)

        controller.connect(URI("ws://music.example.local/sendspin"))
        transport.fail(IllegalStateException("boom"))

        assertEquals(SendspinConnectionStatus.FAILED, snapshots.last().status)
        assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_TRANSPORT_ERROR, snapshots.last().error?.code)
        assertEquals("boom", snapshots.last().error?.details?.get("message"))

        val second = FakeSendspinTransport()
        snapshots.clear()
        val secondController = controller(second, snapshots)
        secondController.connect(URI("ws://music.example.local/sendspin"))
        second.closedByPeer(1006, "network closed")

        assertEquals(SendspinConnectionStatus.FAILED, snapshots.last().status)
        assertEquals(1006, snapshots.last().error?.details?.get("code"))
        assertEquals("network closed", snapshots.last().error?.details?.get("reason"))
    }

    @Test
    fun controllerReportsHelloSendAndParseFailures() {
        val sendFailureTransport = FakeSendspinTransport(throwOnSendContaining = "client/hello")
        val sendFailureSnapshots = mutableListOf<SendspinConnectionSnapshot>()
        val sendFailureController = controller(sendFailureTransport, sendFailureSnapshots)

        sendFailureController.connect(URI("ws://music.example.local/sendspin"))
        sendFailureTransport.opened()

        assertEquals(SendspinConnectionStatus.FAILED, sendFailureSnapshots.last().status)
        assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_TRANSPORT_ERROR, sendFailureSnapshots.last().error?.code)
        assertNotNull(sendFailureSnapshots.last().error?.message)

        val parseFailureTransport = FakeSendspinTransport()
        val parseFailureSnapshots = mutableListOf<SendspinConnectionSnapshot>()
        val parseFailureController = controller(parseFailureTransport, parseFailureSnapshots)

        parseFailureController.connect(URI("ws://music.example.local/sendspin"))
        parseFailureTransport.opened()
        parseFailureTransport.receiveText(serverHello(protocolVersion = 99))

        assertFalse(parseFailureSnapshots.any { it.status == SendspinConnectionStatus.READY })
        assertEquals(SendspinConnectionStatus.FAILED, parseFailureSnapshots.last().status)
        assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR, parseFailureSnapshots.last().error?.code)
        assertEquals(99, parseFailureSnapshots.last().error?.details?.get("actual"))
    }

    @Test
    fun roleMismatchFailsLoudly() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = controller(transport, snapshots)

        controller.connect(URI("ws://music.example.local/sendspin"))
        transport.opened()
        transport.receiveText(serverHello(roles = listOf("audio", "controller", "state")))

        assertEquals(SendspinConnectionStatus.FAILED, snapshots.last().status)
        assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_ROLE_MISMATCH, snapshots.last().error?.code)
        assertEquals(true, snapshots.last().error?.details?.containsKey("missingRoles"))
    }

    @Test
    fun postReadyTextFailsUntilDispatcherExists() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = controller(transport, snapshots)

        controller.connect(URI("ws://music.example.local/sendspin"))
        transport.opened()
        transport.receiveText(serverHello())
        transport.receiveText("{\"type\":\"server/state\"}")

        assertEquals(SendspinConnectionStatus.FAILED, snapshots.last().status)
        assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR, snapshots.last().error?.code)
    }

    @Test
    fun deliberateDisconnectSendsExactGoodbyeAndNormalClose() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = controller(transport, snapshots)
        var disconnectError: SendspinConnectionException? = SendspinConnectionException("unset", "unset")

        controller.connect(URI("ws://music.example.local/sendspin"))
        transport.opened()
        controller.disconnect { disconnectError = it }

        assertEquals(null, disconnectError)
        assertTrue(transport.sentTexts.contains(SendspinProtocolHandshake.clientGoodbye()))
        assertEquals(SendspinTransport.NORMAL_CLOSURE, transport.closeCode)
        assertEquals(SendspinTransport.NORMAL_CLOSURE_REASON, transport.closeReason)
        assertEquals(SendspinConnectionStatus.DISCONNECTED, snapshots.last().status)
    }

    @Test
    fun reconnectFailsVisiblyWhenReplacementGoodbyeFails() {
        val first = FakeSendspinTransport(throwOnSendContaining = "client/goodbye")
        val second = FakeSendspinTransport()
        val transports = ArrayDeque(listOf(first, second))
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transports.removeFirst() },
            onSnapshot = snapshots::add,
        )
        var reconnectError: SendspinConnectionException? = null

        controller.connect(URI("ws://music.example.local/first"))
        first.opened()
        controller.connect(URI("ws://music.example.local/second")) { reconnectError = it }

        assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_TRANSPORT_ERROR, reconnectError?.code)
        assertEquals(SendspinConnectionStatus.FAILED, snapshots.last().status)
        assertEquals(null, second.endpoint)
    }

    @Test
    fun reconnectDeterminismIgnoresStaleCallbacksAndUsesSecondEndpoint() {
        val first = FakeSendspinTransport()
        val second = FakeSendspinTransport()
        val transports = ArrayDeque(listOf(first, second))
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transports.removeFirst() },
            onSnapshot = snapshots::add,
        )

        controller.connect(URI("ws://music.example.local/first"))
        controller.connect(URI("ws://music.example.local/second"))
        first.opened()
        first.receiveText(serverHello())
        first.fail(IllegalStateException("stale failure"))
        first.closedByPeer(1006, "stale close")
        second.opened()
        second.receiveText(serverHello())

        assertEquals(URI("ws://music.example.local/second"), second.endpoint)
        assertEquals(true, first.closed)
        assertFalse(first.sentTexts.contains(SendspinProtocolHandshake.clientGoodbye()))
        assertEquals(SendspinConnectionStatus.READY, snapshots.last().status)
        assertEquals(1, snapshots.count { it.status == SendspinConnectionStatus.READY })
    }

    private fun endpoint(serverUrl: String, path: String): String {
        return SendspinEndpointBuilder.buildEndpoint(SendspinServerSettings(serverUrl, path)).toString()
    }

    private fun controller(
        transport: FakeSendspinTransport,
        snapshots: MutableList<SendspinConnectionSnapshot>,
    ): SendspinConnectionController {
        return SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
        )
    }

    private fun serverHello(
        type: String = "server/hello",
        protocolVersion: Int = 1,
        roles: List<String> = listOf("audio", "controller", "state", "time"),
    ): String {
        return JSONObject()
            .put("type", type)
            .put("protocolVersion", protocolVersion)
            .put("activatedRoles", roles)
            .toString()
    }

    private fun assertConnectionError(
        expectedCode: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (error: SendspinConnectionException) {
            assertEquals(expectedCode, error.code)
            return
        }
        throw AssertionError("Expected SendspinConnectionException with code $expectedCode")
    }
}

/** Fake text transport for deterministic hello/goodbye and stale-callback tests. */
private class FakeSendspinTransport(
    private val throwOnSendContaining: String? = null,
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
