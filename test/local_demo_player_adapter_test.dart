import 'package:flutter_test/flutter_test.dart';
import 'package:mass_mate/playback/local_demo_player_adapter.dart';
import 'package:mass_mate/playback/playback_intent.dart';

void main() {
  test('LocalDemoPlayerAdapter exposes the audiobook demo state', () {
    final adapter = LocalDemoPlayerAdapter();

    expect(adapter.state.playerName, 'Mass Mate');
    expect(adapter.state.connectionLabel, 'Local demo');
    expect(adapter.state.mediaItem.title, 'Chapter 12: Night Drive');
    expect(adapter.state.isPlaying, isTrue);
  });

  test('LocalDemoPlayerAdapter applies playback intents to local state',
      () async {
    final adapter = LocalDemoPlayerAdapter();

    await adapter.applyIntent(const TogglePlayPausePlaybackIntent());
    expect(adapter.state.isPlaying, isFalse);

    await adapter
        .applyIntent(const SeekToPlaybackIntent(Duration(minutes: 24)));
    expect(adapter.state.playback.position, const Duration(minutes: 24));

    await adapter.applyIntent(const SetVolumePlaybackIntent(0.25));
    expect(adapter.state.playback.volume, 0.25);

    await adapter.applyIntent(const SelectQueueItemPlaybackIntent(6));
    expect(adapter.state.playback.queueIndex, 6);
  });
}
