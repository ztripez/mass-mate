import 'dart:async';

import 'package:flutter/services.dart';

import 'native_local_player_contract.dart';

export 'native_local_player_contract.dart';

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

LocalPlayerBridgeException _exceptionFromPlatform(PlatformException error) {
  return LocalPlayerBridgeException(
    kind: localPlayerErrorKindFromCode(error.code),
    message: error.message ?? 'Native local player platform call failed.',
    details: error.details,
  );
}
