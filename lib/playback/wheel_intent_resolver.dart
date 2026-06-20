import '../wheel/wheel_gesture.dart';
import '../wheel_mode.dart';
import 'playback_intent.dart';
import 'playback_snapshot.dart';
import 'seek_model.dart';

const double _volumePerTurn = 0.18;
const double _queueItemsPerTurn = 12;

/// Result of mapping wheel input to playback behavior and feedback.
final class WheelIntentResolution {
  /// Creates a resolver result with optional playback intent, local update, and boundary hit.
  const WheelIntentResolution({
    this.intent,
    this.boundaryHit,
    this.localStateChanged = false,
  });

  /// Creates a resolver result that contains neither a playback intent nor boundary feedback.
  const WheelIntentResolution.none()
      : intent = null,
        boundaryHit = null,
        localStateChanged = false;

  /// Playback command produced by the wheel, if any.
  final PlaybackIntent? intent;

  /// Range boundary that newly needs feedback, if any.
  final PlaybackBoundaryHit? boundaryHit;

  /// Whether resolver-owned local state changed without producing a playback command.
  final bool localStateChanged;
}

/// Stateful mapper that converts click-wheel movement into playback intents and boundary feedback.
///
/// The resolver tracks the active [WheelMode], maintains an uncommitted seek preview
/// target for [WheelMode.seek], preserves fractional queue movement between calls to
/// [resolve], and suppresses repeated boundary feedback until the controlled playback
/// value leaves the boundary. Seek preview commits and cancellation are explicit through
/// [commitSeekPreview] and [cancelSeekPreview].
class WheelIntentResolver {
  /// Creates a wheel intent resolver that uses [seekModel] for seek preview movement.
  WheelIntentResolver({SeekModel seekModel = const SeekModel()})
      : _seekModel = seekModel;

  final SeekModel _seekModel;
  WheelMode? _activeMode;
  double _queueRemainderItems = 0;
  Duration? _seekPreviewPosition;
  PlaybackBoundaryHit? _lastBoundaryHit;

  /// Current local seek preview target, if seek preview is active.
  Duration? get seekPreviewPosition => _seekPreviewPosition;

  /// Whether a local seek preview is active and waiting for commit or cancellation.
  bool get hasActiveSeekPreview => _seekPreviewPosition != null;

  bool _selectMode(WheelMode mode) {
    if (_activeMode == mode) return false;

    final clearedSeekPreview =
        mode != WheelMode.seek && _seekPreviewPosition != null;
    _activeMode = mode;
    _queueRemainderItems = 0;
    if (mode != WheelMode.seek) _seekPreviewPosition = null;
    _lastBoundaryHit = null;
    return clearedSeekPreview;
  }

  /// Activates [mode] and clears state that must not carry across wheel modes.
  ///
  /// Call this when UI state changes modes without a wheel gesture. Activating a new
  /// non-seek mode clears any active seek preview, fractional queue movement, and remembered
  /// boundary feedback from the previous mode. Calling this with the currently active mode
  /// is a no-op.
  void activateMode(WheelMode mode) => _selectMode(mode);

  /// Maps a click-wheel [gesture] into playback behavior for [mode].
  ///
  /// The [playback] snapshot supplies the committed playback position, track length,
  /// volume, and selectable queue bounds. In [WheelMode.seek], nonzero movement updates
  /// the resolver-owned local preview target and returns a result with
  /// [WheelIntentResolution.localStateChanged] set; use [commitSeekPreview] to convert the
  /// active preview into a [SeekToPlaybackIntent] or [cancelSeekPreview] to discard it.
  /// In [WheelMode.volume], movement returns a
  /// [SetVolumePlaybackIntent] clamped to `0.0..1.0`. In [WheelMode.queue], fractional
  /// movement accumulates until at least one whole queue item is available, then returns a
  /// [SelectQueueItemPlaybackIntent] clamped to the selectable queue range.
  ///
  /// Returns a local-state result when a mode change cancels an active seek preview without
  /// producing a playback intent. Otherwise returns [WheelIntentResolution.none] when
  /// [gesture] has no movement or queue movement has not crossed a whole-item step. The
  /// returned [WheelIntentResolution] may include a [PlaybackBoundaryHit] when the mapped
  /// value newly reaches a range boundary.
  WheelIntentResolution resolve({
    required WheelGesture gesture,
    required WheelMode mode,
    required PlaybackSnapshot playback,
  }) {
    final modeChangeCanceledPreview = _selectMode(mode);
    if (gesture.turnDelta == 0) {
      return modeChangeCanceledPreview
          ? const WheelIntentResolution(localStateChanged: true)
          : const WheelIntentResolution.none();
    }

    final resolution = switch (mode) {
      WheelMode.seek => _resolveSeek(gesture, playback),
      WheelMode.volume => _resolveVolume(gesture, playback),
      WheelMode.queue => _resolveQueue(gesture, playback),
    };

    if (modeChangeCanceledPreview &&
        resolution.intent == null &&
        !resolution.localStateChanged) {
      return WheelIntentResolution(
        boundaryHit: resolution.boundaryHit,
        localStateChanged: true,
      );
    }

    return resolution;
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

  /// Commits the active local seek preview as an absolute playback seek intent.
  ///
  /// Returns [WheelIntentResolution.none] when no seek preview is active. A successful
  /// commit clears the preview so later wheel input starts from the next committed
  /// playback position supplied to [resolve].
  WheelIntentResolution commitSeekPreview() {
    final previewPosition = _seekPreviewPosition;
    if (previewPosition == null) return const WheelIntentResolution.none();

    _seekPreviewPosition = null;
    return WheelIntentResolution(
      intent: SeekToPlaybackIntent(previewPosition),
    );
  }

  /// Cancels any active local seek preview without producing a playback intent.
  ///
  /// Canceling also clears remembered boundary feedback so a discarded seek preview cannot
  /// suppress haptics for the next seek gesture.
  void cancelSeekPreview() {
    _seekPreviewPosition = null;
    _lastBoundaryHit = null;
  }

  WheelIntentResolution _resolveSeek(
    WheelGesture gesture,
    PlaybackSnapshot playback,
  ) {
    final preview = _seekModel.preview(
      gesture: gesture,
      playback: playback,
      currentPreview: _seekPreviewPosition,
    );
    _seekPreviewPosition = preview.position;

    return WheelIntentResolution(
      localStateChanged: true,
      boundaryHit: _feedbackForBoundary(WheelMode.seek, preview.boundary),
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
