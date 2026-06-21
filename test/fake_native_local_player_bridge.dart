import 'dart:async';

import 'package:mass_mate/playback/native_local_player_bridge.dart';

final class FakeNativeLocalPlayerBridge implements NativeLocalPlayerBridge {
  final StreamController<LocalPlayerSnapshot> _snapshots =
      StreamController<LocalPlayerSnapshot>.broadcast(sync: true);

  int connectCalls = 0;
  int disconnectCalls = 0;
  final List<LocalPlayerCommandEnvelope> commands = [];

  LocalPlayerBridgeResult connectResult =
      const LocalPlayerBridgeResult.accepted();
  LocalPlayerBridgeResult disconnectResult =
      const LocalPlayerBridgeResult.accepted();
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

  void emitSnapshot(LocalPlayerSnapshot snapshot) {
    _snapshots.add(snapshot);
  }

  void emitError(Object error) {
    _snapshots.addError(error);
  }

  Future<void> dispose() async {
    await _snapshots.close();
  }
}

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
