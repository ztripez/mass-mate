package dev.ztripez.massmate

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Flutter platform-channel owner for the native local-player service seam.
 *
 * The method channel is `mass_mate/local_player` and accepts `connect`, `disconnect`, and
 * `sendCommand` calls. Method results use the envelope
 * `{ accepted: Boolean, error?: { code: String, message: String, details?: Any } }` so Dart can
 * surface rejected lifecycle or command requests without silently switching to a demo backend.
 * `sendCommand` receives intent-level Mass Mate command envelopes, not transport/protocol command
 * names.
 *
 * The event channel is `mass_mate/local_player/snapshots` and streams snapshot envelopes produced
 * by [LocalPlayerService]. Subscribing to the event channel observes the route-independent service;
 * it does not define whether the player is connected. All channel and service-connection mutable
 * state is handled on Android's main thread because this skeleton does no blocking work.
 */
class LocalPlayerChannel private constructor(
    private val context: Context,
    private val methodChannel: MethodChannel,
    private val eventChannel: EventChannel,
) : MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler {
    private val appContext = context.applicationContext
    private val pendingServiceActions = mutableListOf<(LocalPlayerService) -> Unit>()

    private var service: LocalPlayerService? = null
    private var eventSink: EventChannel.EventSink? = null
    private var pendingListenAction: ((LocalPlayerService) -> Unit)? = null
    private var bindStarted = false
    private var disposed = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as LocalPlayerService.LocalBinder).service

            val activeSink = eventSink
            if (activeSink != null) {
                service?.setSnapshotListener { snapshot ->
                    if (eventSink === activeSink) activeSink.success(snapshot)
                }
            }

            val actions = pendingServiceActions.toList()
            pendingServiceActions.clear()
            actions.forEach { action -> service?.let(action) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bindStarted = false
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (disposed) {
            result.success(
                failedResult(
                    LOCAL_PLAYER_UNAVAILABLE,
                    "Native local player channel has been disposed.",
                ),
            )
            return
        }

        when (call.method) {
            "connect" -> withService(result) { localPlayer -> localPlayer.connect() }
            "disconnect" -> withService(result) { localPlayer -> localPlayer.disconnect() }
            "sendCommand" -> withService(result) { localPlayer ->
                localPlayer.sendCommand(call.arguments as? Map<*, *>)
            }
            else -> result.notImplemented()
        }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        eventSink = events

        val existingService = service
        if (existingService != null) {
            installSnapshotListener(existingService, events)
            return
        }

        val listenAction: (LocalPlayerService) -> Unit = { localPlayer ->
            if (eventSink === events) installSnapshotListener(localPlayer, events)
        }
        pendingListenAction = listenAction
        pendingServiceActions.add(listenAction)

        val failure = ensureServiceBinding()
        if (failure != null) {
            pendingServiceActions.remove(listenAction)
            pendingListenAction = null
            if (eventSink === events) events.error(failure.code, failure.message, failure.details)
        }
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
        pendingListenAction?.let(pendingServiceActions::remove)
        pendingListenAction = null
        service?.setSnapshotListener(null)
    }

    /** Releases channel handlers, pending event listeners, and the service binding. */
    fun dispose() {
        disposed = true
        eventSink = null
        pendingServiceActions.clear()
        pendingListenAction = null
        service?.setSnapshotListener(null)
        service = null

        if (bindStarted) {
            try {
                appContext.unbindService(connection)
            } catch (_: IllegalArgumentException) {
                // The service was already unbound by the Android runtime; disposal remains complete.
            }
            bindStarted = false
        }

        appContext.stopService(Intent(appContext, LocalPlayerService::class.java))
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
    }

    private fun withService(
        result: MethodChannel.Result,
        action: (LocalPlayerService) -> Map<String, Any?>,
    ) {
        val existingService = service
        if (existingService != null) {
            result.success(action(existingService))
            return
        }

        val pendingAction: (LocalPlayerService) -> Unit = { localPlayer ->
            result.success(action(localPlayer))
        }
        pendingServiceActions.add(pendingAction)
        val failure = ensureServiceBinding()
        if (failure != null) {
            pendingServiceActions.remove(pendingAction)
            result.success(failedResult(failure.code, failure.message, failure.details))
        }
    }

    private fun installSnapshotListener(
        localPlayer: LocalPlayerService,
        events: EventChannel.EventSink,
    ) {
        localPlayer.setSnapshotListener { snapshot ->
            if (eventSink === events) events.success(snapshot)
        }
    }

    private fun ensureServiceBinding(): BindingFailure? {
        if (bindStarted) return null

        val intent = Intent(appContext, LocalPlayerService::class.java)
        return try {
            appContext.startService(intent)
            bindStarted = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (bindStarted) {
                null
            } else {
                BindingFailure(
                    LOCAL_PLAYER_UNAVAILABLE,
                    "Native local player service could not be bound.",
                    mapOf("reason" to "bindService returned false"),
                )
            }
        } catch (error: RuntimeException) {
            bindStarted = false
            BindingFailure(
                LOCAL_PLAYER_UNAVAILABLE,
                "Native local player service could not be bound.",
                mapOf(
                    "exception" to error::class.java.name,
                    "message" to error.message,
                ),
            )
        }
    }

    private fun failedResult(
        code: String,
        message: String,
        details: Map<String, Any?>? = null,
    ): Map<String, Any?> {
        return mapOf(
            "accepted" to false,
            "error" to mapOf(
                "code" to code,
                "message" to message,
                "details" to details,
            ),
        )
    }

    companion object {
        private const val METHOD_CHANNEL = "mass_mate/local_player"
        private const val EVENT_CHANNEL = "mass_mate/local_player/snapshots"
        private const val LOCAL_PLAYER_UNAVAILABLE = "LOCAL_PLAYER_UNAVAILABLE"

        /** Registers local-player method and event channels on [messenger]. */
        fun register(context: Context, messenger: BinaryMessenger): LocalPlayerChannel {
            val methodChannel = MethodChannel(messenger, METHOD_CHANNEL)
            val eventChannel = EventChannel(messenger, EVENT_CHANNEL)
            val channel = LocalPlayerChannel(context, methodChannel, eventChannel)
            methodChannel.setMethodCallHandler(channel)
            eventChannel.setStreamHandler(channel)
            return channel
        }
    }
}

private data class BindingFailure(
    val code: String,
    val message: String,
    val details: Map<String, Any?>?,
)
