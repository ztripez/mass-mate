import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mass_mate/click_wheel.dart';
import 'package:mass_mate/playback/playback_intent.dart';
import 'package:mass_mate/playback/playback_intent_projection.dart';
import 'package:mass_mate/playback/playback_snapshot.dart';
import 'package:mass_mate/playback/player_adapter.dart';
import 'package:mass_mate/playback/player_state.dart';
import 'package:mass_mate/player_screen.dart';

const MethodChannel _platformChannel = SystemChannels.platform;

void main() {
  testWidgets('PlayerScreen renders state supplied by the adapter',
      (tester) async {
    final adapter = _RecordingPlayerAdapter(
      initialState: PlayerState(
        playerName: 'Kitchen Speaker',
        connectionLabel: 'Fake adapter',
        mediaItem: const PlaybackMediaItem(
          title: 'Adapter Title',
          subtitle: 'Adapter Subtitle',
        ),
        playback: PlaybackSnapshot(
          position: const Duration(hours: 1, minutes: 2, seconds: 3),
          trackLength: const Duration(hours: 2),
          volume: 0.42,
          queueIndex: 4,
          queueMinIndex: 1,
          queueMaxIndex: 9,
        ),
        isPlaying: false,
      ),
    );

    await _pumpPlayerScreenAtSize(
      tester,
      const Size(390, 844),
      playerAdapter: adapter,
    );

    expect(find.text('Kitchen Speaker'), findsOneWidget);
    expect(find.text('Fake adapter'), findsOneWidget);
    expect(find.text('Adapter Title'), findsOneWidget);
    expect(find.text('Adapter Subtitle'), findsOneWidget);
    expect(find.text('1:02:03'), findsOneWidget);
    expect(find.text('-57:57'), findsOneWidget);
    expect(find.textContaining('Volume 42%'), findsOneWidget);
    expect(find.textContaining('Queue item 4 of 9'), findsOneWidget);
    expect(find.byIcon(Icons.play_arrow), findsOneWidget);
  });

  testWidgets('center commit sends seek intent to adapter after local preview',
      (tester) async {
    final adapter = _RecordingPlayerAdapter();
    final platformCalls = <MethodCall>[];
    _capturePlatformCalls(platformCalls);

    await _pumpPlayerScreenAtSize(
      tester,
      const Size(390, 844),
      playerAdapter: adapter,
    );

    await tester.tap(find.byIcon(Icons.skip_next));
    await tester.pump();

    expect(find.text('Seek preview'), findsOneWidget);
    expect(adapter.intents, isEmpty);

    await tester.tap(find.byType(FilledButton));
    await tester.pump();

    expect(adapter.intents, hasLength(1));
    expect(
      adapter.intents.single,
      isA<SeekToPlaybackIntent>().having(
        (intent) => intent.position,
        'position',
        const Duration(minutes: 24, seconds: 27, milliseconds: 600),
      ),
    );
    expect(_hapticCalls(platformCalls, 'selectionClick'), hasLength(1));
  });

  testWidgets('wheel release sends committed seek intent to adapter',
      (tester) async {
    final adapter = _RecordingPlayerAdapter();
    final platformCalls = <MethodCall>[];
    _capturePlatformCalls(platformCalls);

    await _pumpPlayerScreenAtSize(
      tester,
      const Size(390, 844),
      playerAdapter: adapter,
    );

    final center = tester.getCenter(find.byType(ClickWheel));
    final gesture = await tester.startGesture(center + const Offset(92, -92));
    await gesture.moveTo(center + const Offset(130, 0));
    await tester.pump();

    expect(find.text('Seek preview'), findsOneWidget);
    expect(adapter.intents, isEmpty);

    await gesture.up();
    await tester.pump();

    expect(adapter.intents, hasLength(1));
    expect(
      adapter.intents.single,
      isA<SeekToPlaybackIntent>().having(
        (intent) => intent.position,
        'position',
        const Duration(minutes: 23, seconds: 12),
      ),
    );
    expect(_hapticCalls(platformCalls, 'selectionClick'), hasLength(1));
  });

  testWidgets('seek preview remains visible until adapter accepts commit',
      (tester) async {
    final adapter = _DelayedPlayerAdapter();

    await _pumpPlayerScreenAtSize(
      tester,
      const Size(390, 844),
      playerAdapter: adapter,
    );

    await tester.tap(find.byIcon(Icons.skip_next));
    await tester.pump();
    await tester.tap(find.byType(FilledButton));
    await tester.pump();

    expect(adapter.intents, hasLength(1));
    expect(find.text('Seek preview'), findsOneWidget);

    adapter.acceptPendingIntent();
    await tester.pump();

    expect(find.text('Seek preview'), findsNothing);
    expect(find.text('24:27'), findsOneWidget);
  });
}

Iterable<MethodCall> _hapticCalls(List<MethodCall> platformCalls, String type) {
  return platformCalls.where(
    (call) =>
        call.method == 'HapticFeedback.vibrate' &&
        call.arguments == 'HapticFeedbackType.$type',
  );
}

void _capturePlatformCalls(List<MethodCall> platformCalls) {
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(_platformChannel, (call) async {
    platformCalls.add(call);
    return null;
  });
  addTearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_platformChannel, null),
  );
}

Future<void> _pumpPlayerScreenAtSize(
  WidgetTester tester,
  Size size, {
  required PlayerAdapter playerAdapter,
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);

  await tester.pumpWidget(
    MaterialApp(home: PlayerScreen(playerAdapter: playerAdapter)),
  );
}

final class _RecordingPlayerAdapter implements PlayerAdapter {
  _RecordingPlayerAdapter({PlayerState? initialState})
      : _state = initialState ?? PlayerState.localDemo();

  final List<PlaybackIntent> intents = [];
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
    intents.add(intent);
    _state = projectPlaybackIntent(_state, intent);
    _states.add(_state);
    return _state;
  }
}

final class _DelayedPlayerAdapter implements PlayerAdapter {
  _DelayedPlayerAdapter() : _state = PlayerState.localDemo();

  final List<PlaybackIntent> intents = [];
  final Completer<void> _pendingIntent = Completer<void>();
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
    intents.add(intent);
    await _pendingIntent.future;
    _state = projectPlaybackIntent(_state, intent);
    _states.add(_state);
    return _state;
  }

  void acceptPendingIntent() => _pendingIntent.complete();
}
