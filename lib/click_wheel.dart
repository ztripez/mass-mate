import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'haptics.dart';
import 'wheel/wheel_constants.dart';
import 'wheel/wheel_gesture.dart';

/// Circular touch control that reports rotary drag movement and exposes iPod-style controls.
///
/// The click wheel converts pan gestures around its center into signed fractions of a
/// full turn. Positive values represent clockwise movement and negative values represent
/// counterclockwise movement. It emits medium haptic feedback for stepped wheel ticks and
/// delegates visible button regions to callbacks supplied by its parent.
class ClickWheel extends StatefulWidget {
  /// Creates a click wheel for rotary touch interactions.
  const ClickWheel({
    required this.semanticLabel,
    required this.semanticHint,
    required this.isPlaying,
    required this.onGesture,
    required this.onGestureEnded,
    required this.onGestureCanceled,
    required this.onCenterPressed,
    required this.onModePressed,
    required this.onSkipBack,
    required this.onSkipForward,
    required this.onPlayPausePressed,
    this.dimension = 300,
    this.centerSemanticLabel = 'Center button',
    this.centerSemanticHint,
    this.modeSemanticLabel = 'MODE',
    this.modeSemanticHint,
    this.backSemanticLabel = 'Back',
    this.backSemanticHint,
    this.forwardSemanticLabel = 'Next',
    this.forwardSemanticHint,
    this.playPauseSemanticHint,
    super.key,
  });

  /// Smallest supported wheel diameter in logical pixels.
  static const double minSupportedDimension = 260;

  /// Largest supported wheel diameter in logical pixels.
  static const double maxSupportedDimension = 360;

  /// Accessibility label for the current wheel mode.
  final String semanticLabel;

  /// Accessibility hint for the current wheel mode.
  final String semanticHint;

  /// Whether the bottom transport control displays pause instead of play.
  final bool isPlaying;

  /// Called when rotary dragging produces a signed wheel gesture.
  final ValueChanged<WheelGesture> onGesture;

  /// Called when an active rotary drag ends with a normal pointer release.
  final VoidCallback onGestureEnded;

  /// Called when an active rotary drag is canceled before normal release.
  final VoidCallback onGestureCanceled;

  /// Called when the center select button is pressed.
  final VoidCallback onCenterPressed;

  /// Called when the mode button requests the next wheel interaction mode.
  final VoidCallback onModePressed;

  /// Called when the backward skip button is pressed.
  final VoidCallback onSkipBack;

  /// Called when the forward skip button is pressed.
  final VoidCallback onSkipForward;

  /// Called when the bottom play/pause button is pressed.
  final VoidCallback onPlayPausePressed;

  /// Diameter of the circular wheel in logical pixels.
  final double dimension;

  /// Accessibility label for the center button.
  final String centerSemanticLabel;

  /// Accessibility hint for the center button.
  final String? centerSemanticHint;

  /// Accessibility label for the top MODE button.
  final String modeSemanticLabel;

  /// Accessibility hint for the top MODE button.
  final String? modeSemanticHint;

  /// Accessibility label for the left/back button.
  final String backSemanticLabel;

  /// Accessibility hint for the left/back button.
  final String? backSemanticHint;

  /// Accessibility label for the right/forward button.
  final String forwardSemanticLabel;

  /// Accessibility hint for the right/forward button.
  final String? forwardSemanticHint;

  /// Accessibility hint for the bottom play/pause button.
  final String? playPauseSemanticHint;

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
    if (previous == null) {
      throw StateError(
        'Click wheel drag angle must be initialized before pan updates.',
      );
    }

    final current = _angleFor(details.localPosition);
    final delta = _normalizeRadians(current - previous);
    _lastAngle = current;

    final deltaTurns = delta / (math.pi * 2);
    setState(() => _indicatorTurns = (_indicatorTurns + deltaTurns) % 1);
    _pulseHaptics(deltaTurns);
    widget.onGesture(WheelGesture(turnDelta: deltaTurns));
  }

  void _end(DragEndDetails details) {
    _center = null;
    _lastAngle = null;
    widget.onGestureEnded();
  }

  void _cancel() {
    _center = null;
    _lastAngle = null;
    widget.onGestureCanceled();
  }

  double _angleFor(Offset localPosition) {
    final center = _center;
    if (center == null) {
      throw StateError(
        'Click wheel drag center must be initialized before resolving angles.',
      );
    }

    final vector = localPosition - center;
    return math.atan2(vector.dy, vector.dx);
  }

  double _normalizeRadians(double value) {
    if (value > math.pi) return value - math.pi * 2;
    if (value < -math.pi) return value + math.pi * 2;
    return value;
  }

  void _pulseHaptics(double deltaTurns) {
    _hapticAccumulator += deltaTurns * wheelDetentsPerTurn;

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
    final dimension = widget.dimension;
    _validateDimension(dimension);

    final geometry = _ClickWheelGeometry.forDimension(dimension);
    geometry.validateFits(dimension);

    return Semantics(
      label: widget.semanticLabel,
      hint: widget.semanticHint,
      child: SizedBox.square(
        dimension: dimension,
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
                  onPanCancel: _cancel,
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
                top: geometry.verticalInset,
                child: _WheelTextButton(
                  label: 'MODE',
                  semanticLabel: widget.modeSemanticLabel,
                  semanticHint: widget.modeSemanticHint,
                  minimumSize: Size(geometry.textButtonWidth, geometry.hitSize),
                  onTap: widget.onModePressed,
                ),
              ),
              Positioned(
                left: geometry.horizontalInset,
                child: _WheelIconButton(
                  icon: Icons.skip_previous,
                  label: widget.backSemanticLabel,
                  semanticHint: widget.backSemanticHint,
                  hitSize: geometry.hitSize,
                  iconSize: geometry.iconSize,
                  onTap: widget.onSkipBack,
                ),
              ),
              Positioned(
                right: geometry.horizontalInset,
                child: _WheelIconButton(
                  icon: Icons.skip_next,
                  label: widget.forwardSemanticLabel,
                  semanticHint: widget.forwardSemanticHint,
                  hitSize: geometry.hitSize,
                  iconSize: geometry.iconSize,
                  onTap: widget.onSkipForward,
                ),
              ),
              Positioned(
                bottom: geometry.verticalInset,
                child: _WheelIconButton(
                  icon: widget.isPlaying ? Icons.pause : Icons.play_arrow,
                  label: widget.isPlaying ? 'Pause' : 'Play',
                  semanticHint: widget.playPauseSemanticHint,
                  hitSize: geometry.hitSize,
                  iconSize: geometry.iconSize,
                  onTap: widget.onPlayPausePressed,
                ),
              ),
              Semantics(
                label: widget.centerSemanticLabel,
                hint: widget.centerSemanticHint,
                button: true,
                child: FilledButton(
                  style: FilledButton.styleFrom(
                    fixedSize: Size.square(geometry.centerButtonSize),
                    shape: const CircleBorder(),
                    backgroundColor: colorScheme.surface,
                    foregroundColor: colorScheme.onSurface,
                  ),
                  onPressed: widget.onCenterPressed,
                  child: const SizedBox.shrink(),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  void _validateDimension(double dimension) {
    if (!dimension.isFinite ||
        dimension < ClickWheel.minSupportedDimension ||
        dimension > ClickWheel.maxSupportedDimension) {
      throw FlutterError(
        'ClickWheel dimension must be finite and between '
        '${ClickWheel.minSupportedDimension.toStringAsFixed(0)} and '
        '${ClickWheel.maxSupportedDimension.toStringAsFixed(0)} logical pixels; '
        'received $dimension.',
      );
    }
  }
}

class _ClickWheelGeometry {
  const _ClickWheelGeometry({
    required this.hitSize,
    required this.iconSize,
    required this.textButtonWidth,
    required this.centerButtonSize,
    required this.verticalInset,
    required this.horizontalInset,
  });

  factory _ClickWheelGeometry.forDimension(double dimension) {
    final normalized = (dimension - ClickWheel.minSupportedDimension) /
        (ClickWheel.maxSupportedDimension - ClickWheel.minSupportedDimension);

    return _ClickWheelGeometry(
      hitSize: _lerp(64, 76, normalized),
      iconSize: _lerp(28, 36, normalized),
      textButtonWidth: _lerp(82, 100, normalized),
      centerButtonSize: _lerp(96, 132, normalized),
      verticalInset: _lerp(14, 30, normalized),
      horizontalInset: _lerp(14, 34, normalized),
    );
  }

  final double hitSize;
  final double iconSize;
  final double textButtonWidth;
  final double centerButtonSize;
  final double verticalInset;
  final double horizontalInset;

  void validateFits(double dimension) {
    final centerEdge = (dimension - centerButtonSize) / 2;
    final verticalGap = centerEdge - verticalInset - hitSize;
    final horizontalGap = centerEdge - horizontalInset - hitSize;

    if (verticalGap < 0 || horizontalGap < 0) {
      throw FlutterError(
        'ClickWheel controls do not fit inside a '
        '${dimension.toStringAsFixed(0)} logical-pixel wheel without overlap.',
      );
    }
  }

  static double _lerp(double start, double end, double normalized) {
    return start + (end - start) * normalized;
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
        (offsetTurns * wheelDetentsPerTurn).round() % wheelDetentsPerTurn;
    final outerRadius = size.shortestSide / 2 - 18;

    for (var index = 0; index < wheelDetentsPerTurn; index += 1) {
      final distance = _distanceFromActiveTick(index, activeTick);
      final isActive = distance <= 2;
      final angle = -math.pi / 2 + index * math.pi * 2 / wheelDetentsPerTurn;
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
    return math.min(directDistance, wheelDetentsPerTurn - directDistance);
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
    required this.hitSize,
    required this.iconSize,
    required this.onTap,
    this.semanticHint,
  });

  final IconData icon;
  final String label;
  final String? semanticHint;
  final double hitSize;
  final double iconSize;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: label,
      hint: semanticHint,
      button: true,
      child: IconButton(
        tooltip: label,
        style: IconButton.styleFrom(
          fixedSize: Size.square(hitSize),
          minimumSize: Size.square(hitSize),
        ),
        onPressed: onTap,
        icon: Icon(icon, color: const Color(0xFF33353B), size: iconSize),
      ),
    );
  }
}

class _WheelTextButton extends StatelessWidget {
  const _WheelTextButton({
    required this.label,
    required this.semanticLabel,
    required this.minimumSize,
    required this.onTap,
    this.semanticHint,
  });

  final String label;
  final String semanticLabel;
  final String? semanticHint;
  final Size minimumSize;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: semanticLabel,
      hint: semanticHint,
      button: true,
      child: TextButton(
        onPressed: onTap,
        style: TextButton.styleFrom(
          foregroundColor: const Color(0xFF33353B),
          minimumSize: minimumSize,
          textStyle: Theme.of(context).textTheme.labelLarge?.copyWith(
                fontWeight: FontWeight.w800,
                letterSpacing: 1.2,
              ),
        ),
        child: Text(label),
      ),
    );
  }
}
