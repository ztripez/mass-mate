# Music Assistant Wheel Integration Plan

This plan turns the wheel mapping matrix into implementation slices.

Parent docs:

- `docs/click-wheel-contract.md`
- `docs/music-assistant-wheel-matrix.md`

## Slice 1: Contract and resolver coverage

- Extend playback intents so the UI can express every P0/P1 wheel action without knowing Music Assistant command names.
- Keep `ClickWheel` gesture-only.
- Keep seek preview local and explicit.
- Ensure play/pause, MODE/back, and transport actions do not accidentally commit active seek preview.

## Slice 2: Adapter seam

- Add a Music Assistant client abstraction around `/api` command calls.
- Add a player/queue adapter around intent-level operations.
- Keep the demo/local backend behind the same adapter shape.
- Add a fake adapter that records emitted command intents for tests.

## Slice 3: P0 real playback

- Connect selected player/output state.
- Implement play/pause, previous/next, committed seek, volume set, mute, queue list, and selected queue item playback.
- Prefer queue commands for queue-owned playback and player commands for output-level operations.
- Verify command names and schemas against the connected server's generated `/api-docs`.

## Slice 4: Wheel-native surfaces

- Add the screen/surface switcher opened by MODE hold, with Player Outputs as the path to output selection.
- Add queue cursor surface using the matrix behavior.
- Add lightweight playback options overlay for shuffle/repeat/favorite only after P0 playback is stable.
- Add browse/search navigation after queue/output behavior is no longer moving.

## Slice 5: Validation

- Add resolver tests for every matrix row marked P0 or P1.
- Add fake-adapter tests proving remote seeks are emitted only on explicit commit.
- Add golden or widget coverage for compact phone, normal phone, tall phone, and landscape/wide layout where useful.
- Add a small command-schema capture fixture from a real Music Assistant `/api-docs` instance when available.
