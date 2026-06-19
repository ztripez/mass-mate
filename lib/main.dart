import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

const int _wheelStepsPerTurn = 36;
const int _queueMinIndex = 1;
const int _queueLength = 24;
const MethodChannel _hapticsChannel = MethodChannel('mass_mate/haptics');

enum RangeBoundary { min, max }

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
  double _seekRemainderSeconds = 0;
  double _queueRemainderItems = 0;
  ({WheelMode mode, RangeBoundary boundary})? _lastBoundaryHit;

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

  RangeBoundary? _boundaryForRange(num value, num min, num max) {
    if (value <= min) return RangeBoundary.min;
    if (value >= max) return RangeBoundary.max;
    return null;
  }

  void _pulseBoundaryHaptics(WheelMode mode, RangeBoundary? boundary) {
    if (boundary == null) {
      _lastBoundaryHit = null;
      return;
    }

    final boundaryHit = (mode: mode, boundary: boundary);
    if (_lastBoundaryHit == boundaryHit) return;
    _lastBoundaryHit = boundaryHit;
    _WheelHaptics.boundaryBuzz();
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
          onCenterPressed: () => setState(() => _isPlaying = !_isPlaying),
          onModePressed: _cycleMode,
          onSkipBack: () => _handleWheelDelta(-0.16),
          onSkipForward: () => _handleWheelDelta(0.16),
        ),
      ],
    );
  }
}

class _WheelHaptics {
  static Future<void> boundaryBuzz() {
    return _hapticsChannel.invokeMethod<void>('boundaryBuzz');
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
  double _indicatorTurns = 0;
  double _hapticAccumulator = 0;

  void _start(DragStartDetails details) {
    final box = context.findRenderObject() as RenderBox;
    _center = box.size.center(Offset.zero);
    _lastAngle = _angleFor(details.localPosition);
    _hapticAccumulator = 0;
  }

  void _update(DragUpdateDetails details) {
    final previous = _lastAngle;
    if (previous == null) return;

    final current = _angleFor(details.localPosition);
    final delta = _normalizeRadians(current - previous);
    _lastAngle = current;

    final deltaTurns = delta / (math.pi * 2);
    setState(() => _indicatorTurns = (_indicatorTurns + deltaTurns) % 1);
    _pulseHaptics(deltaTurns);
    widget.onDelta(deltaTurns);
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

  void _pulseHaptics(double deltaTurns) {
    _hapticAccumulator += deltaTurns * _wheelStepsPerTurn;

    while (_hapticAccumulator.abs() >= 1) {
      HapticFeedback.mediumImpact();
      _hapticAccumulator -= _hapticAccumulator.sign;
    }
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;

    return Semantics(
      label: '${widget.mode.label} click wheel',
      hint: widget.mode.description,
      child: SizedBox.square(
        dimension: 300,
        child: DecoratedBox(
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            gradient: RadialGradient(
              stops: const [0.42, 0.43, 1],
              colors: [
                colorScheme.surfaceContainerLow,
                const Color(0xFFE8E8EA),
                const Color(0xFFAEB0B7),
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
              Positioned.fill(
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onPanStart: _start,
                  onPanUpdate: _update,
                  onPanEnd: _end,
                ),
              ),
              Positioned.fill(
                child: IgnorePointer(
                  child: CustomPaint(
                    painter: _WheelTickPainter(
                      offsetTurns: _indicatorTurns,
                      tickColor: const Color(0xFF6A6C73),
                      activeColor: colorScheme.primary,
                    ),
                  ),
                ),
              ),
              Positioned(
                top: 28,
                child: _WheelTextButton(
                  label: 'MENU',
                  onTap: widget.onModePressed,
                ),
              ),
              Positioned(
                left: 32,
                child: _WheelIconButton(
                  icon: Icons.skip_previous,
                  label: 'Back',
                  onTap: widget.onSkipBack,
                ),
              ),
              Positioned(
                right: 32,
                child: _WheelIconButton(
                  icon: Icons.skip_next,
                  label: 'Next',
                  onTap: widget.onSkipForward,
                ),
              ),
              Positioned(
                bottom: 28,
                child: _WheelIconButton(
                  icon: widget.isPlaying ? Icons.pause : Icons.play_arrow,
                  label: widget.isPlaying ? 'Pause' : 'Play',
                  onTap: widget.onCenterPressed,
                ),
              ),
              FilledButton(
                style: FilledButton.styleFrom(
                  fixedSize: const Size.square(112),
                  shape: const CircleBorder(),
                  backgroundColor: colorScheme.surface,
                  foregroundColor: colorScheme.onSurface,
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
    );
  }
}

class _WheelTickPainter extends CustomPainter {
  const _WheelTickPainter({
    required this.offsetTurns,
    required this.tickColor,
    required this.activeColor,
  });

  final double offsetTurns;
  final Color tickColor;
  final Color activeColor;

  @override
  void paint(Canvas canvas, Size size) {
    final center = size.center(Offset.zero);
    final activeTick =
        (offsetTurns * _wheelStepsPerTurn).round() % _wheelStepsPerTurn;
    final outerRadius = size.shortestSide / 2 - 18;

    for (var index = 0; index < _wheelStepsPerTurn; index += 1) {
      final distance = _distanceFromActiveTick(index, activeTick);
      final isActive = distance <= 2;
      final angle = -math.pi / 2 + index * math.pi * 2 / _wheelStepsPerTurn;
      final direction = Offset(math.cos(angle), math.sin(angle));
      final tickLength = isActive ? 16.0 - distance * 2 : 8.0;
      final paint = Paint()
        ..color = isActive
            ? activeColor.withValues(alpha: 0.85 - distance * 0.18)
            : tickColor.withValues(alpha: 0.42)
        ..strokeWidth = isActive ? 2.6 : 1.4
        ..strokeCap = StrokeCap.round;

      canvas.drawLine(
        center + direction * (outerRadius - tickLength),
        center + direction * outerRadius,
        paint,
      );
    }
  }

  int _distanceFromActiveTick(int index, int activeTick) {
    final directDistance = (index - activeTick).abs();
    return math.min(directDistance, _wheelStepsPerTurn - directDistance);
  }

  @override
  bool shouldRepaint(_WheelTickPainter oldDelegate) {
    return oldDelegate.offsetTurns != offsetTurns ||
        oldDelegate.tickColor != tickColor ||
        oldDelegate.activeColor != activeColor;
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

class _WheelIconButton extends StatelessWidget {
  const _WheelIconButton({
    required this.icon,
    required this.label,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return IconButton(
      tooltip: label,
      onPressed: onTap,
      icon: Icon(icon, color: const Color(0xFF33353B), size: 30),
    );
  }
}

class _WheelTextButton extends StatelessWidget {
  const _WheelTextButton({required this.label, required this.onTap});

  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return TextButton(
      onPressed: onTap,
      style: TextButton.styleFrom(
        foregroundColor: const Color(0xFF33353B),
        textStyle: Theme.of(context).textTheme.labelLarge?.copyWith(
              fontWeight: FontWeight.w800,
              letterSpacing: 1.2,
            ),
      ),
      child: Text(label),
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
