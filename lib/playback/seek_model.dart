import '../wheel/wheel_gesture.dart';
import '../wheel/wheel_constants.dart';
import 'playback_intent.dart';
import 'playback_snapshot.dart';

/// Speed band selected from the amount of wheel motion in a single gesture update.
enum SeekSpeedBand {
  /// Fine movement for small adjustments near the current position.
  slow(secondsPerDetent: 5),

  /// Default movement for ordinary music-length seeking.
  normal(secondsPerDetent: 15),

  /// Accelerated movement for long songs and podcast chapters.
  fast(secondsPerDetent: 60),

  /// Large movement for multi-hour audiobook scrubbing.
  veryFast(secondsPerDetent: 300);

  /// Creates a seek speed band with the number of seek seconds applied per click-wheel detent.
  const SeekSpeedBand({required this.secondsPerDetent});

  /// Seek seconds applied for each wheel detent in this band.
  final int secondsPerDetent;
}

/// Result of updating a local seek preview target.
final class SeekPreviewResolution {
  /// Creates a seek preview result for [position] in [speedBand].
  const SeekPreviewResolution({
    required this.position,
    required this.speedBand,
    required this.boundary,
  });

  /// Preview target after applying the wheel gesture.
  final Duration position;

  /// Speed band used to map the gesture into a seek delta.
  final SeekSpeedBand speedBand;

  /// Playback boundary reached by the preview target, if any.
  final PlaybackRangeBoundary? boundary;
}

/// Duration-aware and velocity-aware model for local seek preview updates.
///
/// The model maps wheel movement to preview targets without committing playback. The
/// gesture magnitude acts as a velocity proxy: larger per-update movement selects faster
/// bands so short tracks remain controllable while long-form media can be scrubbed with
/// fewer turns. Callers provide the current preview target, when one exists, so preview
/// state remains explicit and commit policy stays outside this pure model.
class SeekModel {
  /// Creates the default seek model tuned for touch-wheel media seeking.
  const SeekModel();

  /// Returns the local seek preview target produced by [gesture].
  ///
  /// The [playback] snapshot supplies the committed position and playable range. The
  /// optional [currentPreview] is used as the base for continued preview movement and
  /// must already be inside the inclusive `0..trackLength` range; when it is absent,
  /// seeking starts from [PlaybackSnapshot.position]. The returned preview clamps gesture
  /// overshoot to the inclusive `0..trackLength` range.
  ///
  /// Throws an [ArgumentError] when [currentPreview] is negative or greater than
  /// [PlaybackSnapshot.trackLength].
  SeekPreviewResolution preview({
    required WheelGesture gesture,
    required PlaybackSnapshot playback,
    Duration? currentPreview,
  }) {
    if (currentPreview != null &&
        (currentPreview < Duration.zero ||
            currentPreview > playback.trackLength)) {
      throw ArgumentError.value(
        currentPreview,
        'currentPreview',
        'must be within 0..trackLength',
      );
    }

    final detents = gesture.turnDelta * wheelDetentsPerTurn;
    final speedBand = _bandForDetents(detents.abs());
    final delta = Duration(
      milliseconds: (detents * speedBand.secondsPerDetent * 1000).round(),
    );
    final base = currentPreview ?? playback.position;
    final nextMilliseconds = base.inMilliseconds + delta.inMilliseconds;
    final clampedMilliseconds = nextMilliseconds.clamp(
      0,
      playback.trackLength.inMilliseconds,
    );
    final position = Duration(milliseconds: clampedMilliseconds.toInt());

    return SeekPreviewResolution(
      position: position,
      speedBand: speedBand,
      boundary: _boundaryFor(position, playback.trackLength),
    );
  }

  SeekSpeedBand _bandForDetents(double detents) {
    if (detents < 1.5) return SeekSpeedBand.slow;
    if (detents < 3) return SeekSpeedBand.normal;
    if (detents < 8) return SeekSpeedBand.fast;
    return SeekSpeedBand.veryFast;
  }

  PlaybackRangeBoundary? _boundaryFor(Duration position, Duration trackLength) {
    if (position == Duration.zero) return PlaybackRangeBoundary.minimum;
    if (position == trackLength) return PlaybackRangeBoundary.maximum;
    return null;
  }
}
