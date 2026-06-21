import 'local_demo_player_adapter.dart';
import 'native_local_player_adapter.dart';
import 'native_local_player_bridge.dart';
import 'player_adapter.dart';

/// Player backend choices available behind the Mass Mate adapter seam.
enum PlayerBackendSelection {
  /// In-memory audiobook demo backend.
  demo,

  /// Android native local-player service backend.
  nativeLocalPlayer,
}

/// Creates a playback adapter for [backend].
PlayerAdapter createPlayerAdapter({
  required PlayerBackendSelection backend,
  NativeLocalPlayerBridge? nativeBridge,
}) {
  return switch (backend) {
    PlayerBackendSelection.demo => LocalDemoPlayerAdapter(),
    PlayerBackendSelection.nativeLocalPlayer => NativeLocalPlayerAdapter(
        bridge: nativeBridge,
      ),
  };
}

/// Selects the default backend from `--dart-define=MASS_MATE_PLAYER_BACKEND=...`.
///
/// Unknown values fail loudly instead of silently falling back to the demo backend.
PlayerBackendSelection playerBackendFromEnvironment() {
  const backendName = String.fromEnvironment(
    'MASS_MATE_PLAYER_BACKEND',
    defaultValue: 'demo',
  );

  return switch (backendName) {
    'demo' => PlayerBackendSelection.demo,
    'native-local-player' => PlayerBackendSelection.nativeLocalPlayer,
    _ => throw StateError(
        'Unknown MASS_MATE_PLAYER_BACKEND value `$backendName`.',
      ),
  };
}
