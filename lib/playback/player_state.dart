import 'playback_snapshot.dart';

/// Metadata for the media item currently shown on the player surface.
final class PlaybackMediaItem {
  /// Creates display metadata for the active media item.
  const PlaybackMediaItem({required this.title, required this.subtitle});

  /// Primary title shown in the now-playing card.
  final String title;

  /// Secondary artist, album, podcast, or audiobook label shown under [title].
  final String subtitle;
}

/// Complete player state consumed by the Mass Mate player UI.
///
/// [PlaybackSnapshot] remains the canonical representation of playback position,
/// duration, volume, and queue cursor values. This state object adds player identity,
/// play/pause state, and current media metadata around that canonical snapshot.
final class PlayerState {
  /// Creates a player state snapshot for one playback target.
  const PlayerState({
    required this.playerName,
    required this.connectionLabel,
    required this.mediaItem,
    required this.playback,
    required this.isPlaying,
  });

  /// Creates the local audiobook demo state used before real Music Assistant integration.
  factory PlayerState.localDemo() {
    return PlayerState(
      playerName: 'Mass Mate',
      connectionLabel: 'Local demo',
      mediaItem: const PlaybackMediaItem(
        title: 'Chapter 12: Night Drive',
        subtitle: 'The Long Way Home • Audiobook',
      ),
      playback: PlaybackSnapshot(
        position: const Duration(minutes: 18, seconds: 42),
        trackLength: const Duration(hours: 1, minutes: 24, seconds: 18),
        volume: 0.62,
        queueIndex: 3,
        queueMinIndex: 1,
        queueMaxIndex: 24,
      ),
      isPlaying: true,
    );
  }

  /// Human-readable player or app name shown in the connection header.
  final String playerName;

  /// Connection status label shown beside [playerName].
  final String connectionLabel;

  /// Current media metadata displayed in the now-playing card.
  final PlaybackMediaItem mediaItem;

  /// Canonical playback position, duration, volume, and queue state.
  final PlaybackSnapshot playback;

  /// Whether the active target is currently playing.
  final bool isPlaying;

  /// Creates a copy of this state with selected fields replaced.
  ///
  /// Non-null arguments replace the matching fields. Passing `null` or omitting an argument
  /// preserves the current value for that field.
  PlayerState copyWith({
    String? playerName,
    String? connectionLabel,
    PlaybackMediaItem? mediaItem,
    PlaybackSnapshot? playback,
    bool? isPlaying,
  }) {
    return PlayerState(
      playerName: playerName ?? this.playerName,
      connectionLabel: connectionLabel ?? this.connectionLabel,
      mediaItem: mediaItem ?? this.mediaItem,
      playback: playback ?? this.playback,
      isPlaying: isPlaying ?? this.isPlaying,
    );
  }
}
