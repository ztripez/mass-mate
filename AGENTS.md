# Mass Mate

Flutter prototype for a Music Assistant companion player, currently focused on an Android touch UI shell and local click-wheel interaction model.

## Start here

Before changing wheel, playback, Music Assistant integration, navigation, or mobile interaction behavior, read these docs:

- `README.md` -- project scope and current prototype status.
- `docs/click-wheel-contract.md` -- source of truth for click-wheel seek, volume, queue, button, haptic, accessibility, and hit-area behavior.
- `docs/wheel-navigation-model.md` -- source of truth for screen navigation, list navigation, nested back stacks, and how those layers stay separate from `WheelMode`.
- `docs/wheel-ux-strictness.md` -- source of truth for deciding whether a flow needs strict wheel-first ergonomics or can use relaxed setup/admin UI.
- `docs/music-assistant-wheel-matrix.md` -- source of truth for how Music Assistant functionality maps to the wheel, buttons, context actions, UX strictness classes, adapter buckets, and priorities.
- `docs/implementation-plan.md` -- staged integration plan for turning the matrix into implementation slices.
- `docs/issue-index.md` -- active tracker entry points.

Treat the contract docs as product constraints. If implementation behavior conflicts with them, either fix the implementation or deliberately update the relevant contract in the same change.

## Commands
- If `flutter` is missing in non-interactive shells, run commands as `source "$HOME/.zshenv" && flutter ...`.
- Put Android Gradle wrapper/cache files on the work mount: `export GRADLE_USER_HOME=/mnt/work/.gradlebuilds` before `flutter run`/`flutter build apk --debug`.
- Use a Java 17 JDK for Android builds; Java 26 can fail Gradle/AGP `jlink` transforms. On Arch, install `jdk17-openjdk` and run `flutter config --jdk-dir /usr/lib/jvm/java-17-openjdk`.
- `flutter pub get` -- install dependencies from `pubspec.yaml`.
- `flutter analyze` -- run the configured `flutter_lints` rules.
- `flutter test` -- run the full Flutter test suite, including widget/layout tests and pure click-wheel intent resolution tests.
- `flutter test test/<file>.dart` -- run a focused test file; use `test/widget_test.dart` for widget/layout/haptics coverage or `test/wheel_intent_resolver_test.dart` for pure click-wheel intent resolution.
- `flutter devices` -- list Android devices/emulators before choosing a run target.
- `flutter run -d <android-device-id>` -- start the current Android prototype on a device/emulator.
- `flutter build apk --debug` -- build a prototype Android APK; release builds fail loudly until release signing is configured.
- `flutter run -d linux` / `flutter build linux` -- optional desktop smoke checks; Android is the primary target for wheel feel.

## Structure
- `lib/main.dart` is only the app bootstrap; player UI/state lives in `lib/player_screen.dart`.
- `lib/click_wheel.dart` owns wheel gesture handling, tick painting, and step haptics.
- `lib/wheel/wheel_gesture.dart` defines the raw wheel input contract emitted by `ClickWheel`.
- `lib/playback/` defines playback snapshots, seek preview modeling, wheel intent resolution, and boundary feedback.
- `lib/haptics.dart` owns the native boundary haptics MethodChannel contract.
- `lib/wheel_mode.dart` defines the shared click-wheel modes and their display/accessibility metadata.
- `test/widget_test.dart` pumps `MassMateApp` and covers layout, wheel scrolling, and haptics behavior.
- `test/wheel_intent_resolver_test.dart` covers pure click-wheel seek modeling and intent resolution without pumping widgets.
- `android/` and `linux/` are the only committed platform runners; do not assume iOS, web, macOS, or Windows scaffolding exists.

## Repo-specific guidance
- Keep work within the current prototype scope unless explicitly asked: no Music Assistant API integration, auth, device discovery, or real playback control yet.
- When Music Assistant work is explicitly requested, use `docs/music-assistant-wheel-matrix.md` and `docs/implementation-plan.md` to keep API wiring behind intent-level adapters instead of leaking MA command names into UI widgets.
- Use Mode, not Menu, for the wheel behavior switch. `WheelMode` is playback/control behavior, not screen navigation, list navigation, or output selection.
- Keep screen navigation and list navigation separate. Screen-level wheel movement chooses primary app surfaces; list-level wheel movement moves focused rows inside the active screen.
- Use `docs/wheel-ux-strictness.md` before designing a flow. Playback, seek, volume, transport, queue, and frequent session actions are strict wheel-first flows.
- Treat output selection, player grouping, provider setup, settings, diagnostics, authentication, and admin tasks as relaxed setup/admin flows when conventional UI is clearer and safer.
- Do not spend complexity optimizing low-frequency configuration before playback, queue, seeking, volume, and browsing are solid.
- Queue/playback preview is local-first: do not emit remote seek commands for every wheel tick; remote seek belongs on explicit commit.
- Bottom play/pause and transport actions must never silently commit an active seek preview.
- MODE/back must cancel active seek preview without committing it.
- Target Android touch first for validating click-wheel feel; keep Linux as a secondary desktop smoke target.
- Follow `analysis_options.yaml`: `package:flutter_lints/flutter.yaml` plus `prefer_const_constructors`.
- `pubspec.lock` is not committed and `*.lock` is ignored; `pubspec.yaml` is the dependency source of truth here.
- If adding UI behavior to the click wheel or player shell, add or update widget tests; if adding playback intent, seek modeling, or wheel gesture resolution, add or update `test/wheel_intent_resolver_test.dart` in addition to any affected widget tests.
