/// Current playback values used to map relative click-wheel input to previews, playback intents, and boundary feedback.
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
