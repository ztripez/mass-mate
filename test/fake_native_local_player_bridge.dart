import 'dart:async';

import 'package:mass_mate/playback/native_local_player_bridge.dart';

/// In-memory native local-player bridge used by Dart and widget tests.
final class FakeNativeLocalPlayerBridge implements NativeLocalPlayerBridge {
  final StreamController<LocalPlayerSnapshot> _snapshots =
      StreamController<LocalPlayerSnapshot>.broadcast(sync: true);

  /// Number of lifecycle connect requests received by the fake bridge.
  int connectCalls = 0;

  /// Number of lifecycle disconnect requests received by the fake bridge.
  int disconnectCalls = 0;

  /// Intent-level command envelopes received by the fake bridge.
  final List<LocalPlayerCommandEnvelope> commands = [];

  /// Result returned by [connect].
  LocalPlayerBridgeResult connectResult =
      const LocalPlayerBridgeResult.accepted();

  /// Result returned by [disconnect].
  LocalPlayerBridgeResult disconnectResult =
      const LocalPlayerBridgeResult.accepted();

  /// Result returned by [sendCommand].
  LocalPlayerBridgeResult commandResult =
      const LocalPlayerBridgeResult.accepted();

  @override
  Stream<LocalPlayerSnapshot> get snapshots => _snapshots.stream;

  @override
  Future<LocalPlayerBridgeResult> connect() async {
    connectCalls += 1;
    return connectResult;
  }

  @override
  Future<LocalPlayerBridgeResult> disconnect() async {
    disconnectCalls += 1;
    return disconnectResult;
  }

  @override
  Future<LocalPlayerBridgeResult> sendCommand(
    LocalPlayerCommandEnvelope envelope,
  ) async {
    commands.add(envelope);
    return commandResult;
  }

  /// Emits [snapshot] on the fake native snapshot stream.
  void emitSnapshot(LocalPlayerSnapshot snapshot) {
    _snapshots.add(snapshot);
  }

  /// Emits [error] on the fake native snapshot stream.
  void emitError(Object error) {
    _snapshots.addError(error);
  }

  /// Closes fake stream resources.
  Future<void> dispose() async {
    await _snapshots.close();
  }
}

/// Builds a complete fake local-player snapshot for adapter and widget tests.
///
/// Defaults describe a connected, playing native local player with valid playback and
/// queue bounds. Override [connectionStatus] and [error] together for failure-state
/// tests; override player/media/playback fields to assert adapter mapping into
/// `PlayerState` and `PlaybackSnapshot`.
LocalPlayerSnapshot nativeSnapshot({
  LocalPlayerConnectionStatus connectionStatus =
      LocalPlayerConnectionStatus.connected,
  String playerName = 'Mass Mate Native',
  String connectionLabel = 'Native local player connected',
  String mediaTitle = 'Native Title',
  String mediaSubtitle = 'Native Subtitle',
  Duration position = const Duration(minutes: 2, seconds: 3),
  Duration trackLength = const Duration(minutes: 4, seconds: 5),
  double volume = 0.7,
  int queueIndex = 2,
  int queueMinIndex = 1,
  int queueMaxIndex = 5,
  bool isPlaying = true,
  LocalPlayerBridgeException? error,
}) {
  return LocalPlayerSnapshot(
    connectionStatus: connectionStatus,
    playerName: playerName,
    connectionLabel: connectionLabel,
    mediaTitle: mediaTitle,
    mediaSubtitle: mediaSubtitle,
    position: position,
    trackLength: trackLength,
    volume: volume,
    queueIndex: queueIndex,
    queueMinIndex: queueMinIndex,
    queueMaxIndex: queueMaxIndex,
    isPlaying: isPlaying,
    error: error,
  );
}
