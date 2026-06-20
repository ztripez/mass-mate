# Mass Mate

Mass Mate is a greenfield Flutter companion player concept for Music Assistant, currently targeting an Android touch prototype so the click wheel can be tested on-device. The first prototype centers the experience around a large click-wheel-inspired control so listeners can scrub audiobooks, make fine volume adjustments, and move through playback without targeting tiny sliders.

## Prototype goals

- Mobile-first layout that keeps transport controls reachable with one thumb.
- Large scroll wheel for coarse and fine adjustments.
- Mode switching between seek, volume, and queue navigation.
- Visual feedback that makes wheel gestures predictable before backend integration.

## Interaction contracts

The click-wheel product contract is documented in [docs/click-wheel-contract.md](docs/click-wheel-contract.md). Treat it as the source of truth for seek, volume, queue, button, haptic, accessibility, and hit-area behavior before adding Music Assistant integration.

The Music Assistant functionality mapping is documented in [docs/music-assistant-wheel-matrix.md](docs/music-assistant-wheel-matrix.md). Treat it as the source of truth for which Music Assistant features belong on the wheel, which are context actions, and which should stay out of the primary mobile playback path.

## Getting started

```bash
flutter pub get
flutter devices
flutter run -d <android-device-id>
```

Use `flutter devices` to choose an attached Android device or emulator.

## Current scope

This repository currently contains the Flutter UI shell and local interaction model only. Music Assistant API integration, authentication, device discovery, and real playback control are intentionally left for follow-up work.
