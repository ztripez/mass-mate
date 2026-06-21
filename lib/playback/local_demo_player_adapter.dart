import 'dart:async';

import 'playback_intent.dart';
import 'playback_intent_projection.dart';
import 'player_adapter.dart';
import 'player_state.dart';

/// Local in-memory playback adapter used by the prototype before Music Assistant is wired.
final class LocalDemoPlayerAdapter implements PlayerAdapter {
  /// Creates a local demo adapter with optional [initialState].
  LocalDemoPlayerAdapter({PlayerState? initialState})
      : _state = initialState ?? PlayerState.localDemo();

  final StreamController<PlayerState> _states =
      StreamController<PlayerState>.broadcast(sync: true);
  PlayerState _state;

  @override
  PlayerState get state => _state;

  @override
  Stream<PlayerState> get states => _states.stream;

  @override
  Future<PlayerState> connect() async => _state;

  @override
  Future<PlayerState> disconnect() async => _state;

  @override
  Future<PlayerState> applyIntent(PlaybackIntent intent) async {
    _state = projectPlaybackIntent(_state, intent);
    _states.add(_state);
    return _state;
  }
}
