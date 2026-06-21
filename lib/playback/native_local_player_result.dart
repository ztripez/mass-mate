import 'native_local_player_error.dart';

/// Result returned by native lifecycle and command calls.
final class LocalPlayerBridgeResult {
  const LocalPlayerBridgeResult._({
    required this.accepted,
    this.error,
  });

  /// Creates an accepted bridge result.
  const LocalPlayerBridgeResult.accepted() : this._(accepted: true);

  /// Creates a rejected or failed bridge result.
  const LocalPlayerBridgeResult.failed(LocalPlayerBridgeException error)
      : this._(accepted: false, error: error);

  /// Whether the native backend accepted the request.
  final bool accepted;

  /// Error returned when [accepted] is false.
  final LocalPlayerBridgeException? error;

  /// Throws [error] when the result was not accepted.
  ///
  /// Accepted native replies are the only successful result. Rejected, failed,
  /// malformed, or contradictory envelopes become [LocalPlayerBridgeException]
  /// values so callers cannot accidentally treat native backend failure as
  /// local success.
  void throwIfFailed() {
    final failure = error;
    if (!accepted && failure != null) throw failure;
    if (!accepted) {
      throw const LocalPlayerBridgeException(
        kind: LocalPlayerErrorKind.failed,
        message: 'Native local player rejected the request.',
      );
    }
  }

  /// Parses a MethodChannel result envelope.
  ///
  /// Valid native replies are maps shaped as `{ accepted: true }` for success or
  /// `{ accepted: false, error: { code: String, message: String, details?: Object } }`
  /// for rejected or failed operations. An `accepted: true` envelope with an
  /// `error` payload becomes [LocalPlayerErrorKind.invalidEnvelope].
  factory LocalPlayerBridgeResult.fromMap(Object? value) {
    if (value is! Map) {
      return const LocalPlayerBridgeResult.failed(
        LocalPlayerBridgeException(
          kind: LocalPlayerErrorKind.invalidEnvelope,
          message: 'Native local player returned a malformed reply.',
        ),
      );
    }

    final accepted = value['accepted'];
    if (accepted == true) {
      if (value.containsKey('error') && value['error'] != null) {
        return const LocalPlayerBridgeResult.failed(
          LocalPlayerBridgeException(
            kind: LocalPlayerErrorKind.invalidEnvelope,
            message:
                'Native local player returned success with an error payload.',
          ),
        );
      }
      return const LocalPlayerBridgeResult.accepted();
    }
    if (accepted is! bool) {
      return const LocalPlayerBridgeResult.failed(
        LocalPlayerBridgeException(
          kind: LocalPlayerErrorKind.invalidEnvelope,
          message:
              'Native local player reply is missing a boolean accepted field.',
        ),
      );
    }

    return LocalPlayerBridgeResult.failed(
      exceptionFromLocalPlayerEnvelope(
        value['error'] ?? value,
        fallbackKind: LocalPlayerErrorKind.rejected,
        fallbackMessage: 'Native local player rejected the request.',
      ),
    );
  }
}
