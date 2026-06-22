package dev.ztripez.massmate

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper

/**
 * Route-independent Android owner for the Mass Mate native local-player backend.
 *
 * The service is bound by [LocalPlayerChannel] and outlives Flutter route rebuilds. It owns the
 * Sendspin connection lifecycle boundary while Flutter widgets continue to send only intent-level
 * playback operations through the Dart adapter seam. Transport open, hello/goodbye, deterministic
 * reconnect, and connection-state aggregation run on a dedicated serial background thread so
 * MethodChannel calls never block Android's main thread on transport locks or callbacks. The
 * service intentionally does not implement audio output, stream buffering, browse, or
 * intent-to-Sendspin controller command mapping. Clock synchronization is native-only timing
 * diagnostics for later audio scheduling and is not Flutter UI state mapping.
 */
class LocalPlayerService : Service() {
    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val controllerThread = HandlerThread("MassMateSendspinConnection")

    private lateinit var controllerHandler: Handler
    private lateinit var controller: SendspinConnectionController

    private var snapshotListener: ((Map<String, Any?>) -> Unit)? = null
    private var currentSnapshot = SendspinConnectionSnapshot.disconnected()

    /** Binder exposing the service instance to the platform-channel registrar. */
    inner class LocalBinder : Binder() {
        /** Bound [LocalPlayerService] instance used by [LocalPlayerChannel] to call service APIs. */
        val service: LocalPlayerService
            get() = this@LocalPlayerService
    }

    override fun onCreate() {
        super.onCreate()
        controllerThread.start()
        controllerHandler = Handler(controllerThread.looper)
        controller = SendspinConnectionController(
            transportFactory = OkHttpWebSocketSendspinTransportFactory(),
            onSnapshot = ::handleControllerSnapshot,
            queue = SendspinConnectionQueue { task -> controllerHandler.post(task) },
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * Registers a listener for typed local-player snapshot envelopes.
     *
     * The listener immediately receives the current snapshot map and then receives later snapshots
     * caused by [connect], [disconnect], or [sendCommand]. Passing `null` clears the listener.
     * Failed snapshots include the typed error emitted by the native connection controller.
     */
    fun setSnapshotListener(listener: ((Map<String, Any?>) -> Unit)?) {
        snapshotListener = listener
        listener?.invoke(currentSnapshotEnvelope())
    }

    /**
     * Asynchronously requests a local-player connection to configured Sendspin server settings.
     *
     * [arguments] must contain `serverUrl` and may contain `sendspinPath`; invalid or missing
     * values complete with `{ accepted: false, error: { code: LOCAL_PLAYER_ENDPOINT_INVALID, ... } }`
     * and emit a failed snapshot. Valid settings enqueue transport open and hello handling on the
     * service's serial connection thread, then complete with `{ accepted: true }` once the WebSocket
     * open request is started. Later handshake success/failure is reported through snapshots.
     */
    fun connect(arguments: Map<*, *>?, complete: (Map<String, Any?>) -> Unit) {
        val endpoint = try {
            SendspinEndpointBuilder.fromBridgeArguments(arguments)
        } catch (error: SendspinConnectionException) {
            applyLocalFailure(error)
            complete(LocalPlayerEnvelope.failedResult(error.code, error.message, error.details))
            return
        }

        controller.connect(endpoint) { error ->
            completeOnMain(complete, error.toLifecycleResult())
        }
    }

    /**
     * Asynchronously requests an explicit local-player disconnect.
     *
     * The controller sends `client/goodbye` when a client hello has already been sent, closes the
     * transport with normal WebSocket closure, emits a disconnected snapshot on success, and
     * completes with `{ accepted: true }`. Goodbye or close failures complete with
     * `{ accepted: false, error: { code: LOCAL_PLAYER_TRANSPORT_ERROR, ... } }` and emit a failed
     * snapshot. Flutter route unmounts do not call this method.
     */
    fun disconnect(complete: (Map<String, Any?>) -> Unit) {
        controller.disconnect { error ->
            completeOnMain(complete, error.toLifecycleResult())
        }
    }

    /**
     * Sends an intent-level Mass Mate playback command envelope to the native backend seam.
     *
     * Invalid envelopes return `LOCAL_PLAYER_INVALID_ENVELOPE`. Valid commands require
     * [SendspinConnectionStatus.READY]; otherwise the service emits and returns
     * `LOCAL_PLAYER_NOT_CONNECTED`. Because Flutter command dispatch is not implemented yet,
     * ready-state commands return `LOCAL_PLAYER_REJECTED` instead of pretending success.
     */
    fun sendCommand(envelope: Map<*, *>?): Map<String, Any?> {
        if (envelope?.get("command") !is String) {
            return LocalPlayerEnvelope.failedResult(
                LocalPlayerEnvelope.LOCAL_PLAYER_INVALID_ENVELOPE,
                LocalPlayerEnvelope.INVALID_COMMAND_ENVELOPE_MESSAGE,
            )
        }

        if (currentSnapshot.status != SendspinConnectionStatus.READY) {
            val error = SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_NOT_CONNECTED,
                LocalPlayerEnvelope.NOT_CONNECTED_MESSAGE,
            )
            applyLocalFailure(error)
            return LocalPlayerEnvelope.failedResult(error.code, error.message, error.details)
        }

        return LocalPlayerEnvelope.failedResult(
            LocalPlayerEnvelope.LOCAL_PLAYER_REJECTED,
            LocalPlayerEnvelope.COMMAND_DISPATCH_DEFERRED_MESSAGE,
        )
    }

    override fun onDestroy() {
        if (::controller.isInitialized) {
            controller.disconnect()
        }
        controllerThread.quitSafely()
        super.onDestroy()
    }

    private fun handleControllerSnapshot(snapshot: SendspinConnectionSnapshot) {
        mainHandler.post { applyControllerSnapshot(snapshot) }
    }

    private fun applyControllerSnapshot(snapshot: SendspinConnectionSnapshot) {
        if (!LocalPlayerSnapshotOrdering.shouldApplyControllerSnapshot(currentSnapshot, snapshot)) return
        currentSnapshot = snapshot
        emitSnapshot()
    }

    private fun applyLocalFailure(error: SendspinConnectionException) {
        currentSnapshot = LocalPlayerSnapshotOrdering.localFailure(currentSnapshot, error)
        emitSnapshot()
    }

    private fun emitSnapshot() {
        snapshotListener?.invoke(currentSnapshotEnvelope())
    }

    private fun currentSnapshotEnvelope(): Map<String, Any?> = LocalPlayerEnvelope.snapshot(currentSnapshot)

    private fun completeOnMain(
        complete: (Map<String, Any?>) -> Unit,
        result: Map<String, Any?>,
    ) {
        mainHandler.post { complete(result) }
    }

    private fun SendspinConnectionException?.toLifecycleResult(): Map<String, Any?> {
        val error = this ?: return LocalPlayerEnvelope.acceptedResult()
        return LocalPlayerEnvelope.failedResult(error.code, error.message, error.details)
    }
}

/** Keeps service-local failures from advancing controller-owned snapshot generations. */
object LocalPlayerSnapshotOrdering {
    /** Returns whether controller-produced [incoming] may replace [current].
     *
     * Equal generations are accepted so controller snapshots can replace service-local failures that
     * preserved the current generation instead of consuming a future controller generation.
     */
    fun shouldApplyControllerSnapshot(
        current: SendspinConnectionSnapshot,
        incoming: SendspinConnectionSnapshot,
    ): Boolean = incoming.generation >= current.generation

    /** Creates a visible local failure without consuming a future controller generation. */
    fun localFailure(
        current: SendspinConnectionSnapshot,
        error: SendspinConnectionException,
    ): SendspinConnectionSnapshot = SendspinConnectionSnapshot.failed(current.generation, error)
}
