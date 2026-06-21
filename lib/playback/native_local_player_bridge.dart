import 'dart:async';

import 'package:flutter/services.dart';

import 'playback_intent.dart';
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

  /// The backend failed and requires explicit recovery.
  failed,
}

/// Intent-level command names sent over the native bridge.
///
/// These are Mass Mate operation names derived from [PlaybackIntent], not raw protocol
/// command names from the native player transport.
enum LocalPlayerCommand {
  /// Toggle play or pause.
  togglePlayPause,

  /// Seek to a committed absolute position.
  seekTo,

  /// Set the absolute volume.
  setVolume,

  /// Select an item in the active queue.
  selectQueueItem,
}

/// Typed native-local-player error categories exposed by the bridge.
enum LocalPlayerErrorKind {
  /// The selected native backend is unavailable in the current runtime.
  unavailable,

  /// The command requires an active connection, but none exists.
  notConnected,

  /// The native backend rejected the request without treating it as a crash.
  rejected,

  /// The native backend failed while processing the request.
  failed,

  /// A snapshot or reply envelope did not match the bridge contract.
  invalidEnvelope,
}

/// Exception raised when the selected native local-player backend cannot fulfill a request.
final class LocalPlayerBridgeException implements Exception {
  /// Creates a typed bridge exception with a user-visible [message].
  const LocalPlayerBridgeException({
    required this.kind,
    required this.message,
    this.details,
  });

  /// Machine-readable error category.
  final LocalPlayerErrorKind kind;

  /// Human-readable error message suitable for surfacing in the player shell.
  final String message;

  /// Optional backend-provided detail payload.
  final Object? details;

  @override
  String toString() => message;
}

/// Intent-level command envelope sent from Dart to native Android.
final class LocalPlayerCommandEnvelope {
  /// Creates a local-player command envelope.
  const LocalPlayerCommandEnvelope({required this.command, this.arguments});

  /// Command name derived from a Mass Mate playback intent.
  final LocalPlayerCommand command;

  /// Command-specific scalar arguments.
  final Map<String, Object?>? arguments;

  /// Converts a playback intent into a bridge command envelope.
  factory LocalPlayerCommandEnvelope.fromIntent(PlaybackIntent intent) {
    return switch (intent) {
      TogglePlayPausePlaybackIntent() => const LocalPlayerCommandEnvelope(
          command: LocalPlayerCommand.togglePlayPause,
        ),
      SeekToPlaybackIntent(:final position) => LocalPlayerCommandEnvelope(
          command: LocalPlayerCommand.seekTo,
          arguments: {'positionMs': position.inMilliseconds},
        ),
      SetVolumePlaybackIntent(:final volume) => LocalPlayerCommandEnvelope(
          command: LocalPlayerCommand.setVolume,
          arguments: {'volume': volume},
        ),
      SelectQueueItemPlaybackIntent(:final queueIndex) =>
        LocalPlayerCommandEnvelope(
          command: LocalPlayerCommand.selectQueueItem,
          arguments: {'queueIndex': queueIndex},
        ),
    };
  }

  /// Serializes this envelope for MethodChannel transport.
  Map<String, Object?> toMap() {
    return {
      'command': command.name,
      if (arguments != null) 'arguments': arguments,
    };
  }
}

/// Result returned by native lifecycle and command calls.
final class LocalPlayerBridgeResult {
  const LocalPlayerBridgeResult._({
    required this.accepted,
    this.error,
  });

  /// Creates an accepted bridge result.
  const LocalPlayerBridgeResult.accepted() : this._(accepted: true);

  /// Creates a rejected or failed bridge result.
  const LocalPlayerBridgeResult.failed(LocalPlayerBridgeException error)
      : this._(accepted: false, error: error);

  /// Whether the native backend accepted the request.
  final bool accepted;

  /// Error returned when [accepted] is false.
  final LocalPlayerBridgeException? error;

  /// Throws [error] when the result was not accepted.
  ///
  /// Accepted native replies are the only successful result. Rejected, failed, malformed,
  /// or contradictory envelopes become [LocalPlayerBridgeException] values so callers
  /// cannot accidentally treat native backend failure as local success.
  void throwIfFailed() {
    final failure = error;
    if (!accepted && failure != null) throw failure;
    if (!accepted) {
      throw const LocalPlayerBridgeException(
        kind: LocalPlayerErrorKind.failed,
        message: 'Native local player rejected the request.',
      );
    }
  }

  /// Parses a MethodChannel result envelope.
  ///
  /// Valid native replies are maps shaped as `{ accepted: true }` for success or
  /// `{ accepted: false, error: { code: String, message: String, details?: Object } }`
  /// for rejected or failed operations. An `accepted: true` envelope with an `error`
  /// payload is contradictory and is converted into an [LocalPlayerErrorKind.invalidEnvelope]
  /// failure instead of being accepted.
  factory LocalPlayerBridgeResult.fromMap(Object? value) {
    if (value is! Map) {
      return const LocalPlayerBridgeResult.failed(
        LocalPlayerBridgeException(
          kind: LocalPlayerErrorKind.invalidEnvelope,
          message: 'Native local player returned a malformed reply.',
        ),
      );
    }

    final accepted = value['accepted'];
    if (accepted == true) {
      if (value.containsKey('error') && value['error'] != null) {
        return const LocalPlayerBridgeResult.failed(
          LocalPlayerBridgeException(
            kind: LocalPlayerErrorKind.invalidEnvelope,
            message:
                'Native local player returned success with an error payload.',
          ),
        );
      }
      return const LocalPlayerBridgeResult.accepted();
    }
    if (accepted is! bool) {
      return const LocalPlayerBridgeResult.failed(
        LocalPlayerBridgeException(
          kind: LocalPlayerErrorKind.invalidEnvelope,
          message:
              'Native local player reply is missing a boolean accepted field.',
        ),
      );
    }

    return LocalPlayerBridgeResult.failed(
      _exceptionFromMap(
        value['error'] ?? value,
        fallbackKind: LocalPlayerErrorKind.rejected,
        fallbackMessage: 'Native local player rejected the request.',
      ),
    );
  }
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
  /// `positionMs`, `trackLengthMs`, `volume`, queue bounds, and `isPlaying`. Failed or
  /// unavailable snapshots should include an `error` payload. If native code omits that
  /// payload for those failure statuses, this parser synthesizes a typed exception so
  /// adapter subscribers still observe a visible failure instead of a normal state.
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
          : _exceptionFromMap(
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

/// Fakeable native-local-player bridge used by [NativeLocalPlayerAdapter].
///
/// Implementations own lifecycle method calls, intent-level command envelopes, and the
/// typed snapshot stream. They must not translate failures into demo state or expose raw
/// transport/protocol command names to Flutter widgets.
abstract interface class NativeLocalPlayerBridge {
  /// Typed snapshot stream emitted by the native local-player service.
  Stream<LocalPlayerSnapshot> get snapshots;

  /// Requests native local-player connection.
  Future<LocalPlayerBridgeResult> connect();

  /// Requests native local-player disconnection.
  Future<LocalPlayerBridgeResult> disconnect();

  /// Sends an intent-level local-player command envelope.
  Future<LocalPlayerBridgeResult> sendCommand(
    LocalPlayerCommandEnvelope envelope,
  );
}

/// MethodChannel/EventChannel implementation of the native local-player bridge.
///
/// Lifecycle and command requests are sent over `mass_mate/local_player`; snapshots are
/// received from `mass_mate/local_player/snapshots`. Platform exceptions and malformed
/// result or snapshot envelopes become typed bridge failures. The bridge sends only
/// Mass Mate intent-level [LocalPlayerCommandEnvelope] values and does not implement or
/// expose native transport, protocol, network, or audio behavior.
final class MethodChannelNativeLocalPlayerBridge
    implements NativeLocalPlayerBridge {
  /// Creates the platform-channel bridge.
  MethodChannelNativeLocalPlayerBridge({
    MethodChannel methodChannel = const MethodChannel(_methodChannelName),
    EventChannel eventChannel = const EventChannel(_eventChannelName),
  })  : _methodChannel = methodChannel,
        _eventChannel = eventChannel;

  static const String _methodChannelName = 'mass_mate/local_player';
  static const String _eventChannelName = 'mass_mate/local_player/snapshots';

  final MethodChannel _methodChannel;
  final EventChannel _eventChannel;

  Stream<LocalPlayerSnapshot>? _snapshots;

  @override
  Stream<LocalPlayerSnapshot> get snapshots {
    return _snapshots ??= _eventChannel.receiveBroadcastStream().map(
          LocalPlayerSnapshot.fromMap,
        );
  }

  @override
  Future<LocalPlayerBridgeResult> connect() async {
    return _invokeLifecycle('connect');
  }

  @override
  Future<LocalPlayerBridgeResult> disconnect() async {
    return _invokeLifecycle('disconnect');
  }

  @override
  Future<LocalPlayerBridgeResult> sendCommand(
    LocalPlayerCommandEnvelope envelope,
  ) async {
    try {
      final result = await _methodChannel.invokeMethod<Object?>(
        'sendCommand',
        envelope.toMap(),
      );
      return LocalPlayerBridgeResult.fromMap(result);
    } on PlatformException catch (error) {
      return LocalPlayerBridgeResult.failed(_exceptionFromPlatform(error));
    }
  }

  Future<LocalPlayerBridgeResult> _invokeLifecycle(String method) async {
    try {
      final result = await _methodChannel.invokeMethod<Object?>(method);
      return LocalPlayerBridgeResult.fromMap(result);
    } on PlatformException catch (error) {
      return LocalPlayerBridgeResult.failed(_exceptionFromPlatform(error));
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

LocalPlayerBridgeException _exceptionFromPlatform(PlatformException error) {
  return LocalPlayerBridgeException(
    kind: _kindFromCode(error.code),
    message: error.message ?? 'Native local player platform call failed.',
    details: error.details,
  );
}

LocalPlayerBridgeException _exceptionFromMap(
  Object? value, {
  required LocalPlayerErrorKind fallbackKind,
  required String fallbackMessage,
}) {
  if (value is! Map) {
    return LocalPlayerBridgeException(
      kind: fallbackKind,
      message: fallbackMessage,
      details: value,
    );
  }

  return LocalPlayerBridgeException(
    kind: _kindFromCode(value['code']?.toString()),
    message: value['message']?.toString() ?? fallbackMessage,
    details: value['details'],
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
    LocalPlayerConnectionStatus.connected =>
      null,
  };
}

LocalPlayerErrorKind _kindFromCode(String? code) {
  return switch (code) {
    'LOCAL_PLAYER_UNAVAILABLE' => LocalPlayerErrorKind.unavailable,
    'LOCAL_PLAYER_NOT_CONNECTED' => LocalPlayerErrorKind.notConnected,
    'LOCAL_PLAYER_REJECTED' => LocalPlayerErrorKind.rejected,
    'LOCAL_PLAYER_INVALID_ENVELOPE' => LocalPlayerErrorKind.invalidEnvelope,
    _ => LocalPlayerErrorKind.failed,
  };
}
