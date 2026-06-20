import 'package:flutter_test/flutter_test.dart';

import 'playback_test_helpers.dart';

void main() {
  test('PlaybackSnapshot rejects each invalid invariant with specific errors',
      () {
    expectArgumentErrorNamed(
      'position',
      () => snapshot(position: const Duration(seconds: -1)),
    );
    expectArgumentErrorNamed(
      'trackLength',
      () => snapshot(position: Duration.zero, trackLength: Duration.zero),
    );
    expectArgumentErrorNamed(
      'trackLength',
      () => snapshot(
        position: Duration.zero,
        trackLength: const Duration(seconds: -1),
      ),
    );
    expectArgumentErrorNamed(
      'position',
      () => snapshot(position: const Duration(hours: 1)),
    );
    expectArgumentErrorNamed('volume', () => snapshot(volume: -0.01));
    expectArgumentErrorNamed('volume', () => snapshot(volume: 1.01));
    expectArgumentErrorNamed('volume', () => snapshot(volume: double.nan));
    expectArgumentErrorNamed(
      'volume',
      () => snapshot(volume: double.infinity),
    );
    expectArgumentErrorNamed(
      'volume',
      () => snapshot(volume: double.negativeInfinity),
    );
    expectArgumentErrorNamed('queueMinIndex', () => snapshot(queueMinIndex: 0));
    expectArgumentErrorNamed(
        'queueMinIndex', () => snapshot(queueMinIndex: -1));
    expectArgumentErrorNamed(
      'queueMinIndex',
      () => snapshot(queueMinIndex: 10, queueMaxIndex: 1),
    );
    expectArgumentErrorNamed('queueIndex', () => snapshot(queueIndex: 0));
    expectArgumentErrorNamed('queueIndex', () => snapshot(queueIndex: 25));
  });
}
