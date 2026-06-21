import 'native_local_player_error.dart';
import 'playback_snapshot.dart';
import 'player_state.dart';

/// Native local-player connection state reported through the platform bridge.
enum LocalPlayerConnectionStatus {
  /// The Android service or future transport is not available for use.
  unavailable,

  /// No active local-player connection exists.
  disconnected,

  /// The native service is attempting to establish a connection.
  connecting,

  /// The native service reports an active local-player session.
  connected,

  /// The Sendspin handshake completed and the local player is ready.
  ready,

  /// The backend failed and requires explicit recovery.
  failed,
}

/// Typed local-player snapshot envelope received from native Android.
final class LocalPlayerSnapshot {
  /// Creates a typed snapshot of the native local player.
  const LocalPlayerSnapshot({
    required this.connectionStatus,
    required this.playerName,
    required this.connectionLabel,
    required this.mediaTitle,
    required this.mediaSubtitle,
    required this.position,
    required this.trackLength,
    required this.volume,
    required this.queueIndex,
    required this.queueMinIndex,
    required this.queueMaxIndex,
    required this.isPlaying,
    this.error,
  });

  /// Native connection status.
  final LocalPlayerConnectionStatus connectionStatus;

  /// Player or app display name.
  final String playerName;

  /// Human-readable connection label.
  final String connectionLabel;

  /// Active media title.
  final String mediaTitle;

  /// Active media subtitle.
  final String mediaSubtitle;

  /// Committed playback position.
  final Duration position;

  /// Active item duration.
  final Duration trackLength;

  /// Current volume from 0.0 to 1.0.
  final double volume;

  /// One-based current queue index.
  final int queueIndex;

  /// Minimum selectable one-based queue index.
  final int queueMinIndex;

  /// Maximum selectable one-based queue index.
  final int queueMaxIndex;

  /// Whether the native player reports active playback.
  final bool isPlaying;

  /// Optional typed backend error included with the snapshot.
  final LocalPlayerBridgeException? error;

  /// Converts this bridge snapshot into the existing UI state model.
  PlayerState toPlayerState() {
    return PlayerState(
      playerName: playerName,
      connectionLabel: connectionLabel,
      mediaItem: PlaybackMediaItem(
        title: mediaTitle,
        subtitle: mediaSubtitle,
      ),
      playback: PlaybackSnapshot(
        position: position,
        trackLength: trackLength,
        volume: volume,
        queueIndex: queueIndex,
        queueMinIndex: queueMinIndex,
        queueMaxIndex: queueMaxIndex,
      ),
      isPlaying: isPlaying,
    );
  }

  /// Parses a snapshot envelope from the EventChannel.
  ///
  /// Valid snapshot envelopes include `connectionStatus`, player/media labels,
  /// `positionMs`, `trackLengthMs`, `volume`, queue bounds, and `isPlaying`.
  /// Failed or unavailable snapshots should include an `error` payload. If
  /// native code omits that payload, this parser synthesizes a typed exception.
  factory LocalPlayerSnapshot.fromMap(Object? value) {
    if (value is! Map) {
      throw const LocalPlayerBridgeException(
        kind: LocalPlayerErrorKind.invalidEnvelope,
        message: 'Native local player emitted a malformed snapshot.',
      );
    }

    try {
      final connectionStatus = _enumByName(
        LocalPlayerConnectionStatus.values,
        _readString(value, 'connectionStatus'),
      );
      final explicitError = value['error'] == null
          ? null
          : exceptionFromLocalPlayerEnvelope(
              value['error'],
              fallbackKind: LocalPlayerErrorKind.failed,
              fallbackMessage: 'Native local player reported an error.',
            );
      return LocalPlayerSnapshot(
        connectionStatus: connectionStatus,
        playerName: _readString(value, 'playerName'),
        connectionLabel: _readString(value, 'connectionLabel'),
        mediaTitle: _readString(value, 'mediaTitle'),
        mediaSubtitle: _readString(value, 'mediaSubtitle'),
        position: Duration(milliseconds: _readInt(value, 'positionMs')),
        trackLength: Duration(milliseconds: _readInt(value, 'trackLengthMs')),
        volume: _readDouble(value, 'volume'),
        queueIndex: _readInt(value, 'queueIndex'),
        queueMinIndex: _readInt(value, 'queueMinIndex'),
        queueMaxIndex: _readInt(value, 'queueMaxIndex'),
        isPlaying: _readBool(value, 'isPlaying'),
        error: explicitError ?? _synthesizeStatusError(connectionStatus),
      );
    } on LocalPlayerBridgeException {
      rethrow;
    } on Object catch (error) {
      throw LocalPlayerBridgeException(
        kind: LocalPlayerErrorKind.invalidEnvelope,
        message: 'Native local player snapshot did not match the contract.',
        details: error,
      );
    }
  }
}

String _readString(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value is String) return value;
  throw LocalPlayerBridgeException(
    kind: LocalPlayerErrorKind.invalidEnvelope,
    message: 'Native local player snapshot field `$key` must be a string.',
  );
}

int _readInt(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value is int) return value;
  throw LocalPlayerBridgeException(
    kind: LocalPlayerErrorKind.invalidEnvelope,
    message: 'Native local player snapshot field `$key` must be an integer.',
  );
}

double _readDouble(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value is int) return value.toDouble();
  if (value is double) return value;
  throw LocalPlayerBridgeException(
    kind: LocalPlayerErrorKind.invalidEnvelope,
    message: 'Native local player snapshot field `$key` must be numeric.',
  );
}

bool _readBool(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value is bool) return value;
  throw LocalPlayerBridgeException(
    kind: LocalPlayerErrorKind.invalidEnvelope,
    message: 'Native local player snapshot field `$key` must be a boolean.',
  );
}

T _enumByName<T extends Enum>(List<T> values, String name) {
  for (final value in values) {
    if (value.name == name) return value;
  }
  throw LocalPlayerBridgeException(
    kind: LocalPlayerErrorKind.invalidEnvelope,
    message: 'Native local player emitted unknown enum value `$name`.',
  );
}

LocalPlayerBridgeException? _synthesizeStatusError(
  LocalPlayerConnectionStatus status,
) {
  return switch (status) {
    LocalPlayerConnectionStatus.unavailable => const LocalPlayerBridgeException(
        kind: LocalPlayerErrorKind.unavailable,
        message:
            'Native local player reported unavailable without error details.',
      ),
    LocalPlayerConnectionStatus.failed => const LocalPlayerBridgeException(
        kind: LocalPlayerErrorKind.failed,
        message: 'Native local player reported failure without error details.',
      ),
    LocalPlayerConnectionStatus.disconnected ||
    LocalPlayerConnectionStatus.connecting ||
    LocalPlayerConnectionStatus.connected ||
    LocalPlayerConnectionStatus.ready =>
      null,
  };
}
