# Mass Mate

Mass Mate is a greenfield Flutter companion player concept for Music Assistant, currently targeting an Android touch prototype so the click wheel can be tested on-device. The first prototype centers the experience around a large click-wheel-inspired control so listeners can scrub audiobooks, make fine volume adjustments, and move through playback without targeting tiny sliders.

## Prototype goals

- Mobile-first layout that keeps transport controls reachable with one thumb.
- Large scroll wheel for coarse and fine adjustments.
- Mode switching between seek, volume, and the current prototype queue cursor.
- Visual feedback that makes wheel gestures predictable before backend integration.

## Interaction contracts

The click-wheel product contract is documented in [docs/click-wheel-contract.md](docs/click-wheel-contract.md). Treat it as the source of truth for seek, volume, queue, button, haptic, accessibility, and hit-area behavior before adding Music Assistant integration.

The wheel navigation model is documented in [docs/wheel-navigation-model.md](docs/wheel-navigation-model.md). Treat it as the source of truth for how the wheel moves between primary screens and inside nested lists.

The Music Assistant functionality mapping is documented in [docs/music-assistant-wheel-matrix.md](docs/music-assistant-wheel-matrix.md). Treat it as the source of truth for which Music Assistant features belong on the wheel, which are context actions, and which should stay out of the primary mobile playback path.

## Getting started

```bash
flutter pub get
flutter devices
flutter run -d <android-device-id>
```

Use `flutter devices` to choose an attached Android device or emulator.

## Current scope

This repository currently contains the Flutter UI shell, local interaction model, and an adapter seam for future Music Assistant playback wiring. The player UI talks to an intent-level `PlayerAdapter`; the default adapter is still the local demo backend.

Music Assistant API transport, authentication, device discovery, selected-player persistence, websocket state updates, and real playback control are intentionally left for follow-up work. The current Music Assistant classes are stubs that accept canonical playback intents and fail loudly when a real client is not configured.
