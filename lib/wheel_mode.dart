import 'package:flutter/material.dart';

/// Interaction modes that determine how rotary click-wheel movement is applied.
enum WheelMode {
  /// Scrubs through the current track position.
  seek(Icons.schedule, 'Seek', 'Scrub through the current track'),

  /// Fine-tunes playback volume.
  volume(Icons.volume_up, 'Volume', 'Fine tune output level'),

  /// Steps through queued media items.
  queue(Icons.queue_music, 'Queue', 'Step through upcoming items');

  /// Creates a mode with display metadata used by the player shell.
  const WheelMode(this.icon, this.label, this.description);

  /// Icon shown in mode selectors and status indicators.
  final IconData icon;

  /// Short human-readable mode name.
  final String label;

  /// Accessibility hint explaining what rotary movement changes in this mode.
  final String description;
}
