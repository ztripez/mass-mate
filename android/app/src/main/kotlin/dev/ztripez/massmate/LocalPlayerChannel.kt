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
import io.flutter.plugin.common.StandardMethodCodec

/** Registers Flutter platform channels for the native local-player service seam. */
class LocalPlayerChannel(private val context: Context) :
    MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler {
    private val appContext = context.applicationContext
    private val pendingServiceActions = mutableListOf<(LocalPlayerService) -> Unit>()

    private var service: LocalPlayerService? = null
    private var eventSink: EventChannel.EventSink? = null
    private var bindStarted = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as LocalPlayerService.LocalBinder).service
            service?.setSnapshotListener { snapshot -> eventSink?.success(snapshot) }

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
        withService { localPlayer ->
            localPlayer.setSnapshotListener { snapshot -> events.success(snapshot) }
        }
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
        service?.setSnapshotListener(null)
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
        if (!ensureServiceBinding()) {
            pendingServiceActions.remove(pendingAction)
            result.success(
                failedResult(
                    "LOCAL_PLAYER_UNAVAILABLE",
                    "Native local player service could not be bound.",
                ),
            )
        }
    }

    private fun withService(action: (LocalPlayerService) -> Unit) {
        val existingService = service
        if (existingService != null) {
            action(existingService)
            return
        }

        pendingServiceActions.add(action)
        if (!ensureServiceBinding()) pendingServiceActions.remove(action)
    }

    private fun ensureServiceBinding(): Boolean {
        if (bindStarted) return true

        val intent = Intent(appContext, LocalPlayerService::class.java)
        return try {
            appContext.startService(intent)
            bindStarted = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            bindStarted
        } catch (error: RuntimeException) {
            bindStarted = false
            false
        }
    }

    private fun failedResult(code: String, message: String): Map<String, Any?> {
        return mapOf(
            "accepted" to false,
            "error" to mapOf(
                "code" to code,
                "message" to message,
            ),
        )
    }

    companion object {
        private const val METHOD_CHANNEL = "mass_mate/local_player"
        private const val EVENT_CHANNEL = "mass_mate/local_player/snapshots"

        /** Registers method and event channels on [messenger]. */
        fun register(context: Context, messenger: BinaryMessenger) {
            val channel = LocalPlayerChannel(context)
            val taskQueue = messenger.makeBackgroundTaskQueue()
            MethodChannel(messenger, METHOD_CHANNEL, StandardMethodCodec.INSTANCE, taskQueue)
                .setMethodCallHandler(channel)
            EventChannel(messenger, EVENT_CHANNEL, StandardMethodCodec.INSTANCE, taskQueue)
                .setStreamHandler(channel)
        }
    }
}
