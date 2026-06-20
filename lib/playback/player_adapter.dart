import 'playback_intent.dart';
import 'player_state.dart';

/// Intent-level playback adapter consumed by the player UI.
///
/// Implementations may be local demo state, a fake test adapter, or a Music Assistant-backed
/// adapter. UI widgets depend on this shape instead of depending on a specific backend.
abstract interface class PlayerAdapter {
  /// Latest player state available to the UI.
  PlayerState get state;

  /// Applies [intent] to the active playback target and returns the latest state.
  ///
  /// Implementations must throw when an intent cannot be applied. They must not report
  /// success for a remote command that was not accepted by its backend.
  Future<PlayerState> applyIntent(PlaybackIntent intent);
}
