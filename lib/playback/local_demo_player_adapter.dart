import 'playback_intent.dart';
import 'playback_intent_projection.dart';
import 'player_state.dart';
import 'state_stream_player_adapter.dart';

/// Local in-memory playback adapter used by the prototype before Music Assistant is wired.
final class LocalDemoPlayerAdapter extends StateStreamPlayerAdapter {
  /// Creates a local demo adapter with optional [initialState].
  LocalDemoPlayerAdapter({PlayerState? initialState})
      : _state = initialState ?? PlayerState.localDemo();

  PlayerState _state;

  @override
  PlayerState get state => _state;

  @override
  Future<PlayerState> connect() async => _state;

  @override
  Future<PlayerState> disconnect() async => _state;

  @override
  Future<PlayerState> applyIntent(PlaybackIntent intent) async {
    _state = projectPlaybackIntent(_state, intent);
    emitState(_state);
    return _state;
  }
}
