import 'dart:async';

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
  WheelMode _mode = WheelMode.seek;
  final WheelIntentResolver _wheelIntentResolver = WheelIntentResolver();

  PlayerAdapter get _playerAdapter => widget.playerAdapter;

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
    await _playerAdapter.applyIntent(intent);
    if (!mounted) return;

    setState(() {});
  }

  void _commitSeekPreview() {
    final resolution = _wheelIntentResolver.resolveSeekPreviewCommit();
    final intent = resolution.intent;
    if (intent == null) return;

    unawaited(_applySeekCommitIntent(intent));
  }

  Future<void> _applySeekCommitIntent(PlaybackIntent intent) async {
    await _playerAdapter.applyIntent(intent);
    if (!mounted) return;

    setState(_wheelIntentResolver.completeSeekPreviewCommit);
    _pulseSeekCommitHaptics();
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
                return Column(
                  children: [
                    _ConnectionHeader(
                      playerName: playerState.playerName,
                      connectionLabel: playerState.connectionLabel,
                    ),
                    const SizedBox(height: 20),
                    Expanded(
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Expanded(child: _buildNowPlayingCard(playerState)),
                          const SizedBox(width: 24),
                          SizedBox(
                            width: 320,
                            child: _buildControls(
                              playerState: playerState,
                              mainAxisAlignment: MainAxisAlignment.center,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                );
              }

              return Column(
                children: [
                  _ConnectionHeader(
                    playerName: playerState.playerName,
                    connectionLabel: playerState.connectionLabel,
                  ),
                  const SizedBox(height: 12),
                  Expanded(child: _buildNowPlayingCard(playerState)),
                  const SizedBox(height: 14),
                  _buildControls(
                    playerState: playerState,
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

  Widget _buildNowPlayingCard(PlayerState playerState) {
    return _NowPlayingCard(
      playerState: playerState,
      seekPreviewPosition: _wheelIntentResolver.seekPreviewPosition,
      mode: _mode,
    );
  }

  Widget _buildControls({
    required PlayerState playerState,
    MainAxisAlignment mainAxisAlignment = MainAxisAlignment.start,
    bool showModeSelector = true,
  }) {
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
          semanticLabel: '${_mode.label} click wheel',
          semanticHint: _mode.description,
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
}

class _NowPlayingCard extends StatelessWidget {
  const _NowPlayingCard({
    required this.playerState,
    required this.seekPreviewPosition,
    required this.mode,
  });

  final PlayerState playerState;
  final Duration? seekPreviewPosition;
  final WheelMode mode;

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
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Container(
                width: double.infinity,
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(28),
                  gradient: const LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [Color(0xFF263B80), Color(0xFF111827)],
                  ),
                ),
                child: const Icon(Icons.album, size: 96),
              ),
            ),
            const SizedBox(height: 22),
            Text(
              mediaItem.title,
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 6),
            Text(
              mediaItem.subtitle,
              style: Theme.of(context).textTheme.bodyMedium,
            ),
            const SizedBox(height: 18),
            LinearProgressIndicator(value: progress),
            const SizedBox(height: 10),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(formatPlaybackDuration(displayPosition)),
                Text(
                    '-${formatPlaybackDuration(trackLength - displayPosition)}'),
              ],
            ),
            const SizedBox(height: 16),
            _StatusPill(
              icon: isPreviewingSeek ? Icons.travel_explore : mode.icon,
              text: isPreviewingSeek ? 'Seek preview' : '${mode.label} mode',
            ),
            const SizedBox(height: 10),
            if (isPreviewingSeek) ...[
              Text(
                'Preview target ${formatPlaybackDuration(displayPosition)} • committed ${formatPlaybackDuration(position)}',
              ),
              const SizedBox(height: 10),
            ],
            Text(
              'Volume ${(playback.volume * 100).round()}% • Queue item ${playback.queueIndex} of ${playback.queueMaxIndex}',
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
