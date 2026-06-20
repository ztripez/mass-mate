import 'dart:async';

import 'package:flutter/material.dart';

import 'click_wheel.dart';
import 'haptics.dart';
import 'playback/playback_intent.dart';
import 'playback/wheel_intent_resolver.dart';
import 'wheel/wheel_gesture.dart';
import 'wheel_mode.dart';

/// Player screen that displays the local playback prototype and click-wheel controls.
class PlayerScreen extends StatefulWidget {
  /// Creates the local player prototype screen.
  const PlayerScreen({super.key});

  @override
  State<PlayerScreen> createState() => _PlayerScreenState();
}

class _PlayerScreenState extends State<PlayerScreen> {
  WheelMode _mode = WheelMode.seek;
  PlaybackSnapshot _playback = PlaybackSnapshot(
    position: const Duration(minutes: 18, seconds: 42),
    trackLength: const Duration(minutes: 54, seconds: 18),
    volume: 0.62,
    queueIndex: 3,
    queueMinIndex: 1,
    queueMaxIndex: 24,
  );
  bool _isPlaying = true;
  final WheelIntentResolver _wheelIntentResolver = WheelIntentResolver();

  void _handleWheelGesture(WheelGesture gesture) {
    final resolution = _wheelIntentResolver.resolve(
      gesture: gesture,
      mode: _mode,
      playback: _playback,
    );

    _emitBoundaryFeedback(resolution.boundaryHit);

    final intent = resolution.intent;
    if (intent == null) return;

    setState(() => _applyPlaybackIntent(intent));
  }

  void _applyPlaybackIntent(PlaybackIntent intent) {
    switch (intent) {
      case SeekToPlaybackIntent(:final position):
        _playback = _playback.copyWith(position: position);
      case SetVolumePlaybackIntent(:final volume):
        _playback = _playback.copyWith(volume: volume);
      case SelectQueueItemPlaybackIntent(:final queueIndex):
        _playback = _playback.copyWith(queueIndex: queueIndex);
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

    setState(() => _mode = mode);
  }

  void _cycleMode() {
    final nextIndex = (_mode.index + 1) % WheelMode.values.length;
    _setMode(WheelMode.values[nextIndex]);
  }

  @override
  Widget build(BuildContext context) {
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
                    const _ConnectionHeader(),
                    const SizedBox(height: 20),
                    Expanded(
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Expanded(child: _buildNowPlayingCard()),
                          const SizedBox(width: 24),
                          SizedBox(
                            width: 320,
                            child: _buildControls(
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
                  const _ConnectionHeader(),
                  const SizedBox(height: 12),
                  Expanded(child: _buildNowPlayingCard()),
                  const SizedBox(height: 14),
                  _buildControls(showModeSelector: false),
                ],
              );
            },
          ),
        ),
      ),
    );
  }

  Widget _buildNowPlayingCard() {
    return _NowPlayingCard(
      playback: _playback,
      mode: _mode,
    );
  }

  Widget _buildControls({
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
          isPlaying: _isPlaying,
          onGesture: _handleWheelGesture,
          onCenterPressed: _cycleMode,
          onModePressed: _cycleMode,
          onSkipBack: () => _handleWheelGesture(
            WheelGesture(turnDelta: -0.16),
          ),
          onSkipForward: () => _handleWheelGesture(
            WheelGesture(turnDelta: 0.16),
          ),
          onPlayPausePressed: () => setState(() => _isPlaying = !_isPlaying),
        ),
      ],
    );
  }
}

class _NowPlayingCard extends StatelessWidget {
  const _NowPlayingCard({
    required this.playback,
    required this.mode,
  });

  final PlaybackSnapshot playback;
  final WheelMode mode;

  @override
  Widget build(BuildContext context) {
    final position = playback.position;
    final trackLength = playback.trackLength;
    final progress = position.inMilliseconds / trackLength.inMilliseconds;

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
              'Chapter 12: Night Drive',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 6),
            Text(
              'The Long Way Home • Audiobook',
              style: Theme.of(context).textTheme.bodyMedium,
            ),
            const SizedBox(height: 18),
            LinearProgressIndicator(value: progress),
            const SizedBox(height: 10),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(_formatDuration(position)),
                Text('-${_formatDuration(trackLength - position)}'),
              ],
            ),
            const SizedBox(height: 16),
            _StatusPill(icon: mode.icon, text: '${mode.label} mode'),
            const SizedBox(height: 10),
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
  const _ConnectionHeader();

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        const Icon(Icons.speaker_group),
        const SizedBox(width: 10),
        Expanded(
          child: Text(
            'Mass Mate',
            style: Theme.of(context).textTheme.titleLarge,
          ),
        ),
        const _StatusPill(icon: Icons.wifi_tethering, text: 'Local demo'),
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

String _formatDuration(Duration duration) {
  final minutes = duration.inMinutes.remainder(60).toString().padLeft(2, '0');
  final seconds = duration.inSeconds.remainder(60).toString().padLeft(2, '0');
  return '$minutes:$seconds';
}
