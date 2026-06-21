# Music Assistant Wheel Integration Plan

This plan turns the wheel mapping matrix into implementation slices.

Parent docs:

- `docs/click-wheel-contract.md`
- `docs/wheel-navigation-model.md`
- `docs/music-assistant-wheel-matrix.md`
- `docs/sendspin-player-architecture.md`

Current priority: Sendspin local playback comes first. The older Music Assistant API and wheel-surface slices below are retained as follow-up integration guidance, but they must not bypass or weaken the Sendspin ownership model.

## Sendspin-first playback slice

Issue #24 defines the Sendspin local-player ownership model in `docs/sendspin-player-architecture.md`. The first implementation wave should make Mass Mate a native Android Sendspin local player behind the existing `PlayerAdapter` / `PlaybackIntent` seam before any browse or library work is added.

Implementation order for epic #23:

1. Add the native local-player service and Flutter bridge (#25).
2. Add WebSocket transport, hello/goodbye, role activation, and typed connection states (#26).
3. Add typed Sendspin message models and dispatcher (#27).
4. Add clock synchronization and server-time to local-time mapping (#28).
5. Add stream lifecycle and binary frame buffering (#29).
6. Add the first PCM `AudioTrack` output path (#30).
7. Add FLAC and Opus only after the PCM path is stable (#31).
8. Map existing wheel playback intents to supported Sendspin controller commands (#32).
9. Map native metadata/playback/timing/buffer/error state into `PlayerState` / `PlaybackSnapshot` (#33).
10. Add fake transport/audio validation and real-server smoke tests (#34).

Constraints for this slice:

- Flutter UI emits intent-level playback operations only.
- Native Android owns connection lifecycle, clock sync, stream lifecycle, audio output, and player state reporting.
- Sendspin protocol names stay behind the Dart adapter/native bridge; UI widgets must not depend on them.
- Seek preview remains local and follows `docs/click-wheel-contract.md`; only explicit seek commit crosses the bridge.
- Protocol, transport, capability, codec, and audio errors fail visibly and must not silently fall back to the local demo adapter.
- Browse, search, library, provider administration, discovery, and auth overreach stay out of the first Sendspin playback path.

## Follow-up Slice 1: Contract and resolver coverage

- Extend playback intents so the UI can express every P0/P1 wheel action without knowing Music Assistant command names.
- Keep `ClickWheel` gesture-only.
- Keep seek preview local and explicit.
- Ensure play/pause, MODE/back, and transport actions do not accidentally commit active seek preview.

## Follow-up Slice 2: Music Assistant API adapter seam

- Add a Music Assistant client abstraction around `/api` command calls only after Sendspin local-player work has a stable adapter boundary.
- Add a player/queue adapter around intent-level operations without changing the Sendspin ownership model.
- Keep the demo/local backend behind the same adapter shape for explicit selection and tests.
- Add a fake adapter that records emitted command intents for tests.

## Follow-up Slice 3: P0 remote Music Assistant playback

- Connect selected player/output state.
- Implement play/pause, previous/next, committed seek, volume set, mute, queue list, and selected queue item playback.
- Prefer queue commands for queue-owned playback and player commands for output-level operations.
- Verify command names and schemas against the connected server's generated `/api-docs`.

## Follow-up Slice 4: Wheel-native surfaces

- Add the screen/surface switcher opened by MODE hold, with Player Outputs as the path to output selection.
- Add queue cursor surface using the matrix behavior.
- Add lightweight playback options overlay for shuffle/repeat/favorite only after P0 playback is stable.
- Add browse/search navigation after queue/output behavior is no longer moving.

## Follow-up Slice 5: Validation

- Add resolver tests for every matrix row marked P0 or P1.
- Add fake-adapter tests proving remote seeks are emitted only on explicit commit.
- Add golden or widget coverage for compact phone, normal phone, tall phone, and landscape/wide layout where useful.
- Add a small command-schema capture fixture from a real Music Assistant `/api-docs` instance when available.
