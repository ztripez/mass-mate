import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mass_mate/playback/native_local_player_bridge.dart';
import 'package:mass_mate/playback/playback_intent.dart';
import 'package:mass_mate/playback/player_adapter_factory.dart';

import 'fake_native_local_player_bridge.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('LocalPlayerSnapshot parses typed platform envelopes', () {
    final snapshot = LocalPlayerSnapshot.fromMap({
      'connectionStatus': 'connected',
      'playerName': 'Native Player',
      'connectionLabel': 'Connected',
      'mediaTitle': 'Song',
      'mediaSubtitle': 'Artist',
      'positionMs': 1200,
      'trackLengthMs': 3000,
      'volume': 0.5,
      'queueIndex': 1,
      'queueMinIndex': 1,
      'queueMaxIndex': 2,
      'isPlaying': true,
    });

    expect(snapshot.connectionStatus, LocalPlayerConnectionStatus.connected);
    expect(snapshot.toPlayerState().mediaItem.title, 'Song');
    expect(snapshot.toPlayerState().playback.position.inMilliseconds, 1200);
  });

  test('LocalPlayerSnapshot rejects malformed platform envelopes', () {
    expect(
      () => LocalPlayerSnapshot.fromMap({'connectionStatus': 'connected'}),
      throwsA(isA<LocalPlayerBridgeException>()),
    );
  });

  test('LocalPlayerBridgeResult parses nested native errors', () {
    final result = LocalPlayerBridgeResult.fromMap({
      'accepted': false,
      'error': {
        'code': 'LOCAL_PLAYER_NOT_CONNECTED',
        'message': 'Native local player is not connected.',
      },
    });

    expect(result.accepted, isFalse);
    expect(result.error?.kind, LocalPlayerErrorKind.notConnected);
    expect(result.error?.message, 'Native local player is not connected.');
  });

  test('MethodChannelNativeLocalPlayerBridge sends lifecycle and command calls',
      () async {
    const methodChannel = MethodChannel('test/local_player');
    const eventChannel = EventChannel('test/local_player/snapshots');
    final bridge = MethodChannelNativeLocalPlayerBridge(
      methodChannel: methodChannel,
      eventChannel: eventChannel,
    );
    final calls = <MethodCall>[];

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
      calls.add(call);
      return {'accepted': true};
    });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, null),
    );

    await bridge.connect();
    await bridge.disconnect();
    await bridge.sendCommand(
      LocalPlayerCommandEnvelope.fromIntent(
        const SeekToPlaybackIntent(Duration(seconds: 7)),
      ),
    );

    expect(calls.map((call) => call.method), [
      'connect',
      'disconnect',
      'sendCommand',
    ]);
    expect(calls.last.arguments, {
      'command': 'seekTo',
      'arguments': {'positionMs': 7000},
    });
  });

  test('player adapter factory can select the native local-player backend', () {
    final bridge = FakeNativeLocalPlayerBridge();
    addTearDown(bridge.dispose);

    final adapter = createPlayerAdapter(
      backend: PlayerBackendSelection.nativeLocalPlayer,
      nativeBridge: bridge,
    );

    expect(adapter.state.connectionLabel, 'Native local player disconnected');
  });
}
