import 'dart:async';

import 'playback_intent.dart';
import 'player_state.dart';

/// Machine-readable playback adapter error category.
enum PlayerAdapterErrorKind {
  /// The selected backend does not implement the requested lifecycle operation.
  unsupported,

  /// The selected backend failed while processing a lifecycle or playback request.
  failed,
}

/// Exception raised by playback adapters when backend operations cannot succeed.
final class PlayerAdapterException implements Exception {
  /// Creates a typed playback adapter exception.
  const PlayerAdapterException({required this.kind, required this.message});

  /// Machine-readable error category.
  final PlayerAdapterErrorKind kind;

  /// User-visible failure message.
  final String message;

  @override
  String toString() => message;
}

/// Intent-level playback adapter consumed by the player UI.
///
/// Implementations may be local demo state, a fake test adapter, or a Music Assistant-backed
/// adapter. UI widgets depend on this shape instead of depending on a specific backend.
abstract interface class PlayerAdapter {
  /// Latest player state available to the UI.
  PlayerState get state;

  /// Player-state snapshots emitted by the backend outside direct UI actions.
  ///
  /// Route widgets may subscribe and unsubscribe from this stream. The stream observes
  /// the backend lifecycle; it does not own or define that lifecycle.
  Stream<PlayerState> get states;

  /// Requests that the backend establish its player connection or service session.
  ///
  /// Implementations must throw when the selected backend cannot connect. They must not
  /// silently switch to another backend after a connection failure.
  Future<PlayerState> connect();

  /// Requests that the backend disconnect or release the active player session.
  Future<PlayerState> disconnect();

  /// Applies [intent] to the active playback target and returns the latest state.
  ///
  /// Implementations must throw when an intent cannot be applied. They must not report
  /// success for a remote command that was not accepted by its backend.
  Future<PlayerState> applyIntent(PlaybackIntent intent);

  /// Releases adapter-owned subscriptions, stream controllers, or native observers.
  ///
  /// Route widgets may dispose their adapter instance when they own that instance, but
  /// disposing an adapter must not be treated as an implicit backend disconnect unless the
  /// adapter's own documentation says it owns the backend lifecycle.
  Future<void> dispose();
}
