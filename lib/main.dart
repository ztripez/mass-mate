import 'dart:async';

import 'package:flutter/material.dart';

import 'playback/player_adapter.dart';
import 'playback/player_adapter_factory.dart';
import 'player_screen.dart';

/// Starts the Mass Mate Flutter application.
void main() => runApp(const MassMateApp());

/// Root widget that configures the Mass Mate Material app and opens the player screen.
class MassMateApp extends StatefulWidget {
  /// Creates the root Mass Mate application widget.
  const MassMateApp({
    super.key,
    PlayerAdapter? playerAdapter,
    PlayerBackendSelection? backend,
  })  : _providedPlayerAdapter = playerAdapter,
        _backend = backend;

  final PlayerAdapter? _providedPlayerAdapter;
  final PlayerBackendSelection? _backend;

  @override
  State<MassMateApp> createState() => _MassMateAppState();
}

class _MassMateAppState extends State<MassMateApp> {
  late final PlayerAdapter _playerAdapter;
  late final bool _ownsPlayerAdapter;

  @override
  void initState() {
    super.initState();
    final providedPlayerAdapter = widget._providedPlayerAdapter;
    if (providedPlayerAdapter != null) {
      _playerAdapter = providedPlayerAdapter;
      _ownsPlayerAdapter = false;
    } else {
      _playerAdapter = createPlayerAdapter(
        backend: widget._backend ?? playerBackendFromEnvironment(),
      );
      _ownsPlayerAdapter = true;
    }
  }

  @override
  void dispose() {
    if (_ownsPlayerAdapter) unawaited(_playerAdapter.dispose());
    super.dispose();
  }

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
      home: PlayerScreen(playerAdapter: _playerAdapter),
    );
  }
}
