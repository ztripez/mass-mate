import '../wheel_mode.dart';

/// Range edge reached by a wheel-controlled playback value.
enum PlaybackRangeBoundary {
  /// The controlled value reached its minimum.
  minimum,

  /// The controlled value reached its maximum.
  maximum,
}

/// Playback command resolved from click-wheel input.
sealed class PlaybackIntent {
  /// Initializes shared state for concrete playback intent variants in this library.
  const PlaybackIntent();
}

/// Requests playback seek to an absolute position in the active item.
final class SeekToPlaybackIntent extends PlaybackIntent {
  /// Creates an absolute seek request.
  const SeekToPlaybackIntent(this.position);

  /// Target playback position.
  final Duration position;
}

/// Requests an absolute playback volume level from 0.0 to 1.0.
final class SetVolumePlaybackIntent extends PlaybackIntent {
  /// Creates an absolute volume request.
  const SetVolumePlaybackIntent(this.volume);

  /// Target playback volume from muted to full scale.
  final double volume;
}

/// Requests selection of an absolute queue item index.
final class SelectQueueItemPlaybackIntent extends PlaybackIntent {
  /// Creates an absolute queue selection request.
  const SelectQueueItemPlaybackIntent(this.queueIndex);

  /// One-based queue index selected by the wheel.
  final int queueIndex;
}

/// Identifies a mode-specific playback boundary hit.
final class PlaybackBoundaryHit {
  /// Creates a playback boundary identifier.
  const PlaybackBoundaryHit({required this.mode, required this.boundary});

  /// Wheel mode whose mapped playback value reached a boundary.
  final WheelMode mode;

  /// Range edge reached by the mapped playback value.
  final PlaybackRangeBoundary boundary;

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        other is PlaybackBoundaryHit &&
            other.mode == mode &&
            other.boundary == boundary;
  }

  @override
  int get hashCode => Object.hash(mode, boundary);
}
