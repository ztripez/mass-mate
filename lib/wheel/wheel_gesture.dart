/// Rotary input sample emitted by the click wheel.
///
/// Positive [turnDelta] values represent clockwise movement and negative values
/// represent counterclockwise movement. The value is measured as a fraction of a
/// full turn since the previous gesture update.
class WheelGesture {
  /// Creates a rotary click-wheel gesture sample.
  ///
  /// Throws an [ArgumentError] when [turnDelta] is not finite.
  WheelGesture({required this.turnDelta}) {
    if (!turnDelta.isFinite) {
      throw ArgumentError.value(turnDelta, 'turnDelta', 'must be finite');
    }
  }

  /// Signed fraction of a full turn emitted by this gesture update.
  final double turnDelta;
}
