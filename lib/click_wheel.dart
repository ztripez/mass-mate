import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'haptics.dart';
import 'wheel_mode.dart';

const int _wheelStepsPerTurn = 36;

/// Circular touch control that reports rotary drag movement and exposes iPod-style controls.
///
/// The click wheel converts pan gestures around its center into signed fractions of a
/// full turn. Positive values represent clockwise movement and negative values represent
/// counterclockwise movement. It emits medium haptic feedback for stepped wheel ticks and
/// delegates transport, mode, and center-button actions to callbacks supplied by its parent.
class ClickWheel extends StatefulWidget {
  /// Creates a click wheel for rotary playback interactions.
  const ClickWheel({
    required this.mode,
    required this.isPlaying,
    required this.onDelta,
    required this.onCenterPressed,
    required this.onModePressed,
    required this.onSkipBack,
    required this.onSkipForward,
    required this.onPlayPausePressed,
    super.key,
  });

  /// Current interaction mode used for the semantic label and hint text.
  final WheelMode mode;

  /// Whether the bottom transport control displays pause instead of play.
  final bool isPlaying;

  /// Called when rotary dragging produces signed movement measured in full turns.
  final ValueChanged<double> onDelta;

  /// Called when the center select button is pressed.
  final VoidCallback onCenterPressed;

  /// Called when the menu button requests the next wheel interaction mode.
  final VoidCallback onModePressed;

  /// Called when the backward skip button is pressed.
  final VoidCallback onSkipBack;

  /// Called when the forward skip button is pressed.
  final VoidCallback onSkipForward;

  /// Called when the bottom play/pause button is pressed.
  final VoidCallback onPlayPausePressed;

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
      _mediumImpact();
      _hapticAccumulator -= _hapticAccumulator.sign;
    }
  }

  void _mediumImpact() {
    unawaited(
      HapticFeedback.mediumImpact()
          .catchError((Object error, StackTrace stackTrace) {
        WheelHaptics.reportFailure(
          context: 'while emitting a click-wheel step haptic',
          error: error,
          stackTrace: stackTrace,
        );
      }),
    );
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
                  onTap: widget.onPlayPausePressed,
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
                child: const SizedBox.shrink(),
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
