# Issue Index

This file is intentionally small and points to the GitHub tracker for the current work breakdown.

## Active track

- Epic: #2 Click-wheel-first Music Assistant mobile player
- Epic: #12 Wheel mode and navigation UX model

## Contract docs

- `docs/click-wheel-contract.md` -- current click-wheel behavior for seek, volume, queue, buttons, haptics, accessibility, and hit areas.
- `docs/wheel-navigation-model.md` -- screen/list navigation model; use this to keep screen navigation separate from list navigation and `WheelMode`.
- `docs/wheel-ux-strictness.md` -- strictness taxonomy; use this to decide whether a future flow needs strict wheel-first ergonomics or relaxed setup/admin UI.
- `docs/music-assistant-wheel-matrix.md` -- functionality matrix; use this for Music Assistant feature mapping, Mode behavior, context actions, strictness classes, adapter buckets, and priorities.
- `docs/implementation-plan.md` -- staged integration plan for turning the matrix into implementation slices.

## Wheel UX rules

- Use Mode, not Menu, for the wheel behavior switch.
- Keep screen navigation, list navigation, and Mode behavior as separate layers.
- Treat playback and frequent session actions as strict wheel-first flows.
- Treat output selection, grouping, providers, and settings/admin as relaxed setup/admin flows when clearer.
- Prioritize playback, queue, seeking, volume, and browsing before optimizing low-frequency configuration.
