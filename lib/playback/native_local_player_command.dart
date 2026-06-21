import 'playback_intent.dart';

/// Intent-level command names sent over the native bridge.
///
/// These are Mass Mate operation names derived from [PlaybackIntent], not raw
/// protocol command names from the native player transport.
enum LocalPlayerCommand {
  /// Toggle play or pause.
  togglePlayPause,

  /// Seek to a committed absolute position.
  seekTo,

  /// Set the absolute volume.
  setVolume,

  /// Select an item in the active queue.
  selectQueueItem,
}

/// Intent-level command envelope sent from Dart to native Android.
final class LocalPlayerCommandEnvelope {
  /// Creates a local-player command envelope.
  const LocalPlayerCommandEnvelope({required this.command, this.arguments});

  /// Command name derived from a Mass Mate playback intent.
  final LocalPlayerCommand command;

  /// Command-specific scalar arguments.
  final Map<String, Object?>? arguments;

  /// Converts a playback intent into a bridge command envelope.
  factory LocalPlayerCommandEnvelope.fromIntent(PlaybackIntent intent) {
    return switch (intent) {
      TogglePlayPausePlaybackIntent() => const LocalPlayerCommandEnvelope(
          command: LocalPlayerCommand.togglePlayPause,
        ),
      SeekToPlaybackIntent(:final position) => LocalPlayerCommandEnvelope(
          command: LocalPlayerCommand.seekTo,
          arguments: {'positionMs': position.inMilliseconds},
        ),
      SetVolumePlaybackIntent(:final volume) => LocalPlayerCommandEnvelope(
          command: LocalPlayerCommand.setVolume,
          arguments: {'volume': volume},
        ),
      SelectQueueItemPlaybackIntent(:final queueIndex) =>
        LocalPlayerCommandEnvelope(
          command: LocalPlayerCommand.selectQueueItem,
          arguments: {'queueIndex': queueIndex},
        ),
    };
  }

  /// Serializes this envelope for MethodChannel transport.
  Map<String, Object?> toMap() {
    return {
      'command': command.name,
      if (arguments != null) 'arguments': arguments,
    };
  }
}
