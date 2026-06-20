import '../wheel/wheel_gesture.dart';
import '../wheel_mode.dart';
import 'playback_intent.dart';

const double _seekSecondsPerTurn = 75;
const double _volumePerTurn = 0.18;
const double _queueItemsPerTurn = 12;

/// Current playback values needed to map relative wheel input to absolute intents.
final class PlaybackSnapshot {
  /// Creates a validated snapshot of active playback state and selectable queue bounds.
  ///
  /// The [position] value is the committed playback position and must be non-negative.
  /// The [trackLength] value is the playable duration of the active item and must be
  /// positive. The [position] value must not be greater than [trackLength]. The [volume]
  /// value must be finite and between `0.0` for muted output and `1.0` for full output.
  /// The [queueMinIndex] and [queueMaxIndex] values define a one-based inclusive
  /// selectable queue range, and [queueIndex] must be within that inclusive range.
  ///
  /// Throws an [ArgumentError] when any supplied value violates the playback position,
  /// track length, volume, or queue range invariants.
  PlaybackSnapshot({
    required this.position,
    required this.trackLength,
    required this.volume,
    required this.queueIndex,
    required this.queueMinIndex,
    required this.queueMaxIndex,
  }) {
    if (position < Duration.zero) {
      throw ArgumentError.value(position, 'position', 'must be non-negative');
    }
    if (trackLength <= Duration.zero) {
      throw ArgumentError.value(trackLength, 'trackLength', 'must be positive');
    }
    if (position > trackLength) {
      throw ArgumentError.value(
        position,
        'position',
        'must not be greater than trackLength',
      );
    }
    if (!volume.isFinite || volume < 0 || volume > 1) {
      throw ArgumentError.value(
        volume,
        'volume',
        'must be finite between 0.0 and 1.0',
      );
    }
    if (queueMinIndex < 1) {
      throw ArgumentError.value(
        queueMinIndex,
        'queueMinIndex',
        'must be at least 1',
      );
    }
    if (queueMinIndex > queueMaxIndex) {
      throw ArgumentError.value(
        queueMinIndex,
        'queueMinIndex',
        'must not be greater than queueMaxIndex',
      );
    }
    if (queueIndex < queueMinIndex || queueIndex > queueMaxIndex) {
      throw ArgumentError.value(
        queueIndex,
        'queueIndex',
        'must be within the inclusive queue range',
      );
    }
  }

  /// Current committed playback position.
  final Duration position;

  /// Playable length of the active item.
  final Duration trackLength;

  /// Current absolute volume from 0.0 to 1.0.
  final double volume;

  /// Current one-based queue cursor.
  final int queueIndex;

  /// Minimum selectable queue index.
  final int queueMinIndex;

  /// Maximum selectable queue index.
  final int queueMaxIndex;

  /// Creates a validated copy of the current playback snapshot with selected fields replaced.
  ///
  /// Any omitted parameter keeps the corresponding value from the current
  /// [PlaybackSnapshot]. Supplied [position], [trackLength], [volume], [queueIndex],
  /// [queueMinIndex], and [queueMaxIndex] values are validated with the same invariants
  /// as the [PlaybackSnapshot] constructor.
  ///
  /// Throws an [ArgumentError] when the combined copied and replaced values violate
  /// the playback position, track length, volume, or queue range invariants.
  PlaybackSnapshot copyWith({
    Duration? position,
    Duration? trackLength,
    double? volume,
    int? queueIndex,
    int? queueMinIndex,
    int? queueMaxIndex,
  }) {
    return PlaybackSnapshot(
      position: position ?? this.position,
      trackLength: trackLength ?? this.trackLength,
      volume: volume ?? this.volume,
      queueIndex: queueIndex ?? this.queueIndex,
      queueMinIndex: queueMinIndex ?? this.queueMinIndex,
      queueMaxIndex: queueMaxIndex ?? this.queueMaxIndex,
    );
  }
}

/// Result of mapping wheel input to playback behavior and feedback.
final class WheelIntentResolution {
  /// Creates a resolver result with an optional playback intent and boundary hit.
  const WheelIntentResolution({this.intent, this.boundaryHit});

  /// Creates a resolver result for wheel input that has not crossed a behavior step.
  const WheelIntentResolution.none()
      : intent = null,
        boundaryHit = null;

  /// Playback command produced by the wheel, if any.
  final PlaybackIntent? intent;

  /// Range boundary that newly needs feedback, if any.
  final PlaybackBoundaryHit? boundaryHit;
}

/// Stateful mapper that converts click-wheel movement into playback intents and boundary feedback.
///
/// The resolver tracks the active [WheelMode], preserves fractional seek and queue movement
/// between calls to [resolve], and suppresses repeated boundary feedback until the
/// controlled playback value leaves the boundary.
class WheelIntentResolver {
  WheelMode? _activeMode;
  double _seekRemainderSeconds = 0;
  double _queueRemainderItems = 0;
  PlaybackBoundaryHit? _lastBoundaryHit;

  void _selectMode(WheelMode mode) {
    if (_activeMode == mode) return;

    _activeMode = mode;
    _seekRemainderSeconds = 0;
    _queueRemainderItems = 0;
    _lastBoundaryHit = null;
  }

  /// Maps a click-wheel [gesture] into an absolute playback command for [mode].
  ///
  /// The [playback] snapshot supplies the current position, volume, and queue bounds used
  /// to clamp the resolved command. Seek and queue modes accumulate fractional wheel
  /// movement until at least one whole second or queue item is available. Returns
  /// [WheelIntentResolution.none] when [gesture] has no movement or the accumulated
  /// movement has not crossed a behavior step. The returned [WheelIntentResolution] may
  /// include a [PlaybackBoundaryHit] when the mapped value newly reaches a range boundary.
  WheelIntentResolution resolve({
    required WheelGesture gesture,
    required WheelMode mode,
    required PlaybackSnapshot playback,
  }) {
    if (_activeMode != mode) _selectMode(mode);
    if (gesture.turnDelta == 0) return const WheelIntentResolution.none();

    switch (mode) {
      case WheelMode.seek:
        return _resolveSeek(gesture, playback);
      case WheelMode.volume:
        return _resolveVolume(gesture, playback);
      case WheelMode.queue:
        return _resolveQueue(gesture, playback);
    }
  }

  /// Clears the remembered [boundaryHit] so the same playback boundary can emit feedback again.
  ///
  /// The [boundaryHit] parameter identifies the wheel mode and minimum or maximum playback
  /// range edge whose haptic feedback failed. The resolver leaves the remembered boundary
  /// unchanged when [boundaryHit] does not match the most recent boundary returned by
  /// [resolve].
  void forgetBoundaryHit(PlaybackBoundaryHit boundaryHit) {
    if (_lastBoundaryHit == boundaryHit) {
      _lastBoundaryHit = null;
    }
  }

  WheelIntentResolution _resolveSeek(
    WheelGesture gesture,
    PlaybackSnapshot playback,
  ) {
    _seekRemainderSeconds += gesture.turnDelta * _seekSecondsPerTurn;
    final deltaSeconds = _consumeWholeUnits(_seekRemainderSeconds);
    _seekRemainderSeconds -= deltaSeconds;

    if (deltaSeconds == 0) return const WheelIntentResolution.none();

    final nextSeconds = playback.position.inSeconds + deltaSeconds;
    final clampedSeconds =
        nextSeconds.clamp(0, playback.trackLength.inSeconds).toInt();
    final boundary = _boundaryForRange(
      clampedSeconds,
      0,
      playback.trackLength.inSeconds,
    );

    return WheelIntentResolution(
      intent: SeekToPlaybackIntent(Duration(seconds: clampedSeconds)),
      boundaryHit: _feedbackForBoundary(WheelMode.seek, boundary),
    );
  }

  WheelIntentResolution _resolveVolume(
    WheelGesture gesture,
    PlaybackSnapshot playback,
  ) {
    final nextVolume = (playback.volume + gesture.turnDelta * _volumePerTurn)
        .clamp(0.0, 1.0)
        .toDouble();
    final boundary = _boundaryForRange(nextVolume, 0, 1);

    return WheelIntentResolution(
      intent: SetVolumePlaybackIntent(nextVolume),
      boundaryHit: _feedbackForBoundary(WheelMode.volume, boundary),
    );
  }

  WheelIntentResolution _resolveQueue(
    WheelGesture gesture,
    PlaybackSnapshot playback,
  ) {
    _queueRemainderItems += gesture.turnDelta * _queueItemsPerTurn;
    final deltaItems = _consumeWholeUnits(_queueRemainderItems);
    _queueRemainderItems -= deltaItems;

    if (deltaItems == 0) return const WheelIntentResolution.none();

    final nextQueueIndex = (playback.queueIndex + deltaItems)
        .clamp(playback.queueMinIndex, playback.queueMaxIndex)
        .toInt();
    final boundary = _boundaryForRange(
      nextQueueIndex,
      playback.queueMinIndex,
      playback.queueMaxIndex,
    );

    return WheelIntentResolution(
      intent: SelectQueueItemPlaybackIntent(nextQueueIndex),
      boundaryHit: _feedbackForBoundary(WheelMode.queue, boundary),
    );
  }

  int _consumeWholeUnits(double value) => value.truncate();

  PlaybackRangeBoundary? _boundaryForRange(num value, num min, num max) {
    if (value <= min) return PlaybackRangeBoundary.minimum;
    if (value >= max) return PlaybackRangeBoundary.maximum;
    return null;
  }

  PlaybackBoundaryHit? _feedbackForBoundary(
    WheelMode mode,
    PlaybackRangeBoundary? boundary,
  ) {
    if (boundary == null) {
      _lastBoundaryHit = null;
      return null;
    }

    final boundaryHit = PlaybackBoundaryHit(mode: mode, boundary: boundary);
    if (_lastBoundaryHit == boundaryHit) return null;

    _lastBoundaryHit = boundaryHit;
    return boundaryHit;
  }
}
