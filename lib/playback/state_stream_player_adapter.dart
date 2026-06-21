import 'dart:async';

import 'player_adapter.dart';
import 'player_state.dart';

/// Shared [PlayerAdapter.states] stream ownership for adapters with mutable state.
///
/// Subclasses call [emitState] after accepting a new [PlayerState] and call
/// [emitStateError] when a backend failure should be visible to state subscribers. The
/// canonical [dispose] implementation closes the stream controller without implying a
/// backend disconnect.
abstract base class StateStreamPlayerAdapter implements PlayerAdapter {
  final StreamController<PlayerState> _states =
      StreamController<PlayerState>.broadcast(sync: true);

  @override
  Stream<PlayerState> get states => _states.stream;

  /// Emits [state] to [states].
  void emitState(PlayerState state) {
    if (_states.isClosed) return;
    _states.add(state);
  }

  /// Emits [error] to [states] without changing the current [state].
  void emitStateError(Object error, [StackTrace? stackTrace]) {
    if (_states.isClosed) return;
    _states.addError(error, stackTrace);
  }

  @override
  Future<void> dispose() async {
    await _states.close();
  }
}
