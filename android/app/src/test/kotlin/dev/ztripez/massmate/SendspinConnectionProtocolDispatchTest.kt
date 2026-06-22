package dev.ztripez.massmate

import java.net.URI
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendspinConnectionProtocolDispatchTest {
    @Test
    fun postReadyUnknownTextFailsVisibly() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val logger = CapturingProtocolLogger()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
            protocolLogger = logger,
        )

        connectReady(controller, transport)
        transport.receiveText("{\"type\":\"server/future\",\"value\":1}")

        assertProtocolFailure(snapshots)
        assertEquals(listOf("server/future"), logger.unknownTypes())
    }

    @Test
    fun postReadyInvalidKnownTextFailsVisibly() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
        )

        connectReady(controller, transport)
        transport.receiveText("{\"type\":\"stream/start\",\"codec\":\"pcm\",\"sampleRateHz\":48000,\"channels\":2}")

        assertProtocolFailure(snapshots)
        assertTrue(snapshots.last().error?.message.orEmpty().contains("streamId"))
    }

    @Test
    fun productionDispatchFailsKnownUnsupportedFamilies() {
        val stateSnapshots = readySnapshotsBefore("{\"type\":\"server/state\",\"playbackState\":\"playing\"}")
        val metadataSnapshots = readySnapshotsBefore("{\"type\":\"server/metadata\",\"title\":\"Track\"}")
        val streamSnapshots = readySnapshotsBefore(
            "{\"type\":\"stream/start\",\"streamId\":\"stream-1\",\"codec\":\"pcm\",\"sampleRateHz\":48000," +
                "\"channels\":2}",
        )
        val commandSnapshots = readySnapshotsBefore("{\"type\":\"server/command\",\"command\":\"pause\"}")
        val statusSnapshots = readySnapshotsBefore("{\"type\":\"server/status\",\"status\":\"ok\"}")

        assertProtocolFailure(stateSnapshots)
        assertTrue(stateSnapshots.last().error?.message.orEmpty().contains("server/state"))
        assertProtocolFailure(metadataSnapshots)
        assertTrue(metadataSnapshots.last().error?.message.orEmpty().contains("server/metadata"))
        assertProtocolFailure(streamSnapshots)
        assertTrue(streamSnapshots.last().error?.message.orEmpty().contains("stream/start"))
        assertProtocolFailure(commandSnapshots)
        assertTrue(commandSnapshots.last().error?.message.orEmpty().contains("server/command"))
        assertProtocolFailure(statusSnapshots)
        assertTrue(statusSnapshots.last().error?.message.orEmpty().contains("server/status"))
    }

    @Test
    fun serverErrorAlwaysFailsSession() {
        val events = CapturingProtocolEvents()
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
            protocolEvents = events,
        )

        connectReady(controller, transport)
        transport.receiveText(
            JSONObject()
                .put("type", "server/error")
                .put("code", "BAD_FRAME")
                .put("message", "bad frame")
                .toString(),
        )

        assertProtocolFailure(snapshots)
        assertEquals(SendspinServerProtocolError("BAD_FRAME", "bad frame"), events.protocolErrors.single())
    }

    @Test
    fun postReadyKnownTextRoutesTypedEventsWhenNativeOwnerExists() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val events = CapturingProtocolEvents()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
            protocolEvents = events,
        )

        connectReady(controller, transport)
        transport.receiveText(
            JSONObject()
                .put("type", "server/metadata")
                .put("title", "Captured Song")
                .put("artist", "Captured Artist")
                .toString(),
        )

        assertEquals(SendspinConnectionStatus.READY, snapshots.last().status)
        assertEquals(SendspinMetadata(title = "Captured Song", artist = "Captured Artist"), events.metadata.single())
    }

    @Test
    fun rawNativeClientCommandSendsOnlyWhenReady() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
        )
        var sendError: SendspinConnectionException? = SendspinConnectionException("unset", "unset")

        connectReady(controller, transport)
        controller.sendRawClientCommand(
            SendspinClientCommand(
                command = SendspinClientCommandKind.SEEK_TO,
                requestId = "request-1",
                positionMs = 42000L,
            ),
        ) { sendError = it }

        val command = JSONObject(transport.sentTexts.last())
        assertEquals(null, sendError)
        assertEquals(SendspinConnectionStatus.READY, snapshots.last().status)
        assertEquals("client/command", command.getString("type"))
        assertEquals("seekTo", command.getString("command"))
        assertEquals("request-1", command.getString("requestId"))
        assertEquals(42000L, command.getLong("positionMs"))
    }

    @Test
    fun rawNativeClientCommandFailsVisiblyBeforeReady() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
        )
        var sendError: SendspinConnectionException? = null

        controller.sendRawClientCommand(SendspinClientCommand(SendspinClientCommandKind.PAUSE)) {
            sendError = it
        }

        assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_NOT_CONNECTED, sendError?.code)
        assertEquals(SendspinConnectionStatus.FAILED, snapshots.last().status)
        assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_NOT_CONNECTED, snapshots.last().error?.code)
        assertEquals(true, transport.sentTexts.isEmpty())
    }

    @Test
    fun rawNativeClientCommandFailsVisiblyDuringActiveHandshakeBeforeReady() {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
        )
        var sendError: SendspinConnectionException? = null

        controller.connect(URI("ws://music.example.local/sendspin"))
        transport.opened()
        controller.sendRawClientCommand(SendspinClientCommand(SendspinClientCommandKind.PAUSE)) {
            sendError = it
        }

        assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_NOT_CONNECTED, sendError?.code)
        assertEquals(SendspinConnectionStatus.FAILED, snapshots.last().status)
        assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_NOT_CONNECTED, snapshots.last().error?.code)
        assertEquals(false, transport.sentTexts.any { JSONObject(it).getString("type") == "client/command" })
    }

    @Test
    fun rawNativeClientCommandSendFailureFailsSession() {
        val transport = FakeSendspinTransport(throwOnSendContaining = "client/command")
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
        )
        var sendError: SendspinConnectionException? = null

        connectReady(controller, transport)
        controller.sendRawClientCommand(SendspinClientCommand(SendspinClientCommandKind.PAUSE)) {
            sendError = it
        }

        assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_TRANSPORT_ERROR, sendError?.code)
        assertEquals(SendspinConnectionStatus.FAILED, snapshots.last().status)
        assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_TRANSPORT_ERROR, snapshots.last().error?.code)
        assertEquals(true, transport.closed)
    }

    private fun readySnapshotsBefore(text: String): List<SendspinConnectionSnapshot> {
        val transport = FakeSendspinTransport()
        val snapshots = mutableListOf<SendspinConnectionSnapshot>()
        val controller = SendspinConnectionController(
            transportFactory = SendspinTransportFactory { transport },
            onSnapshot = snapshots::add,
        )
        connectReady(controller, transport)
        transport.receiveText(text)
        return snapshots
    }

    private fun connectReady(controller: SendspinConnectionController, transport: FakeSendspinTransport) {
        controller.connect(URI("ws://music.example.local/sendspin"))
        transport.opened()
        transport.receiveText(serverHello())
    }

    private fun serverHello(): String = JSONObject()
        .put("type", "server/hello")
        .put("protocolVersion", 1)
        .put("activatedRoles", listOf("audio", "controller", "state", "time"))
        .toString()

    private fun assertProtocolFailure(snapshots: List<SendspinConnectionSnapshot>) {
        assertEquals(SendspinConnectionStatus.FAILED, snapshots.last().status)
        assertEquals(LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR, snapshots.last().error?.code)
    }
}

private class CapturingProtocolEvents : SendspinProtocolEvents {
    val metadata = mutableListOf<SendspinMetadata>()
    val protocolErrors = mutableListOf<SendspinServerProtocolError>()

    override fun onServerState(state: SendspinServerState) = Unit

    override fun onMetadata(metadata: SendspinMetadata) {
        this.metadata.add(metadata)
    }

    override fun onStreamStart(stream: SendspinStreamStart) = Unit

    override fun onStreamClear(stream: SendspinStreamClear) = Unit

    override fun onStreamEnd(stream: SendspinStreamEnd) = Unit

    override fun onServerCommand(command: SendspinServerCommand) = Unit

    override fun onServerStatus(status: SendspinServerStatus) = Unit

    override fun onServerProtocolError(error: SendspinServerProtocolError) {
        protocolErrors.add(error)
    }
}

private class CapturingProtocolLogger : SendspinProtocolLogger {
    private val warnings = mutableListOf<Map<String, Any?>?>()

    override fun warn(message: String, details: Map<String, Any?>?) {
        warnings.add(details)
    }

    fun unknownTypes(): List<String> = warnings.mapNotNull { it?.get("type") as? String }
}
