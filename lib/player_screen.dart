import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'click_wheel.dart';
import 'haptics.dart';
import 'playback/playback_duration_format.dart';
import 'playback/playback_intent.dart';
import 'playback/player_adapter.dart';
import 'playback/player_state.dart';
import 'playback/wheel_intent_resolver.dart';
import 'wheel/wheel_gesture.dart';
import 'wheel_mode.dart';

/// Player screen that displays the local playback prototype and click-wheel controls.
class PlayerScreen extends StatefulWidget {
  /// Creates the local player prototype screen.
  const PlayerScreen({super.key, required this.playerAdapter});

  /// Playback adapter used by the screen.
  ///
  /// Supplying an adapter lets the same UI render local demo state, test fakes, or a future
  /// Music Assistant-backed player.
  final PlayerAdapter playerAdapter;

  @override
  State<PlayerScreen> createState() => _PlayerScreenState();
}

class _PlayerScreenState extends State<PlayerScreen> {
  static const double _compactPortraitWheelHeightFraction = 0.50;
  static const double _portraitWheelHeightFraction = 0.46;
  static const double _maxWideWheelDimension = 340;
  static const double _minWideWheelDimension = 300;

  WheelMode _mode = WheelMode.seek;
  final WheelIntentResolver _wheelIntentResolver = WheelIntentResolver();
  StreamSubscription<PlayerState>? _playerStateSubscription;
  String? _playbackError;

  PlayerAdapter get _playerAdapter => widget.playerAdapter;

  @override
  void initState() {
    super.initState();
    _subscribeToPlayerAdapter();
  }

  @override
  void didUpdateWidget(PlayerScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.playerAdapter == widget.playerAdapter) return;

    unawaited(_playerStateSubscription?.cancel());
    _subscribeToPlayerAdapter();
  }

  @override
  void dispose() {
    unawaited(_playerStateSubscription?.cancel());
    super.dispose();
  }

  void _subscribeToPlayerAdapter() {
    _playerStateSubscription = _playerAdapter.states.listen(
      (_) {
        if (!mounted) return;
        setState(() => _playbackError = null);
      },
      onError: (Object error) {
        if (!mounted) return;
        setState(() => _playbackError = _visiblePlaybackError(error));
      },
    );
  }

  void _handleWheelGesture(WheelGesture gesture) {
    final resolution = _wheelIntentResolver.resolve(
      gesture: gesture,
      mode: _mode,
      playback: _playerAdapter.state.playback,
    );

    _emitBoundaryFeedback(resolution.boundaryHit);

    final intent = resolution.intent;
    if (intent == null) {
      if (resolution.localStateChanged) setState(() {});
      return;
    }

    _dispatchPlaybackIntent(intent);
  }

  void _pulseSeekCommitHaptics() {
    unawaited(
      HapticFeedback.selectionClick()
          .catchError((Object error, StackTrace stackTrace) {
        WheelHaptics.reportFailure(
          context: 'while emitting a seek preview commit haptic',
          error: error,
          stackTrace: stackTrace,
        );
      }),
    );
  }

  void _dispatchPlaybackIntent(PlaybackIntent intent) {
    unawaited(_applyPlaybackIntent(intent));
  }

  Future<void> _applyPlaybackIntent(PlaybackIntent intent) async {
    try {
      await _playerAdapter.applyIntent(intent);
      if (!mounted) return;

      setState(() => _playbackError = null);
    } on Object catch (error) {
      if (!mounted) return;
      setState(() => _playbackError = _visiblePlaybackError(error));
    }
  }

  void _commitSeekPreview() {
    final resolution = _wheelIntentResolver.resolveSeekPreviewCommit();
    final intent = resolution.intent;
    if (intent == null) return;

    unawaited(_applySeekCommitIntent(intent));
  }

  Future<void> _applySeekCommitIntent(PlaybackIntent intent) async {
    try {
      await _playerAdapter.applyIntent(intent);
      if (!mounted) return;

      setState(() {
        _playbackError = null;
        _wheelIntentResolver.completeSeekPreviewCommit();
      });
      _pulseSeekCommitHaptics();
    } on Object catch (error) {
      if (!mounted) return;
      setState(() => _playbackError = _visiblePlaybackError(error));
    }
  }

  String _visiblePlaybackError(Object error) {
    return error is Exception ? error.toString() : 'Playback failed: $error';
  }

  void _handleWheelGestureEnded() {
    if (_mode == WheelMode.seek) _commitSeekPreview();
  }

  void _handleWheelGestureCanceled() {
    if (_mode == WheelMode.seek) {
      setState(_wheelIntentResolver.cancelSeekPreview);
    }
  }

  void _emitBoundaryFeedback(PlaybackBoundaryHit? boundaryHit) {
    if (boundaryHit == null) return;
    _pulseBoundaryHaptics(boundaryHit);
  }

  void _pulseBoundaryHaptics(PlaybackBoundaryHit boundaryHit) {
    unawaited(
      WheelHaptics.boundaryBuzz()
          .catchError((Object error, StackTrace stackTrace) {
        if (mounted) {
          _wheelIntentResolver.forgetBoundaryHit(boundaryHit);
        }
        WheelHaptics.reportFailure(
          context: 'while emitting a click-wheel boundary haptic',
          error: error,
          stackTrace: stackTrace,
        );
      }),
    );
  }

  void _setMode(WheelMode mode) {
    if (_mode == mode) return;

    setState(() {
      if (_mode == WheelMode.seek) _wheelIntentResolver.cancelSeekPreview();
      _wheelIntentResolver.activateMode(mode);
      _mode = mode;
    });
  }

  void _handleCenterPressed() {
    if (_mode == WheelMode.seek && _wheelIntentResolver.hasActiveSeekPreview) {
      _commitSeekPreview();
      return;
    }

    _cycleMode();
  }

  void _cycleMode() {
    final nextIndex = (_mode.index + 1) % WheelMode.values.length;
    _setMode(WheelMode.values[nextIndex]);
  }

  @override
  Widget build(BuildContext context) {
    final playerState = _playerAdapter.state;

    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
          child: LayoutBuilder(
            builder: (context, constraints) {
              if (constraints.maxWidth >= 720 &&
                  constraints.maxWidth > constraints.maxHeight) {
                final wheelDimension = _wideWheelDimension(constraints);
                if (wheelDimension == null) {
                  return const _UnsupportedViewportMessage();
                }

                return Column(
                  children: [
                    _ConnectionStatusBlock(
                      playerState: playerState,
                      playbackError: _playbackError,
                    ),
                    const SizedBox(height: 20),
                    Expanded(
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Expanded(child: _buildNowPlayingCard(playerState)),
                          const SizedBox(width: 24),
                          SizedBox(
                            width: wheelDimension,
                            child: _buildControls(
                              playerState: playerState,
                              wheelDimension: wheelDimension,
                              mainAxisAlignment: MainAxisAlignment.center,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                );
              }

              final wheelDimension = _portraitWheelDimension(constraints);
              if (wheelDimension == null) {
                return const _UnsupportedViewportMessage();
              }

              final isCompactLayout = constraints.maxHeight < 700;

              return Column(
                children: [
                  _ConnectionStatusBlock(
                    playerState: playerState,
                    playbackError: _playbackError,
                  ),
                  SizedBox(height: isCompactLayout ? 8 : 12),
                  Expanded(
                    child: _buildNowPlayingCard(
                      playerState,
                      compactLayout: isCompactLayout,
                    ),
                  ),
                  SizedBox(height: isCompactLayout ? 10 : 14),
                  _buildControls(
                    playerState: playerState,
                    wheelDimension: wheelDimension,
                    showModeSelector: false,
                  ),
                ],
              );
            },
          ),
        ),
      ),
    );
  }

  Widget _buildNowPlayingCard(
    PlayerState playerState, {
    bool compactLayout = false,
  }) {
    return _NowPlayingCard(
      playerState: playerState,
      seekPreviewPosition: _wheelIntentResolver.seekPreviewPosition,
      mode: _mode,
      compactLayout: compactLayout,
    );
  }

  Widget _buildControls({
    required PlayerState playerState,
    required double wheelDimension,
    MainAxisAlignment mainAxisAlignment = MainAxisAlignment.start,
    bool showModeSelector = true,
  }) {
    final hasActiveSeekPreview = _wheelIntentResolver.hasActiveSeekPreview;

    return Column(
      mainAxisSize: MainAxisSize.min,
      mainAxisAlignment: mainAxisAlignment,
      children: [
        if (showModeSelector) ...[
          _ModeSelector(
            selectedMode: _mode,
            onSelected: _setMode,
          ),
          const SizedBox(height: 20),
        ],
        ClickWheel(
          dimension: wheelDimension,
          semanticLabel: '${_mode.label} click wheel',
          semanticHint: _mode.description,
          centerSemanticLabel: _centerLabel(hasActiveSeekPreview),
          centerSemanticHint: _centerHint(hasActiveSeekPreview),
          modeSemanticLabel: 'Change wheel mode',
          modeSemanticHint:
              'Cancels active seek preview and moves to the next mode.',
          backSemanticLabel: _backLabel(),
          backSemanticHint: _backHint(),
          forwardSemanticLabel: _forwardLabel(),
          forwardSemanticHint: _forwardHint(),
          playPauseSemanticHint:
              'Toggles committed playback without committing seek preview.',
          isPlaying: playerState.isPlaying,
          onGesture: _handleWheelGesture,
          onGestureEnded: _handleWheelGestureEnded,
          onGestureCanceled: _handleWheelGestureCanceled,
          onCenterPressed: _handleCenterPressed,
          onModePressed: _cycleMode,
          onSkipBack: () => _handleWheelGesture(
            WheelGesture(turnDelta: -0.16),
          ),
          onSkipForward: () => _handleWheelGesture(
            WheelGesture(turnDelta: 0.16),
          ),
          onPlayPausePressed: () => _dispatchPlaybackIntent(
            const TogglePlayPausePlaybackIntent(),
          ),
        ),
      ],
    );
  }

  double? _portraitWheelDimension(BoxConstraints constraints) {
    final widthBound = constraints.maxWidth;
    final heightFraction = constraints.maxHeight < 700
        ? _compactPortraitWheelHeightFraction
        : _portraitWheelHeightFraction;
    final heightBound = constraints.maxHeight * heightFraction;
    final availableDimension = math.min(widthBound, heightBound);

    _validateFiniteWheelBudget(availableDimension);

    if (availableDimension < ClickWheel.minSupportedDimension) return null;

    return math.min(
      availableDimension,
      ClickWheel.maxSupportedDimension,
    );
  }

  double? _wideWheelDimension(BoxConstraints constraints) {
    final availableDimension = math.min(
      constraints.maxHeight * 0.72,
      _maxWideWheelDimension,
    );

    _validateFiniteWheelBudget(availableDimension);

    if (availableDimension < _minWideWheelDimension) return null;

    return availableDimension;
  }

  void _validateFiniteWheelBudget(double availableDimension) {
    if (!availableDimension.isFinite) {
      throw FlutterError(
        'PlayerScreen requires finite layout constraints to size the click wheel.',
      );
    }
  }

  String _centerLabel(bool hasActiveSeekPreview) {
    if (_mode == WheelMode.seek && hasActiveSeekPreview) {
      return 'Commit seek preview';
    }
    return 'Next wheel mode';
  }

  String _centerHint(bool hasActiveSeekPreview) {
    if (_mode == WheelMode.seek && hasActiveSeekPreview) {
      return 'Applies the previewed seek position.';
    }
    return 'Cycles to the next click-wheel mode.';
  }

  String _backLabel() {
    return switch (_mode) {
      WheelMode.seek => 'Seek backward preview',
      WheelMode.volume => 'Volume down',
      WheelMode.queue => 'Queue backward',
    };
  }

  String _backHint() {
    return switch (_mode) {
      WheelMode.seek => 'Moves the local seek preview backward.',
      WheelMode.volume => 'Lowers the local demo volume.',
      WheelMode.queue => 'Moves the queue cursor backward.',
    };
  }

  String _forwardLabel() {
    return switch (_mode) {
      WheelMode.seek => 'Seek forward preview',
      WheelMode.volume => 'Volume up',
      WheelMode.queue => 'Queue forward',
    };
  }

  String _forwardHint() {
    return switch (_mode) {
      WheelMode.seek => 'Moves the local seek preview forward.',
      WheelMode.volume => 'Raises the local demo volume.',
      WheelMode.queue => 'Moves the queue cursor forward.',
    };
  }
}

class _NowPlayingCard extends StatelessWidget {
  const _NowPlayingCard({
    required this.playerState,
    required this.seekPreviewPosition,
    required this.mode,
    required this.compactLayout,
  });

  final PlayerState playerState;
  final Duration? seekPreviewPosition;
  final WheelMode mode;
  final bool compactLayout;

  @override
  Widget build(BuildContext context) {
    final playback = playerState.playback;
    final mediaItem = playerState.mediaItem;
    final position = playback.position;
    final trackLength = playback.trackLength;
    final previewPosition = seekPreviewPosition;
    final displayPosition = previewPosition ?? position;
    final progress =
        displayPosition.inMilliseconds / trackLength.inMilliseconds;
    final isPreviewingSeek = previewPosition != null;

    return Card(
      child: LayoutBuilder(
        builder: (context, constraints) {
          final denseSpacing =
              isPreviewingSeek || compactLayout || constraints.maxHeight < 320;
          final hideSecondaryMetadata =
              isPreviewingSeek || compactLayout || constraints.maxHeight < 240;
          final padding = denseSpacing ? 14.0 : 24.0;
          final albumRadius = denseSpacing ? 22.0 : 28.0;
          final albumIconSize = denseSpacing ? 72.0 : 96.0;

          return Padding(
            padding: EdgeInsets.all(padding),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Container(
                    width: double.infinity,
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(albumRadius),
                      gradient: const LinearGradient(
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                        colors: [Color(0xFF263B80), Color(0xFF111827)],
                      ),
                    ),
                    child: Icon(Icons.album, size: albumIconSize),
                  ),
                ),
                SizedBox(height: denseSpacing ? 8 : 22),
                Text(
                  mediaItem.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: denseSpacing
                      ? Theme.of(context).textTheme.titleLarge
                      : Theme.of(context).textTheme.headlineSmall,
                ),
                if (!hideSecondaryMetadata) ...[
                  const SizedBox(height: 4),
                  Text(
                    mediaItem.subtitle,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                ],
                SizedBox(height: denseSpacing ? 8 : 18),
                LinearProgressIndicator(value: progress),
                const SizedBox(height: 8),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(formatPlaybackDuration(displayPosition)),
                    Text(
                      '-${formatPlaybackDuration(trackLength - displayPosition)}',
                    ),
                  ],
                ),
                SizedBox(height: denseSpacing ? 8 : 16),
                _StatusPill(
                  icon: isPreviewingSeek ? Icons.travel_explore : mode.icon,
                  text:
                      isPreviewingSeek ? 'Seek preview' : '${mode.label} mode',
                ),
                SizedBox(height: denseSpacing ? 8 : 10),
                if (isPreviewingSeek) ...[
                  _SeekPreviewHint(
                    target: formatPlaybackDuration(displayPosition),
                    committed: formatPlaybackDuration(position),
                  ),
                  const SizedBox(height: 8),
                ],
                if (!hideSecondaryMetadata)
                  Text(
                    'Volume ${(playback.volume * 100).round()}% • Queue item ${playback.queueIndex} of ${playback.queueMaxIndex}',
                  ),
              ],
            ),
          );
        },
      ),
    );
  }
}

class _SeekPreviewHint extends StatelessWidget {
  const _SeekPreviewHint({required this.target, required this.committed});

  final String target;
  final String committed;

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;

    return DecoratedBox(
      decoration: BoxDecoration(
        color: colorScheme.primaryContainer,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Preview target $target • committed $committed',
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    color: colorScheme.onPrimaryContainer,
                    fontWeight: FontWeight.w700,
                  ),
            ),
            const SizedBox(height: 4),
            Text(
              'Release or center to commit • MODE cancels',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: colorScheme.onPrimaryContainer,
                  ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ModeSelector extends StatelessWidget {
  const _ModeSelector({required this.selectedMode, required this.onSelected});

  final WheelMode selectedMode;
  final ValueChanged<WheelMode> onSelected;

  @override
  Widget build(BuildContext context) {
    return SegmentedButton<WheelMode>(
      segments: [
        for (final mode in WheelMode.values)
          ButtonSegment(
            value: mode,
            icon: Icon(mode.icon),
            label: Text(mode.label),
          ),
      ],
      selected: {selectedMode},
      onSelectionChanged: (selection) => onSelected(selection.single),
    );
  }
}

class _UnsupportedViewportMessage extends StatelessWidget {
  const _UnsupportedViewportMessage();

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Text(
          'Viewport too small for the Mass Mate click wheel. '
          'Use a larger phone viewport or rotate to a wider layout.',
          textAlign: TextAlign.center,
          style: Theme.of(context).textTheme.titleMedium,
        ),
      ),
    );
  }
}

class _ConnectionHeader extends StatelessWidget {
  const _ConnectionHeader(
      {required this.playerName, required this.connectionLabel});

  final String playerName;
  final String connectionLabel;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        const Icon(Icons.speaker_group),
        const SizedBox(width: 10),
        Expanded(
          child: Text(
            playerName,
            style: Theme.of(context).textTheme.titleLarge,
          ),
        ),
        _StatusPill(icon: Icons.wifi_tethering, text: connectionLabel),
      ],
    );
  }
}

class _ConnectionStatusBlock extends StatelessWidget {
  const _ConnectionStatusBlock({
    required this.playerState,
    required this.playbackError,
  });

  final PlayerState playerState;
  final String? playbackError;

  @override
  Widget build(BuildContext context) {
    final error = playbackError;

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        _ConnectionHeader(
          playerName: playerState.playerName,
          connectionLabel: playerState.connectionLabel,
        ),
        if (error != null) ...[
          const SizedBox(height: 8),
          _PlaybackErrorBanner(message: error),
        ],
      ],
    );
  }
}

class _PlaybackErrorBanner extends StatelessWidget {
  const _PlaybackErrorBanner({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;

    return Semantics(
      container: true,
      liveRegion: true,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: colorScheme.errorContainer,
          borderRadius: BorderRadius.circular(16),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          child: Row(
            children: [
              Icon(Icons.error_outline, color: colorScheme.onErrorContainer),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  message,
                  style: TextStyle(color: colorScheme.onErrorContainer),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _StatusPill extends StatelessWidget {
  const _StatusPill({required this.icon, required this.text});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.secondaryContainer,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 16),
            const SizedBox(width: 6),
            Text(text),
          ],
        ),
      ),
    );
  }
}
