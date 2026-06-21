import 'package:flutter_test/flutter_test.dart';
import 'package:mass_mate/music_assistant/music_assistant_client.dart';
import 'package:mass_mate/music_assistant/music_assistant_player_adapter.dart';
import 'package:mass_mate/playback/playback_intent.dart';
import 'package:mass_mate/playback/player_adapter.dart';
import 'package:mass_mate/playback/player_state.dart';

void main() {
  test('MusicAssistantPlayerAdapter sends canonical intents to client',
      () async {
    final client = _RecordingMusicAssistantClient();
    final adapter = MusicAssistantPlayerAdapter(
      client: client,
      targetId: 'queue-main',
      initialState: PlayerState.localDemo(),
    );
    addTearDown(adapter.dispose);

    const seekIntent = SeekToPlaybackIntent(Duration(minutes: 24));
    await adapter.applyIntent(seekIntent);

    expect(client.records, hasLength(1));
    expect(client.records.single.targetId, 'queue-main');
    expect(client.records.single.intent, same(seekIntent));
    expect(adapter.state.playback.position, const Duration(minutes: 24));
  });

  test('MusicAssistantPlayerAdapter does not mutate state when client fails',
      () async {
    final adapter = MusicAssistantPlayerAdapter(
      client: const UnconfiguredMusicAssistantClient(),
      targetId: 'queue-main',
      initialState: PlayerState.localDemo(),
    );
    addTearDown(adapter.dispose);
    final initialState = adapter.state;

    expect(
      () => adapter.applyIntent(
        const SeekToPlaybackIntent(Duration(minutes: 24)),
      ),
      throwsUnsupportedError,
    );
    expect(adapter.state, same(initialState));
  });

  test('MusicAssistantPlayerAdapter rejects a blank target id', () {
    expect(
      () => MusicAssistantPlayerAdapter(
        client: _RecordingMusicAssistantClient(),
        targetId: ' ',
        initialState: PlayerState.localDemo(),
      ),
      throwsArgumentError,
    );
  });

  test('MusicAssistantPlayerAdapter lifecycle fails until implemented',
      () async {
    final adapter = MusicAssistantPlayerAdapter(
      client: _RecordingMusicAssistantClient(),
      targetId: 'queue-main',
      initialState: PlayerState.localDemo(),
    );
    addTearDown(adapter.dispose);

    await expectLater(
      adapter.connect(),
      throwsA(isA<PlayerAdapterException>()),
    );
    await expectLater(
      adapter.disconnect(),
      throwsA(isA<PlayerAdapterException>()),
    );
  });
}

final class _RecordedIntent {
  const _RecordedIntent({required this.targetId, required this.intent});

  final String targetId;
  final PlaybackIntent intent;
}

final class _RecordingMusicAssistantClient implements MusicAssistantClient {
  final List<_RecordedIntent> records = [];

  @override
  Future<void> applyIntent({
    required String targetId,
    required PlaybackIntent intent,
  }) async {
    records.add(_RecordedIntent(targetId: targetId, intent: intent));
  }
}
