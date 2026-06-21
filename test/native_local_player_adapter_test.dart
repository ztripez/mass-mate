import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mass_mate/playback/native_local_player_adapter.dart';
import 'package:mass_mate/playback/native_local_player_bridge.dart';
import 'package:mass_mate/playback/playback_intent.dart';
import 'package:mass_mate/player_screen.dart';

import 'fake_native_local_player_bridge.dart';

void main() {
  test('NativeLocalPlayerAdapter forwards lifecycle through the bridge',
      () async {
    final bridge = FakeNativeLocalPlayerBridge();
    addTearDown(bridge.dispose);
    final adapter = NativeLocalPlayerAdapter(bridge: bridge);
    addTearDown(adapter.dispose);

    await adapter.connect();
    await adapter.disconnect();

    expect(bridge.connectCalls, 1);
    expect(bridge.disconnectCalls, 1);
  });

  test('NativeLocalPlayerAdapter sends intent-level command envelopes',
      () async {
    final bridge = FakeNativeLocalPlayerBridge();
    addTearDown(bridge.dispose);
    final adapter = NativeLocalPlayerAdapter(bridge: bridge);
    addTearDown(adapter.dispose);

    await adapter.applyIntent(const TogglePlayPausePlaybackIntent());
    await adapter.applyIntent(
      const SeekToPlaybackIntent(Duration(seconds: 9)),
    );
    await adapter.applyIntent(const SetVolumePlaybackIntent(0.25));
    await adapter.applyIntent(const SelectQueueItemPlaybackIntent(4));

    expect(
      bridge.commands.map((command) => command.command),
      [
        LocalPlayerCommand.togglePlayPause,
        LocalPlayerCommand.seekTo,
        LocalPlayerCommand.setVolume,
        LocalPlayerCommand.selectQueueItem,
      ],
    );
    expect(bridge.commands[1].arguments, {'positionMs': 9000});
    expect(bridge.commands[2].arguments, {'volume': 0.25});
    expect(bridge.commands[3].arguments, {'queueIndex': 4});
  });

  test('NativeLocalPlayerAdapter maps fake snapshots into PlayerState',
      () async {
    final bridge = FakeNativeLocalPlayerBridge();
    addTearDown(bridge.dispose);
    final adapter = NativeLocalPlayerAdapter(bridge: bridge);
    addTearDown(adapter.dispose);

    final emittedStates = <String>[];
    final subscription = adapter.states.listen(
      (state) => emittedStates.add(state.mediaItem.title),
    );
    addTearDown(subscription.cancel);

    bridge.emitSnapshot(nativeSnapshot(mediaTitle: 'Bridge Snapshot'));

    expect(adapter.state.mediaItem.title, 'Bridge Snapshot');
    expect(adapter.state.connectionLabel, 'Native local player connected');
    expect(adapter.state.playback.position,
        const Duration(minutes: 2, seconds: 3));
    expect(emittedStates, ['Bridge Snapshot']);
  });

  test('NativeLocalPlayerAdapter surfaces native command failures', () async {
    final bridge = FakeNativeLocalPlayerBridge()
      ..commandResult = const LocalPlayerBridgeResult.failed(
        LocalPlayerBridgeException(
          kind: LocalPlayerErrorKind.notConnected,
          message: 'Native local player is not connected.',
        ),
      );
    addTearDown(bridge.dispose);
    final adapter = NativeLocalPlayerAdapter(bridge: bridge);
    addTearDown(adapter.dispose);

    await expectLater(
      adapter.applyIntent(const TogglePlayPausePlaybackIntent()),
      throwsA(isA<LocalPlayerBridgeException>()),
    );
    expect(bridge.commands, hasLength(1));
  });

  testWidgets('PlayerScreen observes native snapshots without owning lifecycle',
      (tester) async {
    final bridge = FakeNativeLocalPlayerBridge();
    addTearDown(bridge.dispose);
    final adapter = NativeLocalPlayerAdapter(bridge: bridge);
    addTearDown(adapter.dispose);

    await tester
        .pumpWidget(MaterialApp(home: PlayerScreen(playerAdapter: adapter)));

    bridge.emitSnapshot(nativeSnapshot(mediaTitle: 'Streamed Native Title'));
    await tester.pump();

    expect(find.text('Streamed Native Title'), findsOneWidget);
    expect(bridge.connectCalls, 0);
    expect(bridge.disconnectCalls, 0);
  });

  testWidgets('PlayerScreen shows native adapter command failures',
      (tester) async {
    final bridge = FakeNativeLocalPlayerBridge()
      ..commandResult = const LocalPlayerBridgeResult.failed(
        LocalPlayerBridgeException(
          kind: LocalPlayerErrorKind.notConnected,
          message: 'Native local player is not connected.',
        ),
      );
    addTearDown(bridge.dispose);
    final adapter = NativeLocalPlayerAdapter(bridge: bridge);
    addTearDown(adapter.dispose);

    await tester
        .pumpWidget(MaterialApp(home: PlayerScreen(playerAdapter: adapter)));

    await tester.tap(find.byIcon(Icons.play_arrow));
    await tester.pump();

    expect(find.text('Native local player is not connected.'), findsOneWidget);
  });
}
