import 'package:flutter_test/flutter_test.dart';
import 'package:mass_mate/wheel/wheel_gesture.dart';

import 'playback_test_helpers.dart';

void main() {
  test('WheelGesture rejects non-finite turn deltas', () {
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
}
