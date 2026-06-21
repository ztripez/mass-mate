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
    private val pendingMethodActions = mutableListOf<PendingMethodAction>()

    private var service: LocalPlayerService? = null
    private var eventSink: EventChannel.EventSink? = null
    private var bindingRegistered = false
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

            val actions = pendingMethodActions.toList()
            pendingMethodActions.clear()
            actions.forEach { pending -> service?.let(pending::completeWith) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null

            val failure = BindingFailure(
                LocalPlayerEnvelope.LOCAL_PLAYER_UNAVAILABLE,
                "Native local player service disconnected unexpectedly.",
                mapOf("component" to name?.flattenToShortString()),
            )
            val pending = pendingMethodActions.toList()
            pendingMethodActions.clear()
            pending.forEach { action -> action.completeFailure(failure) }
            eventSink?.error(failure.code, failure.message, failure.details)

            if (bindingRegistered) {
                appContext.unbindService(this)
                bindingRegistered = false
            }
            appContext.stopService(Intent(appContext, LocalPlayerService::class.java))
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (disposed) {
            result.success(
                failedResult(
                    LocalPlayerEnvelope.LOCAL_PLAYER_UNAVAILABLE,
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

        val failure = ensureServiceBinding()
        if (failure != null) {
            if (eventSink === events) events.error(failure.code, failure.message, failure.details)
        }
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
        service?.setSnapshotListener(null)
    }

    /** Releases channel handlers, pending event listeners, and the service binding. */
    fun dispose() {
        disposed = true
        eventSink = null
        val pending = pendingMethodActions.toList()
        pendingMethodActions.clear()
        pending.forEach { action ->
            action.completeFailure(
                BindingFailure(
                    LocalPlayerEnvelope.LOCAL_PLAYER_UNAVAILABLE,
                    "Native local player channel was disposed before service binding completed.",
                    mapOf("reason" to "channel disposed"),
                ),
            )
        }
        service?.setSnapshotListener(null)
        service = null

        if (bindingRegistered) {
            appContext.unbindService(connection)
            bindingRegistered = false
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

        val pendingMethodAction = PendingMethodAction(result, action)
        pendingMethodActions.add(pendingMethodAction)
        val failure = ensureServiceBinding()
        if (failure != null) {
            pendingMethodActions.remove(pendingMethodAction)
            pendingMethodAction.completeFailure(failure)
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
        if (bindingRegistered) return null

        val intent = Intent(appContext, LocalPlayerService::class.java)
        return try {
            appContext.startService(intent)
            bindingRegistered = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (bindingRegistered) {
                null
            } else {
                appContext.stopService(intent)
                bindingRegistered = false
                BindingFailure(
                    LocalPlayerEnvelope.LOCAL_PLAYER_UNAVAILABLE,
                    LocalPlayerEnvelope.BIND_FAILED_MESSAGE,
                    mapOf("reason" to "bindService returned false"),
                )
            }
        } catch (error: RuntimeException) {
            bindingRegistered = false
            appContext.stopService(intent)
            BindingFailure(
                LocalPlayerEnvelope.LOCAL_PLAYER_UNAVAILABLE,
                LocalPlayerEnvelope.BIND_FAILED_MESSAGE,
                mapOf(
                    "exception" to error.javaClass.name,
                    "message" to error.message,
                ),
            )
        }
    }

    private fun failedResult(
        code: String,
        message: String,
        details: Map<String, Any?>? = null,
    ): Map<String, Any?> = LocalPlayerEnvelope.failedResult(code, message, details)

    companion object {
        private const val METHOD_CHANNEL = "mass_mate/local_player"
        private const val EVENT_CHANNEL = "mass_mate/local_player/snapshots"

        /**
         * Registers local-player method and event channels on [messenger].
         *
         * [context] is used to bind the route-independent [LocalPlayerService]. [messenger]
         * receives the `mass_mate/local_player` method channel and
         * `mass_mate/local_player/snapshots` event channel. The returned [LocalPlayerChannel]
         * owns those handlers and the Android service binding; callers must invoke [dispose]
         * when the Flutter engine detaches.
         */
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

private data class PendingMethodAction(
    val result: MethodChannel.Result,
    val action: (LocalPlayerService) -> Map<String, Any?>,
) {
    fun completeWith(service: LocalPlayerService) {
        result.success(action(service))
    }

    fun completeFailure(failure: BindingFailure) {
        result.success(
            LocalPlayerEnvelope.failedResult(
                failure.code,
                failure.message,
                failure.details,
            ),
        )
    }
}
