import 'package:flutter/material.dart';

import 'player_screen.dart';

/// Starts the Mass Mate Flutter application.
void main() => runApp(const MassMateApp());

/// Root widget that configures the Mass Mate Material app and opens the player screen.
class MassMateApp extends StatelessWidget {
  /// Creates the root Mass Mate application widget.
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
