/// Typed native-local-player error categories exposed by the bridge.
enum LocalPlayerErrorKind {
  /// The selected native backend is unavailable in the current runtime.
  unavailable,

  /// The command requires an active connection, but none exists.
  notConnected,

  /// The native backend rejected the request without treating it as a crash.
  rejected,

  /// The native backend failed while processing the request.
  failed,

  /// A snapshot or reply envelope did not match the bridge contract.
  invalidEnvelope,
}

/// Exception raised when the selected native local-player backend cannot fulfill a request.
final class LocalPlayerBridgeException implements Exception {
  /// Creates a typed bridge exception with a user-visible [message].
  const LocalPlayerBridgeException({
    required this.kind,
    required this.message,
    this.details,
  });

  /// Machine-readable error category.
  final LocalPlayerErrorKind kind;

  /// Human-readable error message suitable for surfacing in the player shell.
  final String message;

  /// Optional backend-provided detail payload.
  final Object? details;

  @override
  String toString() => message;
}

/// Parses a native error envelope into a typed bridge exception.
LocalPlayerBridgeException exceptionFromLocalPlayerEnvelope(
  Object? value, {
  required LocalPlayerErrorKind fallbackKind,
  required String fallbackMessage,
}) {
  if (value is! Map) {
    return LocalPlayerBridgeException(
      kind: fallbackKind,
      message: fallbackMessage,
      details: value,
    );
  }

  return LocalPlayerBridgeException(
    kind: localPlayerErrorKindFromCode(value['code']?.toString()),
    message: value['message']?.toString() ?? fallbackMessage,
    details: value['details'],
  );
}

/// Maps native local-player error codes to Dart bridge error categories.
LocalPlayerErrorKind localPlayerErrorKindFromCode(String? code) {
  return switch (code) {
    'LOCAL_PLAYER_UNAVAILABLE' => LocalPlayerErrorKind.unavailable,
    'LOCAL_PLAYER_NOT_CONNECTED' => LocalPlayerErrorKind.notConnected,
    'LOCAL_PLAYER_REJECTED' => LocalPlayerErrorKind.rejected,
    'LOCAL_PLAYER_INVALID_ENVELOPE' => LocalPlayerErrorKind.invalidEnvelope,
    _ => LocalPlayerErrorKind.failed,
  };
}
