import 'dart:async';

import '../playback/playback_intent.dart';
import '../playback/playback_intent_projection.dart';
import '../playback/player_adapter.dart';
import '../playback/player_state.dart';
import 'music_assistant_client.dart';

/// Player adapter seam for a future Music Assistant-backed playback target.
///
/// The adapter sends canonical Mass Mate playback intents to [MusicAssistantClient]. It does
/// not implement authentication, discovery, selected-player persistence, websocket state
/// subscriptions, or concrete `/api` request payloads.
final class MusicAssistantPlayerAdapter implements PlayerAdapter {
  /// Creates an adapter for [targetId] using [client] and an [initialState] snapshot.
  ///
  /// Throws an [ArgumentError] when [targetId] is blank.
  MusicAssistantPlayerAdapter({
    required MusicAssistantClient client,
    required String targetId,
    required PlayerState initialState,
  })  : _client = client,
        _targetId = _validatedTargetId(targetId),
        _state = initialState;

  final MusicAssistantClient _client;
  final String _targetId;
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
    await _client.applyIntent(targetId: _targetId, intent: intent);
    _state = projectPlaybackIntent(_state, intent);
    _states.add(_state);
    return _state;
  }

  static String _validatedTargetId(String targetId) {
    if (targetId.trim().isEmpty) {
      throw ArgumentError.value(targetId, 'targetId', 'must not be blank');
    }
    return targetId;
  }
}
