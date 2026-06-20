import '../playback/playback_intent.dart';

/// Transport boundary for applying canonical playback intents to Music Assistant.
///
/// A concrete implementation must translate [PlaybackIntent] values into the connected
/// server's generated `/api-docs` command names and request bodies. This interface keeps the
/// rest of the app on Mass Mate's canonical playback intent model until those schemas are
/// verified.
abstract interface class MusicAssistantClient {
  /// Applies [intent] to [targetId] through Music Assistant.
  ///
  /// Implementations must complete only after Music Assistant accepts the command, and must
  /// throw when the command cannot be sent or accepted.
  Future<void> applyIntent({
    required String targetId,
    required PlaybackIntent intent,
  });
}

/// Placeholder client that fails loudly until a real Music Assistant transport is configured.
final class UnconfiguredMusicAssistantClient implements MusicAssistantClient {
  /// Creates a client placeholder for builds without Music Assistant connectivity.
  const UnconfiguredMusicAssistantClient();

  /// Always throws because Music Assistant API transport is intentionally unimplemented.
  @override
  Future<void> applyIntent({
    required String targetId,
    required PlaybackIntent intent,
  }) async {
    throw UnsupportedError(
      'Music Assistant API transport is not implemented in this prototype.',
    );
  }
}
