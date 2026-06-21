/// Explicit native Sendspin connection settings passed to Android.
///
/// [serverUrl] is required when the native local-player backend is selected and
/// is normally supplied with `--dart-define=MASS_MATE_SENDSPIN_SERVER_URL=...`.
/// The native endpoint builder accepts `http`, `https`, `ws`, and `wss`; HTTP
/// schemes are converted to WebSocket schemes. [sendspinPath] is optional and
/// comes from `MASS_MATE_SENDSPIN_PATH`, defaulting to `/sendspin`. The path
/// must start with `/`. Missing, blank, or invalid settings fail visibly through
/// native bridge result/snapshot errors instead of falling back to the demo
/// backend.
final class LocalPlayerConnectionSettings {
  /// Creates explicit Sendspin connection settings for the native bridge.
  ///
  /// [serverUrl] must be a configured Music Assistant base URL or Sendspin
  /// WebSocket base URL. [sendspinPath] defaults to `/sendspin` and must retain
  /// the leading slash so native endpoint validation can build the final URL.
  const LocalPlayerConnectionSettings({
    required this.serverUrl,
    this.sendspinPath = '/sendspin',
  });

  /// Creates settings from `--dart-define` values.
  ///
  /// Reads required `MASS_MATE_SENDSPIN_SERVER_URL` and optional
  /// `MASS_MATE_SENDSPIN_PATH`. This method intentionally does not substitute a
  /// fake server when the URL is absent; native Android reports a typed endpoint
  /// failure during bridge connection.
  factory LocalPlayerConnectionSettings.fromEnvironment() {
    const serverUrl = String.fromEnvironment('MASS_MATE_SENDSPIN_SERVER_URL');
    const sendspinPath = String.fromEnvironment(
      'MASS_MATE_SENDSPIN_PATH',
      defaultValue: '/sendspin',
    );
    return const LocalPlayerConnectionSettings(
      serverUrl: serverUrl,
      sendspinPath: sendspinPath,
    );
  }

  /// Required configured Music Assistant server URL or Sendspin WebSocket base URL.
  ///
  /// Accepted schemes are `http`, `https`, `ws`, and `wss`. Blank or invalid
  /// values are surfaced as native endpoint errors.
  final String serverUrl;

  /// Optional Sendspin endpoint path appended by the native endpoint builder.
  ///
  /// Defaults to `/sendspin`; nonblank custom paths must start with `/`.
  final String sendspinPath;

  /// Serializes settings for MethodChannel transport.
  ///
  /// The native side expects `serverUrl` and `sendspinPath` map keys and returns
  /// typed visible failures for invalid values.
  Map<String, Object?> toMap() {
    return {
      'serverUrl': serverUrl,
      'sendspinPath': sendspinPath,
    };
  }
}
