import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

const MethodChannel _hapticsChannel = MethodChannel('mass_mate/haptics');

/// Native haptic bridge for click-wheel feedback that Flutter's stock haptics cannot express.
class WheelHaptics {
  const WheelHaptics._();

  /// Requests a native two-pulse vibration when a click-wheel-controlled value reaches a range boundary.
  ///
  /// Returns a [Future] that completes when the Android host reports success.
  ///
  /// Throws a [PlatformException] when the device has no vibrator or the native haptic
  /// request fails. Throws a [MissingPluginException] when no platform handler is
  /// registered for the `mass_mate/haptics` method channel.
  static Future<void> boundaryBuzz() {
    return _hapticsChannel.invokeMethod<void>('boundaryBuzz');
  }

  /// Reports a haptic failure through Flutter's structured error reporting pipeline.
  ///
  /// The [context] describes the operation that failed, [error] is the caught exception
  /// or error object, and [stackTrace] identifies where the failure occurred.
  static void reportFailure({
    required String context,
    required Object error,
    required StackTrace stackTrace,
  }) {
    FlutterError.reportError(
      FlutterErrorDetails(
        exception: error,
        stack: stackTrace,
        library: 'mass_mate haptics',
        context: ErrorDescription(context),
      ),
    );
  }
}
