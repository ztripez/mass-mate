package dev.ztripez.massmate

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMethodCodec

/**
 * Android host activity that registers native platform channels for Mass Mate.
 *
 * The `mass_mate/haptics` channel handles click-wheel boundary haptics. The local-player
 * registration installs `mass_mate/local_player` and `mass_mate/local_player/snapshots` channel
 * handlers that bind the route-independent [LocalPlayerService]. Activity cleanup disposes that
 * channel owner so service bindings do not leak after the Flutter engine detaches.
 */
class MainActivity : FlutterActivity() {
    private var localPlayerChannel: LocalPlayerChannel? = null

    /**
     * Registers platform method handlers after the Flutter engine attaches to the activity.
     *
     * The haptics channel uses a background task queue so synchronous vibrator service calls do
     * not run on Android's main thread during active touch interaction. The local-player channel
     * intentionally uses main-thread handlers because this skeleton performs no blocking work and
     * must serialize channel state with Android service-connection callbacks.
     */
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        val messenger = flutterEngine.dartExecutor.binaryMessenger
        val hapticsTaskQueue = messenger.makeBackgroundTaskQueue()
        MethodChannel(messenger, HAPTICS_CHANNEL, StandardMethodCodec.INSTANCE, hapticsTaskQueue)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "boundaryBuzz" -> {
                        try {
                            if (boundaryBuzz()) {
                                result.success(null)
                            } else {
                                result.error(
                                    "HAPTICS_UNAVAILABLE",
                                    "Device does not report a vibrator for click-wheel boundary feedback.",
                                    null,
                                )
                            }
                        } catch (error: RuntimeException) {
                            result.error(
                                "HAPTICS_FAILED",
                                error.message ?: "Failed to vibrate click-wheel boundary feedback.",
                                null,
                            )
                        }
                    }
                    else -> result.notImplemented()
                }
            }

        localPlayerChannel = LocalPlayerChannel.register(this, messenger)
    }

    /** Releases route-independent platform channels when the Flutter engine detaches. */
    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        localPlayerChannel?.dispose()
        localPlayerChannel = null
        super.cleanUpFlutterEngine(flutterEngine)
    }

    private fun boundaryBuzz(): Boolean {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (!vibrator.hasVibrator()) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 70, 110, 70),
                    intArrayOf(0, 255, 0, 255),
                    -1,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 70, 110, 70), -1)
        }

        return true
    }

    private companion object {
        const val HAPTICS_CHANNEL = "mass_mate/haptics"
    }
}
