import 'dart:math' as math;

import 'package:flutter/material.dart';

void main() => runApp(const MassMateApp());

class MassMateApp extends StatelessWidget {
  const MassMateApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Mass Mate',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF64D2FF),
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      home: const PlayerScreen(),
    );
  }
}

enum WheelMode {
  seek(Icons.schedule, 'Seek', 'Scrub through the current track'),
  volume(Icons.volume_up, 'Volume', 'Fine tune output level'),
  queue(Icons.queue_music, 'Queue', 'Step through upcoming items');

  const WheelMode(this.icon, this.label, this.description);

  final IconData icon;
  final String label;
  final String description;
}

class PlayerScreen extends StatefulWidget {
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

  void _handleWheelDelta(double turns) {
    setState(() {
      switch (_mode) {
        case WheelMode.seek:
          final seconds = _position.inSeconds + (turns * 75).round();
          _position = Duration(
            seconds: seconds.clamp(0, _trackLength.inSeconds),
          );
          break;
        case WheelMode.volume:
          _volume = (_volume + turns * 0.18).clamp(0, 1);
          break;
        case WheelMode.queue:
          _queueIndex = (_queueIndex + (turns * 6).round()).clamp(1, 24);
          break;
      }
    });
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
          child: Column(
            children: [
              const _ConnectionHeader(),
              const SizedBox(height: 20),
              Expanded(
                child: _NowPlayingCard(
                  position: _position,
                  trackLength: _trackLength,
                  volume: _volume,
                  queueIndex: _queueIndex,
                  mode: _mode,
                ),
              ),
              const SizedBox(height: 18),
              _ModeSelector(
                selectedMode: _mode,
                onSelected: (mode) => setState(() => _mode = mode),
              ),
              const SizedBox(height: 20),
              ClickWheel(
                mode: _mode,
                isPlaying: _isPlaying,
                onDelta: _handleWheelDelta,
                onCenterPressed: () => setState(() => _isPlaying = !_isPlaying),
                onModePressed: _cycleMode,
                onSkipBack: () => _handleWheelDelta(-0.16),
                onSkipForward: () => _handleWheelDelta(0.16),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class ClickWheel extends StatefulWidget {
  const ClickWheel({
    required this.mode,
    required this.isPlaying,
    required this.onDelta,
    required this.onCenterPressed,
    required this.onModePressed,
    required this.onSkipBack,
    required this.onSkipForward,
    super.key,
  });

  final WheelMode mode;
  final bool isPlaying;
  final ValueChanged<double> onDelta;
  final VoidCallback onCenterPressed;
  final VoidCallback onModePressed;
  final VoidCallback onSkipBack;
  final VoidCallback onSkipForward;

  @override
  State<ClickWheel> createState() => _ClickWheelState();
}

class _ClickWheelState extends State<ClickWheel> {
  Offset? _center;
  double? _lastAngle;

  void _start(DragStartDetails details) {
    final box = context.findRenderObject() as RenderBox;
    _center = box.size.center(Offset.zero);
    _lastAngle = _angleFor(details.localPosition);
  }

  void _update(DragUpdateDetails details) {
    final previous = _lastAngle;
    if (previous == null) return;

    final current = _angleFor(details.localPosition);
    final delta = _normalizeRadians(current - previous);
    _lastAngle = current;
    widget.onDelta(delta / (math.pi * 2));
  }

  void _end(DragEndDetails details) {
    _center = null;
    _lastAngle = null;
  }

  double _angleFor(Offset localPosition) {
    final center = _center ?? Offset.zero;
    final vector = localPosition - center;
    return math.atan2(vector.dy, vector.dx);
  }

  double _normalizeRadians(double value) {
    if (value > math.pi) return value - math.pi * 2;
    if (value < -math.pi) return value + math.pi * 2;
    return value;
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;

    return Semantics(
      label: '${widget.mode.label} click wheel',
      hint: widget.mode.description,
      child: GestureDetector(
        onPanStart: _start,
        onPanUpdate: _update,
        onPanEnd: _end,
        child: SizedBox.square(
          dimension: 286,
          child: DecoratedBox(
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              gradient: RadialGradient(
                colors: [
                  colorScheme.surfaceContainerHighest,
                  colorScheme.surfaceContainerLow,
                ],
              ),
              boxShadow: const [
                BoxShadow(
                  color: Colors.black54,
                  blurRadius: 30,
                  offset: Offset(0, 18),
                ),
              ],
            ),
            child: Stack(
              alignment: Alignment.center,
              children: [
                Positioned(
                  top: 30,
                  child: _WheelButton(
                    icon: widget.mode.icon,
                    label: widget.mode.label,
                    onTap: widget.onModePressed,
                  ),
                ),
                Positioned(
                  left: 28,
                  child: _WheelButton(
                    icon: Icons.skip_previous,
                    label: 'Back',
                    onTap: widget.onSkipBack,
                  ),
                ),
                Positioned(
                  right: 28,
                  child: _WheelButton(
                    icon: Icons.skip_next,
                    label: 'Next',
                    onTap: widget.onSkipForward,
                  ),
                ),
                Positioned(
                  bottom: 30,
                  child: Text(
                    'MENU',
                    style: Theme.of(context).textTheme.labelLarge,
                  ),
                ),
                FilledButton(
                  style: FilledButton.styleFrom(
                    fixedSize: const Size.square(112),
                    shape: const CircleBorder(),
                    backgroundColor: colorScheme.primary,
                    foregroundColor: colorScheme.onPrimary,
                  ),
                  onPressed: widget.onCenterPressed,
                  child: Icon(
                    widget.isPlaying ? Icons.pause : Icons.play_arrow,
                    size: 44,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
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
            Text('Volume ${(volume * 100).round()}% • Queue item $queueIndex of 24'),
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

class _WheelButton extends StatelessWidget {
  const _WheelButton({
    required this.icon,
    required this.label,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return IconButton.filledTonal(
      tooltip: label,
      onPressed: onTap,
      icon: Icon(icon),
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
