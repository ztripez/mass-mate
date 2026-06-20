import 'package:flutter_test/flutter_test.dart';
import 'package:mass_mate/playback/playback_snapshot.dart';
import 'package:mass_mate/wheel/wheel_constants.dart';

PlaybackSnapshot snapshot({
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

double turnDeltaForDetents(int detents) => detents / wheelDetentsPerTurn;
