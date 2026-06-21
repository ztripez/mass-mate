import 'package:flutter/material.dart';

import 'playback/player_adapter.dart';
import 'playback/player_adapter_factory.dart';
import 'player_screen.dart';

/// Starts the Mass Mate Flutter application.
void main() => runApp(MassMateApp());

/// Root widget that configures the Mass Mate Material app and opens the player screen.
class MassMateApp extends StatelessWidget {
  /// Creates the root Mass Mate application widget.
  MassMateApp({
    super.key,
    PlayerAdapter? playerAdapter,
    PlayerBackendSelection? backend,
  }) : playerAdapter = playerAdapter ??
            createPlayerAdapter(
              backend: backend ?? playerBackendFromEnvironment(),
            );

  /// Playback adapter supplied to the player screen.
  final PlayerAdapter playerAdapter;

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
      home: PlayerScreen(playerAdapter: playerAdapter),
    );
  }
}
