/// Formats a non-negative media duration for playback UI labels.
///
/// Durations shorter than one hour use `MM:SS`. Durations of at least one hour
/// use `H:MM:SS` so long-form podcasts and audiobooks keep their hour value
/// visible instead of wrapping back to minutes.
///
/// Throws an [ArgumentError] when [duration] is negative.
String formatPlaybackDuration(Duration duration) {
  if (duration < Duration.zero) {
    throw ArgumentError.value(duration, 'duration', 'must be non-negative');
  }

  final hours = duration.inHours;
  final minutes = duration.inMinutes.remainder(60).toString().padLeft(2, '0');
  final seconds = duration.inSeconds.remainder(60).toString().padLeft(2, '0');
  if (hours > 0) return '$hours:$minutes:$seconds';

  return '$minutes:$seconds';
}
