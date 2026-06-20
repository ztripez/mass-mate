import 'package:flutter_test/flutter_test.dart';
import 'package:mass_mate/playback/playback_intent.dart';
import 'package:mass_mate/playback/seek_model.dart';
import 'package:mass_mate/wheel/wheel_gesture.dart';

import 'playback_test_helpers.dart';

void main() {
  test('SeekModel keeps short tracks controllable with slow movement', () {
    const model = SeekModel();

    final result = model.preview(
      gesture: WheelGesture(turnDelta: turnDeltaForDetents(1)),
      playback: snapshot(
        position: const Duration(minutes: 1),
        trackLength: const Duration(minutes: 3),
      ),
    );

    expect(result.position, const Duration(minutes: 1, seconds: 5));
    expect(result.boundary, isNull);
  });

  test('SeekModel uses faster bands for long-form movement', () {
    const model = SeekModel();

    final fast = model.preview(
      gesture: WheelGesture(turnDelta: turnDeltaForDetents(4)),
      playback: snapshot(
        position: const Duration(hours: 1),
        trackLength: const Duration(hours: 10),
      ),
    );
    final veryFast = model.preview(
      gesture: WheelGesture(turnDelta: turnDeltaForDetents(12)),
      playback: snapshot(
        position: const Duration(hours: 1),
        trackLength: const Duration(hours: 10),
      ),
    );

    expect(fast.position, const Duration(hours: 1, minutes: 4));
    expect(veryFast.position, const Duration(hours: 2));
  });

  test('SeekModel uses normal band for ordinary music-length movement', () {
    const model = SeekModel();

    final result = model.preview(
      gesture: WheelGesture(turnDelta: turnDeltaForDetents(2)),
      playback: snapshot(
        position: const Duration(minutes: 1),
        trackLength: const Duration(minutes: 4),
      ),
    );

    expect(result.position, const Duration(minutes: 1, seconds: 30));
    expect(result.boundary, isNull);
  });

  test('SeekModel clamps audiobook preview at endpoints', () {
    const model = SeekModel();

    final minimum = model.preview(
      gesture: WheelGesture(turnDelta: turnDeltaForDetents(-12)),
      playback: snapshot(
        position: const Duration(minutes: 10),
        trackLength: const Duration(hours: 10),
      ),
    );
    final maximum = model.preview(
      gesture: WheelGesture(turnDelta: turnDeltaForDetents(12)),
      playback: snapshot(
        position: const Duration(hours: 9, minutes: 30),
        trackLength: const Duration(hours: 10),
      ),
    );

    expect(minimum.position, Duration.zero);
    expect(minimum.boundary, PlaybackRangeBoundary.minimum);
    expect(maximum.position, const Duration(hours: 10));
    expect(maximum.boundary, PlaybackRangeBoundary.maximum);
  });

  test('SeekModel rejects invalid current preview state', () {
    const model = SeekModel();

    expectArgumentErrorNamed(
      'currentPreview',
      () => model.preview(
        gesture: WheelGesture(turnDelta: turnDeltaForDetents(1)),
        playback: snapshot(
          position: const Duration(minutes: 1),
          trackLength: const Duration(minutes: 3),
        ),
        currentPreview: const Duration(seconds: -1),
      ),
    );
    expectArgumentErrorNamed(
      'currentPreview',
      () => model.preview(
        gesture: WheelGesture(turnDelta: turnDeltaForDetents(1)),
        playback: snapshot(
          position: const Duration(minutes: 1),
          trackLength: const Duration(minutes: 3),
        ),
        currentPreview: const Duration(minutes: 4),
      ),
    );
  });
}
