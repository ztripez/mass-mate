import 'playback_intent.dart';
import 'player_state.dart';

/// Applies a playback intent to a local [PlayerState] projection.
///
/// The projection is used by the local demo adapter and by the Music Assistant seam after
/// a command has been accepted by the configured client. The returned state is a new
/// [PlayerState]; [state] is not mutated. Seek preview remains outside this function, so
/// only committed [SeekToPlaybackIntent] values update playback position.
///
/// [TogglePlayPausePlaybackIntent] flips play/pause state, [SeekToPlaybackIntent] updates
/// committed position, [SetVolumePlaybackIntent] updates absolute volume, and
/// [SelectQueueItemPlaybackIntent] updates the selected one-based queue index.
PlayerState projectPlaybackIntent(PlayerState state, PlaybackIntent intent) {
  final playback = state.playback;

  return switch (intent) {
    TogglePlayPausePlaybackIntent() =>
      state.copyWith(isPlaying: !state.isPlaying),
    SeekToPlaybackIntent(:final position) => state.copyWith(
        playback: playback.copyWith(position: position),
      ),
    SetVolumePlaybackIntent(:final volume) => state.copyWith(
        playback: playback.copyWith(volume: volume),
      ),
    SelectQueueItemPlaybackIntent(:final queueIndex) => state.copyWith(
        playback: playback.copyWith(queueIndex: queueIndex),
      ),
  };
}
