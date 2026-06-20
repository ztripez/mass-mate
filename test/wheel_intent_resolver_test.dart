import 'package:flutter_test/flutter_test.dart';
import 'package:mass_mate/playback/playback_intent.dart';
import 'package:mass_mate/playback/wheel_intent_resolver.dart';
import 'package:mass_mate/wheel/wheel_gesture.dart';
import 'package:mass_mate/wheel_mode.dart';

import 'playback_test_helpers.dart';

void main() {
  test('maps seek movement to local preview state', () {
    final resolver = WheelIntentResolver();

    final result = resolver.resolve(
      gesture: WheelGesture(turnDelta: turnDeltaForDetents(1)),
      mode: WheelMode.seek,
      playback: snapshot(),
    );

    expect(result.localStateChanged, isTrue);
    expect(result.intent, isNull);
    expect(
      resolver.seekPreviewPosition,
      const Duration(minutes: 18, seconds: 47),
    );
  });

  test('maps volume movement to an absolute clamped volume intent', () {
    final resolver = WheelIntentResolver();

    final result = resolver.resolve(
      gesture: WheelGesture(turnDelta: 0.25),
      mode: WheelMode.volume,
      playback: snapshot(volume: 0.98),
    );

    expect(
      result.intent,
      isA<SetVolumePlaybackIntent>().having(
        (intent) => intent.volume,
        'volume',
        1,
      ),
    );
    expect(
      result.boundaryHit,
      const PlaybackBoundaryHit(
        mode: WheelMode.volume,
        boundary: PlaybackRangeBoundary.maximum,
      ),
    );
  });

  test('maps volume movement inside range without boundary feedback', () {
    final resolver = WheelIntentResolver();

    final result = resolver.resolve(
      gesture: WheelGesture(turnDelta: 0.25),
      mode: WheelMode.volume,
      playback: snapshot(volume: 0.62),
    );

    expect(
      result.intent,
      isA<SetVolumePlaybackIntent>().having(
        (intent) => intent.volume,
        'volume',
        closeTo(0.665, 1e-9),
      ),
    );
    expect(result.boundaryHit, isNull);
  });

  test('maps queue movement to an absolute queue selection intent', () {
    final resolver = WheelIntentResolver();

    final result = resolver.resolve(
      gesture: WheelGesture(turnDelta: 0.25),
      mode: WheelMode.queue,
      playback: snapshot(queueIndex: 3),
    );

    expect(
      result.intent,
      isA<SelectQueueItemPlaybackIntent>().having(
        (intent) => intent.queueIndex,
        'queueIndex',
        6,
      ),
    );
  });

  test('emits boundary feedback once until the value moves back inside', () {
    final resolver = WheelIntentResolver();

    final firstHit = resolver.resolve(
      gesture: WheelGesture(turnDelta: -0.25),
      mode: WheelMode.queue,
      playback: snapshot(queueIndex: 3),
    );
    expect(
      firstHit.boundaryHit,
      const PlaybackBoundaryHit(
        mode: WheelMode.queue,
        boundary: PlaybackRangeBoundary.minimum,
      ),
    );

    final repeatedHit = resolver.resolve(
      gesture: WheelGesture(turnDelta: -0.25),
      mode: WheelMode.queue,
      playback: snapshot(queueIndex: 1),
    );
    expect(repeatedHit.boundaryHit, isNull);

    final awayFromBoundary = resolver.resolve(
      gesture: WheelGesture(turnDelta: 0.25),
      mode: WheelMode.queue,
      playback: snapshot(queueIndex: 1),
    );
    expect(awayFromBoundary.boundaryHit, isNull);

    final secondHit = resolver.resolve(
      gesture: WheelGesture(turnDelta: -0.25),
      mode: WheelMode.queue,
      playback: snapshot(queueIndex: 4),
    );
    expect(
      secondHit.boundaryHit,
      const PlaybackBoundaryHit(
        mode: WheelMode.queue,
        boundary: PlaybackRangeBoundary.minimum,
      ),
    );
  });

  test('canceling seek preview clears seek boundary feedback suppression', () {
    final resolver = WheelIntentResolver();
    final playback = snapshot(position: const Duration(seconds: 2));

    final firstHit = resolver.resolve(
      gesture: WheelGesture(turnDelta: turnDeltaForDetents(-1)),
      mode: WheelMode.seek,
      playback: playback,
    );
    expect(
      firstHit.boundaryHit,
      const PlaybackBoundaryHit(
        mode: WheelMode.seek,
        boundary: PlaybackRangeBoundary.minimum,
      ),
    );

    resolver.cancelSeekPreview();

    final secondHit = resolver.resolve(
      gesture: WheelGesture(turnDelta: turnDeltaForDetents(-1)),
      mode: WheelMode.seek,
      playback: playback,
    );
    expect(
      secondHit.boundaryHit,
      const PlaybackBoundaryHit(
        mode: WheelMode.seek,
        boundary: PlaybackRangeBoundary.minimum,
      ),
    );
  });

  test('mode changes cancel active seek preview', () {
    final resolver = WheelIntentResolver();

    resolver.resolve(
      gesture: WheelGesture(turnDelta: turnDeltaForDetents(1)),
      mode: WheelMode.seek,
      playback: snapshot(),
    );
    expect(resolver.seekPreviewPosition, isNotNull);

    resolver.resolve(
      gesture: WheelGesture(turnDelta: 0.25),
      mode: WheelMode.volume,
      playback: snapshot(),
    );

    expect(resolver.seekPreviewPosition, isNull);
  });

  test('mode changes report canceled seek preview without playback intent', () {
    final resolver = WheelIntentResolver();

    resolver.resolve(
      gesture: WheelGesture(turnDelta: turnDeltaForDetents(1)),
      mode: WheelMode.seek,
      playback: snapshot(),
    );
    expect(resolver.seekPreviewPosition, isNotNull);

    final result = resolver.resolve(
      gesture: WheelGesture(turnDelta: 0),
      mode: WheelMode.volume,
      playback: snapshot(),
    );

    expect(result.intent, isNull);
    expect(result.localStateChanged, isTrue);
    expect(resolver.seekPreviewPosition, isNull);
  });

  test('creates seek commit intent and clears preview only after completion',
      () {
    final resolver = WheelIntentResolver();

    final preview = resolver.resolve(
      gesture: WheelGesture(turnDelta: turnDeltaForDetents(1)),
      mode: WheelMode.seek,
      playback: snapshot(),
    );
    expect(preview.localStateChanged, isTrue);
    expect(preview.intent, isNull);

    final commit = resolver.resolveSeekPreviewCommit();
    expect(
      commit.intent,
      isA<SeekToPlaybackIntent>().having(
        (intent) => intent.position,
        'position',
        const Duration(minutes: 18, seconds: 47),
      ),
    );
    expect(
      resolver.seekPreviewPosition,
      const Duration(minutes: 18, seconds: 47),
    );

    resolver.completeSeekPreviewCommit();
    expect(resolver.seekPreviewPosition, isNull);
    expect(resolver.resolveSeekPreviewCommit().intent, isNull);
  });

  test('accumulates audiobook preview across updates before commit', () {
    final resolver = WheelIntentResolver();
    final playback = snapshot(
      position: const Duration(hours: 1),
      trackLength: const Duration(hours: 10),
    );

    final first = resolver.resolve(
      gesture: WheelGesture(turnDelta: turnDeltaForDetents(12)),
      mode: WheelMode.seek,
      playback: playback,
    );
    expect(first.localStateChanged, isTrue);
    expect(first.intent, isNull);
    expect(resolver.seekPreviewPosition, const Duration(hours: 2));

    final second = resolver.resolve(
      gesture: WheelGesture(turnDelta: turnDeltaForDetents(12)),
      mode: WheelMode.seek,
      playback: playback,
    );
    expect(second.localStateChanged, isTrue);
    expect(second.intent, isNull);
    expect(resolver.seekPreviewPosition, const Duration(hours: 3));

    final commit = resolver.resolveSeekPreviewCommit();
    expect(
      commit.intent,
      isA<SeekToPlaybackIntent>().having(
        (intent) => intent.position,
        'position',
        const Duration(hours: 3),
      ),
    );
  });

  test('explicit mode activation cancels active seek preview', () {
    final resolver = WheelIntentResolver();

    resolver.resolve(
      gesture: WheelGesture(turnDelta: turnDeltaForDetents(1)),
      mode: WheelMode.seek,
      playback: snapshot(),
    );
    resolver.activateMode(WheelMode.volume);
    resolver.activateMode(WheelMode.queue);
    resolver.activateMode(WheelMode.seek);

    expect(resolver.seekPreviewPosition, isNull);
  });

  test('explicit mode activation clears partial queue accumulation', () {
    final resolver = WheelIntentResolver();

    final first = resolver.resolve(
      gesture: WheelGesture(turnDelta: 0.05),
      mode: WheelMode.queue,
      playback: snapshot(queueIndex: 3),
    );
    expect(first.intent, isNull);

    resolver.activateMode(WheelMode.seek);
    resolver.activateMode(WheelMode.volume);
    resolver.activateMode(WheelMode.queue);

    final result = resolver.resolve(
      gesture: WheelGesture(turnDelta: 0.05),
      mode: WheelMode.queue,
      playback: snapshot(queueIndex: 3),
    );
    expect(result.intent, isNull);
  });
}
