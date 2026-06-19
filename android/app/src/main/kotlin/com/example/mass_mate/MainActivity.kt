package com.example.mass_mate

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, HAPTICS_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "boundaryBuzz" -> {
                        boundaryBuzz()
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun boundaryBuzz() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (!vibrator.hasVibrator()) return

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
    }

    private companion object {
        const val HAPTICS_CHANNEL = "mass_mate/haptics"
    }
}
