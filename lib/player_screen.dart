import 'dart:async';

import 'package:flutter/material.dart';

import 'click_wheel.dart';
import 'haptics.dart';
import 'wheel_mode.dart';

const int _queueMinIndex = 1;
const int _queueLength = 24;

enum _RangeBoundary { min, max }

/// Player screen that displays the local playback prototype and click-wheel controls.
class PlayerScreen extends StatefulWidget {
  /// Creates the local player prototype screen.
  const PlayerScreen({super.key});

  @override
  State<PlayerScreen> createState() => _PlayerScreenState();
}

class _PlayerScreenState extends State<PlayerScreen> {
  static const Duration _trackLength = Duration(minutes: 54, seconds: 18);

  WheelMode _mode = WheelMode.seek;
  Duration _position = const Duration(minutes: 18, seconds: 42);
  double _volume = 0.62;
  int _queueIndex = 3;
  bool _isPlaying = true;
  double _seekRemainderSeconds = 0;
  double _queueRemainderItems = 0;
  ({WheelMode mode, _RangeBoundary boundary})? _lastBoundaryHit;

  void _handleWheelDelta(double turns) {
    switch (_mode) {
      case WheelMode.seek:
        _seekRemainderSeconds += turns * 75;
        final deltaSeconds = _consumeWholeUnits(_seekRemainderSeconds);
        _seekRemainderSeconds -= deltaSeconds;
        if (deltaSeconds == 0) return;

        final nextSeconds = _position.inSeconds + deltaSeconds;
        final clampedSeconds =
            nextSeconds.clamp(0, _trackLength.inSeconds).toInt();
        _pulseBoundaryHaptics(
          WheelMode.seek,
          _boundaryForRange(clampedSeconds, 0, _trackLength.inSeconds),
        );
        setState(() {
          _position = Duration(seconds: clampedSeconds);
        });
        break;
      case WheelMode.volume:
        final nextVolume = (_volume + turns * 0.18).clamp(0.0, 1.0);
        _pulseBoundaryHaptics(
          WheelMode.volume,
          _boundaryForRange(nextVolume, 0, 1),
        );
        setState(() {
          _volume = nextVolume;
        });
        break;
      case WheelMode.queue:
        _queueRemainderItems += turns * 12;
        final deltaItems = _consumeWholeUnits(_queueRemainderItems);
        _queueRemainderItems -= deltaItems;
        if (deltaItems == 0) return;

        final nextQueueIndex = (_queueIndex + deltaItems)
            .clamp(_queueMinIndex, _queueLength)
            .toInt();
        _pulseBoundaryHaptics(
          WheelMode.queue,
          _boundaryForRange(nextQueueIndex, _queueMinIndex, _queueLength),
        );
        setState(() {
          _queueIndex = nextQueueIndex;
        });
        break;
    }
  }

  int _consumeWholeUnits(double value) => value.truncate();

  _RangeBoundary? _boundaryForRange(num value, num min, num max) {
    if (value <= min) return _RangeBoundary.min;
    if (value >= max) return _RangeBoundary.max;
    return null;
  }

  void _pulseBoundaryHaptics(WheelMode mode, _RangeBoundary? boundary) {
    if (boundary == null) {
      _lastBoundaryHit = null;
      return;
    }

    final boundaryHit = (mode: mode, boundary: boundary);
    if (_lastBoundaryHit == boundaryHit) return;
    _lastBoundaryHit = boundaryHit;
    unawaited(
      WheelHaptics.boundaryBuzz()
          .catchError((Object error, StackTrace stackTrace) {
        if (mounted && _lastBoundaryHit == boundaryHit) {
          _lastBoundaryHit = null;
        }
        WheelHaptics.reportFailure(
          context: 'while emitting a click-wheel boundary haptic',
          error: error,
          stackTrace: stackTrace,
        );
      }),
    );
  }

  void _cycleMode() {
    setState(() {
      final nextIndex = (_mode.index + 1) % WheelMode.values.length;
      _mode = WheelMode.values[nextIndex];
    });
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
      position: _position,
      trackLength: _trackLength,
      volume: _volume,
      queueIndex: _queueIndex,
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
            onSelected: (mode) => setState(() => _mode = mode),
          ),
          const SizedBox(height: 20),
        ],
        ClickWheel(
          mode: _mode,
          isPlaying: _isPlaying,
          onDelta: _handleWheelDelta,
          onCenterPressed: _cycleMode,
          onModePressed: _cycleMode,
          onSkipBack: () => _handleWheelDelta(-0.16),
          onSkipForward: () => _handleWheelDelta(0.16),
          onPlayPausePressed: () => setState(() => _isPlaying = !_isPlaying),
        ),
      ],
    );
  }
}

class _NowPlayingCard extends StatelessWidget {
  const _NowPlayingCard({
    required this.position,
    required this.trackLength,
    required this.volume,
    required this.queueIndex,
    required this.mode,
  });

  final Duration position;
  final Duration trackLength;
  final double volume;
  final int queueIndex;
  final WheelMode mode;

  @override
  Widget build(BuildContext context) {
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
            LinearProgressIndicator(value: progress.clamp(0, 1)),
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
              'Volume ${(volume * 100).round()}% • Queue item $queueIndex of $_queueLength',
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
