import 'package:flutter/material.dart';

import 'playback/player_state.dart';

/// Connection header plus optional visible playback error banner.
class PlayerConnectionStatusBlock extends StatelessWidget {
  /// Creates a connection status block for [playerState] and optional [playbackError].
  const PlayerConnectionStatusBlock({
    super.key,
    required this.playerState,
    required this.playbackError,
  });

  /// Player identity and connection label shown in the header.
  final PlayerState playerState;

  /// User-visible playback/backend error message, when one is active.
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

class _ConnectionHeader extends StatelessWidget {
  const _ConnectionHeader({
    required this.playerName,
    required this.connectionLabel,
  });

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
        PlayerStatusPill(icon: Icons.wifi_tethering, text: connectionLabel),
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

/// Rounded icon/text pill used for player status chips.
class PlayerStatusPill extends StatelessWidget {
  /// Creates a compact status pill with [icon] and [text].
  const PlayerStatusPill({super.key, required this.icon, required this.text});

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
