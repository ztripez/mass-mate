import 'package:flutter_test/flutter_test.dart';
import 'package:mass_mate/playback/playback_intent.dart';
import 'package:mass_mate/playback/playback_snapshot.dart';
import 'package:mass_mate/playback/seek_model.dart';
import 'package:mass_mate/playback/wheel_intent_resolver.dart';
import 'package:mass_mate/wheel/wheel_constants.dart';
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

  group('SeekModel', () {
    test('keeps short tracks controllable with slow movement', () {
      const model = SeekModel();

      final result = model.preview(
        gesture: WheelGesture(turnDelta: _turnDeltaForDetents(1)),
        playback: _snapshot(
          position: const Duration(minutes: 1),
          trackLength: const Duration(minutes: 3),
        ),
      );

      expect(result.speedBand, SeekSpeedBand.slow);
      expect(result.position, const Duration(minutes: 1, seconds: 5));
      expect(result.boundary, isNull);
    });

    test('uses faster bands for long-form movement', () {
      const model = SeekModel();

      final fast = model.preview(
        gesture: WheelGesture(turnDelta: _turnDeltaForDetents(4)),
        playback: _snapshot(
          position: const Duration(hours: 1),
          trackLength: const Duration(hours: 10),
        ),
      );
      final veryFast = model.preview(
        gesture: WheelGesture(turnDelta: _turnDeltaForDetents(12)),
        playback: _snapshot(
          position: const Duration(hours: 1),
          trackLength: const Duration(hours: 10),
        ),
      );

      expect(fast.speedBand, SeekSpeedBand.fast);
      expect(fast.position, const Duration(hours: 1, minutes: 4));
      expect(veryFast.speedBand, SeekSpeedBand.veryFast);
      expect(veryFast.position, const Duration(hours: 2));
    });

    test('uses normal band for ordinary music-length movement', () {
      const model = SeekModel();

      final result = model.preview(
        gesture: WheelGesture(turnDelta: _turnDeltaForDetents(2)),
        playback: _snapshot(
          position: const Duration(minutes: 1),
          trackLength: const Duration(minutes: 4),
        ),
      );

      expect(result.speedBand, SeekSpeedBand.normal);
      expect(result.position, const Duration(minutes: 1, seconds: 30));
      expect(result.boundary, isNull);
    });

    test('clamps audiobook preview at endpoints', () {
      const model = SeekModel();

      final minimum = model.preview(
        gesture: WheelGesture(turnDelta: _turnDeltaForDetents(-12)),
        playback: _snapshot(
          position: const Duration(minutes: 10),
          trackLength: const Duration(hours: 10),
        ),
      );
      final maximum = model.preview(
        gesture: WheelGesture(turnDelta: _turnDeltaForDetents(12)),
        playback: _snapshot(
          position: const Duration(hours: 9, minutes: 30),
          trackLength: const Duration(hours: 10),
        ),
      );

      expect(minimum.position, Duration.zero);
      expect(minimum.boundary, PlaybackRangeBoundary.minimum);
      expect(maximum.position, const Duration(hours: 10));
      expect(maximum.boundary, PlaybackRangeBoundary.maximum);
    });

    test('rejects invalid current preview state', () {
      const model = SeekModel();

      expectArgumentErrorNamed(
        'currentPreview',
        () => model.preview(
          gesture: WheelGesture(turnDelta: _turnDeltaForDetents(1)),
          playback: _snapshot(
            position: const Duration(minutes: 1),
            trackLength: const Duration(minutes: 3),
          ),
          currentPreview: const Duration(seconds: -1),
        ),
      );
      expectArgumentErrorNamed(
        'currentPreview',
        () => model.preview(
          gesture: WheelGesture(turnDelta: _turnDeltaForDetents(1)),
          playback: _snapshot(
            position: const Duration(minutes: 1),
            trackLength: const Duration(minutes: 3),
          ),
          currentPreview: const Duration(minutes: 4),
        ),
      );
    });
  });

  group('WheelIntentResolver', () {
    test('maps seek movement to local preview state', () {
      final resolver = WheelIntentResolver();

      final result = resolver.resolve(
        gesture: WheelGesture(turnDelta: _turnDeltaForDetents(1)),
        mode: WheelMode.seek,
        playback: _snapshot(),
      );

      expect(
        result.localStateChanged,
        isTrue,
      );
      expect(result.intent, isNull);
      expect(resolver.seekPreviewPosition,
          const Duration(minutes: 18, seconds: 47));
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

    test('mode changes cancel active seek preview', () {
      final resolver = WheelIntentResolver();

      resolver.resolve(
        gesture: WheelGesture(turnDelta: _turnDeltaForDetents(1)),
        mode: WheelMode.seek,
        playback: _snapshot(),
      );
      expect(resolver.seekPreviewPosition, isNotNull);

      resolver.resolve(
        gesture: WheelGesture(turnDelta: 0.25),
        mode: WheelMode.volume,
        playback: _snapshot(),
      );

      expect(resolver.seekPreviewPosition, isNull);
    });

    test('commits active seek preview only on explicit commit', () {
      final resolver = WheelIntentResolver();

      final preview = resolver.resolve(
        gesture: WheelGesture(turnDelta: _turnDeltaForDetents(1)),
        mode: WheelMode.seek,
        playback: _snapshot(),
      );
      expect(preview.localStateChanged, isTrue);
      expect(preview.intent, isNull);

      final commit = resolver.commitSeekPreview();
      expect(
        commit.intent,
        isA<SeekToPlaybackIntent>().having(
          (intent) => intent.position,
          'position',
          const Duration(minutes: 18, seconds: 47),
        ),
      );
      expect(resolver.seekPreviewPosition, isNull);
      expect(resolver.commitSeekPreview().intent, isNull);
    });

    test('accumulates audiobook preview across updates before commit', () {
      final resolver = WheelIntentResolver();
      final playback = _snapshot(
        position: const Duration(hours: 1),
        trackLength: const Duration(hours: 10),
      );

      final first = resolver.resolve(
        gesture: WheelGesture(turnDelta: _turnDeltaForDetents(12)),
        mode: WheelMode.seek,
        playback: playback,
      );
      expect(
        first.localStateChanged,
        isTrue,
      );
      expect(first.intent, isNull);
      expect(resolver.seekPreviewPosition, const Duration(hours: 2));

      final second = resolver.resolve(
        gesture: WheelGesture(turnDelta: _turnDeltaForDetents(12)),
        mode: WheelMode.seek,
        playback: playback,
      );
      expect(
        second.localStateChanged,
        isTrue,
      );
      expect(second.intent, isNull);
      expect(resolver.seekPreviewPosition, const Duration(hours: 3));

      final commit = resolver.commitSeekPreview();
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
        gesture: WheelGesture(turnDelta: _turnDeltaForDetents(1)),
        mode: WheelMode.seek,
        playback: _snapshot(),
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

double _turnDeltaForDetents(int detents) => detents / wheelDetentsPerTurn;
