import 'dart:async';

import 'native_local_player_bridge.dart';
import 'playback_intent.dart';
import 'playback_snapshot.dart';
import 'player_adapter.dart';
import 'player_state.dart';

/// PlayerAdapter implementation backed by the Android native local-player service.
///
/// This adapter translates Mass Mate playback intents into bridge command envelopes and
/// maps typed native snapshots into the existing [PlayerState] model. It never projects
/// optimistic demo state after a native failure.
final class NativeLocalPlayerAdapter implements PlayerAdapter {
  /// Creates an adapter using [bridge] or the default platform-channel bridge.
  NativeLocalPlayerAdapter({
    NativeLocalPlayerBridge? bridge,
    PlayerState? initialState,
  })  : _bridge = bridge ?? MethodChannelNativeLocalPlayerBridge(),
        _state = initialState ?? _initialState() {
    _snapshotSubscription = _bridge.snapshots.listen(
      _handleSnapshot,
      onError: _handleSnapshotError,
    );
  }

  final NativeLocalPlayerBridge _bridge;
  final StreamController<PlayerState> _states =
      StreamController<PlayerState>.broadcast(sync: true);

  late final StreamSubscription<LocalPlayerSnapshot> _snapshotSubscription;
  PlayerState _state;

  @override
  PlayerState get state => _state;

  @override
  Stream<PlayerState> get states => _states.stream;

  @override
  Future<PlayerState> connect() async {
    final result = await _bridge.connect();
    result.throwIfFailed();
    return _state;
  }

  @override
  Future<PlayerState> disconnect() async {
    final result = await _bridge.disconnect();
    result.throwIfFailed();
    return _state;
  }

  @override
  Future<PlayerState> applyIntent(PlaybackIntent intent) async {
    final result = await _bridge.sendCommand(
      LocalPlayerCommandEnvelope.fromIntent(intent),
    );
    result.throwIfFailed();
    return _state;
  }

  void _handleSnapshot(LocalPlayerSnapshot snapshot) {
    final error = snapshot.error;
    _state = snapshot.toPlayerState();
    _states.add(_state);
    if (error != null) _states.addError(error);
  }

  void _handleSnapshotError(Object error, StackTrace stackTrace) {
    _states.addError(error, stackTrace);
  }

  /// Stops observing native snapshots.
  ///
  /// This is intentionally separate from [disconnect] so a widget subscription cannot own
  /// the native service lifecycle.
  Future<void> dispose() async {
    await _snapshotSubscription.cancel();
    await _states.close();
  }

  static PlayerState _initialState() {
    return PlayerState(
      playerName: 'Mass Mate',
      connectionLabel: 'Native local player disconnected',
      mediaItem: const PlaybackMediaItem(
        title: 'Local player ready',
        subtitle: 'Not connected',
      ),
      playback: PlaybackSnapshot(
        position: Duration.zero,
        trackLength: const Duration(milliseconds: 1),
        volume: 0,
        queueIndex: 1,
        queueMinIndex: 1,
        queueMaxIndex: 1,
      ),
      isPlaying: false,
    );
  }
}
