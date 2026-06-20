import 'package:flutter_test/flutter_test.dart';
import 'package:mass_mate/playback/playback_intent.dart';
import 'package:mass_mate/playback/wheel_intent_resolver.dart';
import 'package:mass_mate/wheel/wheel_gesture.dart';
import 'package:mass_mate/wheel_mode.dart';

void main() {
  group('WheelGesture', () {
    test('rejects non-finite turn deltas', () {
      expectArgumentErrorNamed(
        'turnDelta',
        () => WheelGesture(turnDelta: double.nan),
      );
      expectArgumentErrorNamed(
        'turnDelta',
        () => WheelGesture(turnDelta: double.infinity),
      );
      expectArgumentErrorNamed(
        'turnDelta',
        () => WheelGesture(turnDelta: double.negativeInfinity),
      );
    });
  });

  group('PlaybackSnapshot', () {
    test('rejects each invalid snapshot invariant with specific errors', () {
      expectArgumentErrorNamed(
        'position',
        () => _snapshot(position: const Duration(seconds: -1)),
      );
      expectArgumentErrorNamed(
        'trackLength',
        () => _snapshot(position: Duration.zero, trackLength: Duration.zero),
      );
      expectArgumentErrorNamed(
        'trackLength',
        () => _snapshot(
          position: Duration.zero,
          trackLength: const Duration(seconds: -1),
        ),
      );
      expectArgumentErrorNamed(
        'position',
        () => _snapshot(position: const Duration(hours: 1)),
      );
      expectArgumentErrorNamed('volume', () => _snapshot(volume: -0.01));
      expectArgumentErrorNamed('volume', () => _snapshot(volume: 1.01));
      expectArgumentErrorNamed('volume', () => _snapshot(volume: double.nan));
      expectArgumentErrorNamed(
        'volume',
        () => _snapshot(volume: double.infinity),
      );
      expectArgumentErrorNamed(
        'volume',
        () => _snapshot(volume: double.negativeInfinity),
      );
      expectArgumentErrorNamed(
          'queueMinIndex', () => _snapshot(queueMinIndex: 0));
      expectArgumentErrorNamed(
        'queueMinIndex',
        () => _snapshot(queueMinIndex: -1),
      );
      expectArgumentErrorNamed(
        'queueMinIndex',
        () => _snapshot(queueMinIndex: 10, queueMaxIndex: 1),
      );
      expectArgumentErrorNamed('queueIndex', () => _snapshot(queueIndex: 0));
      expectArgumentErrorNamed(
        'queueIndex',
        () => _snapshot(queueIndex: 25),
      );
    });
  });

  group('WheelIntentResolver', () {
    test('maps seek movement with fractional accumulation', () {
      final resolver = WheelIntentResolver();

      final first = resolver.resolve(
        gesture: WheelGesture(turnDelta: 0.01),
        mode: WheelMode.seek,
        playback: _snapshot(),
      );
      expect(first.intent, isNull);

      final second = resolver.resolve(
        gesture: WheelGesture(turnDelta: 0.01),
        mode: WheelMode.seek,
        playback: _snapshot(),
      );

      expect(
        second.intent,
        isA<SeekToPlaybackIntent>().having(
          (intent) => intent.position,
          'position',
          const Duration(minutes: 18, seconds: 43),
        ),
      );
    });

    test('maps volume movement to an absolute clamped volume intent', () {
      final resolver = WheelIntentResolver();

      final result = resolver.resolve(
        gesture: WheelGesture(turnDelta: 0.25),
        mode: WheelMode.volume,
        playback: _snapshot(volume: 0.98),
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
        playback: _snapshot(volume: 0.62),
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
        playback: _snapshot(queueIndex: 3),
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
        playback: _snapshot(queueIndex: 3),
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
        playback: _snapshot(queueIndex: 1),
      );
      expect(repeatedHit.boundaryHit, isNull);

      final awayFromBoundary = resolver.resolve(
        gesture: WheelGesture(turnDelta: 0.25),
        mode: WheelMode.queue,
        playback: _snapshot(queueIndex: 1),
      );
      expect(awayFromBoundary.boundaryHit, isNull);

      final secondHit = resolver.resolve(
        gesture: WheelGesture(turnDelta: -0.25),
        mode: WheelMode.queue,
        playback: _snapshot(queueIndex: 4),
      );
      expect(
        secondHit.boundaryHit,
        const PlaybackBoundaryHit(
          mode: WheelMode.queue,
          boundary: PlaybackRangeBoundary.minimum,
        ),
      );
    });

    test('mode changes clear partial wheel accumulation', () {
      final resolver = WheelIntentResolver();

      resolver.resolve(
        gesture: WheelGesture(turnDelta: 0.01),
        mode: WheelMode.seek,
        playback: _snapshot(),
      );
      resolver.resolve(
        gesture: WheelGesture(turnDelta: 0.01),
        mode: WheelMode.volume,
        playback: _snapshot(),
      );

      final result = resolver.resolve(
        gesture: WheelGesture(turnDelta: 0.01),
        mode: WheelMode.seek,
        playback: _snapshot(),
      );
      expect(result.intent, isNull);
    });

    test('explicit mode activation clears partial wheel accumulation', () {
      final resolver = WheelIntentResolver();

      resolver.resolve(
        gesture: WheelGesture(turnDelta: 0.01),
        mode: WheelMode.seek,
        playback: _snapshot(),
      );
      resolver.activateMode(WheelMode.volume);
      resolver.activateMode(WheelMode.queue);
      resolver.activateMode(WheelMode.seek);

      final result = resolver.resolve(
        gesture: WheelGesture(turnDelta: 0.01),
        mode: WheelMode.seek,
        playback: _snapshot(),
      );
      expect(result.intent, isNull);
    });

    test('explicit mode activation clears partial queue accumulation', () {
      final resolver = WheelIntentResolver();

      final first = resolver.resolve(
        gesture: WheelGesture(turnDelta: 0.05),
        mode: WheelMode.queue,
        playback: _snapshot(queueIndex: 3),
      );
      expect(first.intent, isNull);

      resolver.activateMode(WheelMode.seek);
      resolver.activateMode(WheelMode.volume);
      resolver.activateMode(WheelMode.queue);

      final result = resolver.resolve(
        gesture: WheelGesture(turnDelta: 0.05),
        mode: WheelMode.queue,
        playback: _snapshot(queueIndex: 3),
      );
      expect(result.intent, isNull);
    });
  });
}

PlaybackSnapshot _snapshot({
  Duration position = const Duration(minutes: 18, seconds: 42),
  Duration trackLength = const Duration(minutes: 54, seconds: 18),
  double volume = 0.62,
  int queueIndex = 3,
  int queueMinIndex = 1,
  int queueMaxIndex = 24,
}) {
  return PlaybackSnapshot(
    position: position,
    trackLength: trackLength,
    volume: volume,
    queueIndex: queueIndex,
    queueMinIndex: queueMinIndex,
    queueMaxIndex: queueMaxIndex,
  );
}

void expectArgumentErrorNamed(String expectedName, Object? Function() build) {
  expect(
    build,
    throwsA(
      isA<ArgumentError>().having(
        (error) => error.name,
        'name',
        expectedName,
      ),
    ),
  );
}
