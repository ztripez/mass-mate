# Mass Mate

Flutter prototype for a Music Assistant companion player, currently focused on an Android touch UI shell and local click-wheel interaction model.

## Commands
- If `flutter` is missing in non-interactive shells, run commands as `source "$HOME/.zshenv" && flutter ...`.
- Put Android Gradle wrapper/cache files on the work mount: `export GRADLE_USER_HOME=/mnt/work/.gradlebuilds` before `flutter run`/`flutter build apk`.
- Use a Java 17 JDK for Android builds; Java 26 can fail Gradle/AGP `jlink` transforms. On Arch, install `jdk17-openjdk` and run `flutter config --jdk-dir /usr/lib/jvm/java-17-openjdk`.
- `flutter pub get` -- install dependencies from `pubspec.yaml`.
- `flutter analyze` -- run the configured `flutter_lints` rules.
- `flutter test` -- run all widget tests.
- `flutter test test/widget_test.dart` -- run the current focused test file.
- `flutter devices` -- list Android devices/emulators before choosing a run target.
- `flutter run -d <android-device-id>` -- start the current Android prototype on a device/emulator.
- `flutter build apk` -- build the Android APK.
- `flutter run -d linux` / `flutter build linux` -- optional desktop smoke checks; Android is the primary target for wheel feel.

## Structure
- `lib/main.dart` is the full app entrypoint and currently contains the UI, state, click-wheel gesture handling, and formatting helper.
- `test/widget_test.dart` is the only test file; it pumps `MassMateApp` and asserts the player shell renders.
- `android/` and `linux/` are the only committed platform runners; do not assume iOS, web, macOS, or Windows scaffolding exists.

## Repo-specific guidance
- Keep work within the current prototype scope unless explicitly asked: no Music Assistant API integration, auth, device discovery, or real playback control yet.
- Target Android touch first for validating click-wheel feel; keep Linux as a secondary desktop smoke target.
- Follow `analysis_options.yaml`: `package:flutter_lints/flutter.yaml` plus `prefer_const_constructors`.
- `pubspec.lock` is not present and `*.lock` is ignored; `pubspec.yaml` is the dependency source of truth here.
- If adding behavior to the click wheel or player shell, add or update widget tests rather than relying only on manual `flutter run` checks.
