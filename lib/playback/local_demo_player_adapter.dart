import 'playback_intent.dart';
import 'playback_intent_projection.dart';
import 'player_adapter.dart';
import 'player_state.dart';

/// Local in-memory playback adapter used by the prototype before Music Assistant is wired.
final class LocalDemoPlayerAdapter implements PlayerAdapter {
  /// Creates a local demo adapter with optional [initialState].
  LocalDemoPlayerAdapter({PlayerState? initialState})
      : _state = initialState ?? PlayerState.localDemo();

  PlayerState _state;

  @override
  PlayerState get state => _state;

  @override
  Future<PlayerState> applyIntent(PlaybackIntent intent) async {
    _state = projectPlaybackIntent(_state, intent);
    return _state;
  }
}
