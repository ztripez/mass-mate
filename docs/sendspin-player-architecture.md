# Sendspin Player Architecture

Status: architecture baseline for issue #24 under epic #23. This document defines the ownership model for later Sendspin implementation work; it does not implement networking, audio, protocol parsing, or new Flutter UI behavior.

## Scope

This architecture introduces Sendspin as a native Android local-player backend behind Mass Mate's existing intent-level playback seam. The Flutter wheel UI continues to resolve user input into `PlaybackIntent` values and consumes `PlayerState` / `PlaybackSnapshot` values. Native Android code owns the Sendspin connection, protocol state, timing, stream handling, audio output, and state reporting.

Authoritative product constraints remain in these documents:

- `docs/click-wheel-contract.md` for wheel seek preview, commit/cancel, haptics, accessibility, and hit-area behavior.
- `docs/wheel-navigation-model.md` for separating screen/list navigation from `WheelMode`.
- `docs/wheel-ux-strictness.md` for deciding which flows require wheel-first ergonomics.
- `docs/music-assistant-wheel-matrix.md` for intent-level playback buckets and Music Assistant feature priority.

## Terms And Assumptions

- Sendspin is the local-player protocol Mass Mate will use to receive control messages, timing data, metadata, and scheduled audio streams from a configured server.
- A local-player backend is the selected playback backend that runs on the Android device and renders audio locally instead of controlling a separate Music Assistant output.
- Role negotiation is the `client/hello` and `server/hello` exchange that decides which local-player capabilities are active for the session.
- Command envelopes are Mass Mate bridge messages derived from `PlaybackIntent` values; they are not raw Sendspin protocol messages and are not visible to Flutter widgets.
- Text protocol messages are structured Sendspin control/state messages carried by the WebSocket transport.
- Binary audio frames are WebSocket binary payloads owned by the stream/buffer/audio path and scheduled against synchronized time.
- A configured endpoint is the explicit Sendspin server URL supplied by setup/configuration; this architecture does not add discovery, auth, proxy, or provider administration.

## Non-Goals

- No browse, search, library, provider, or queue-management implementation in this slice.
- No protocol schema lock-in beyond the architecture-level roles and capability checks listed here.
- No Flutter widget dependency on raw Sendspin message names or command names.
- No silent fallback from a failed Sendspin backend to the local demo adapter.
- No remote seek commands for wheel preview ticks; only explicit committed seek intents may cross the native bridge.
- No authentication, proxy, discovery, or server administration flow beyond version-gated connection expectations.

## Ownership Boundary

| Layer | Owns | Must not own |
| --- | --- | --- |
| Flutter UI and wheel resolver | Touch input, `ClickWheel` events, `WheelMode`, local seek preview state, explicit `PlaybackIntent` creation, visual/accessibility feedback from snapshots. | Sendspin connection lifecycle, raw protocol command names, clock sync, stream buffers, audio output, or native service lifetime. |
| Dart `PlayerAdapter` and native bridge | Adapter selection, intent-level command bridge, snapshot subscription, the sole conversion from native snapshot envelopes into `PlayerState` / `PlaybackSnapshot`, visible error propagation. | Protocol parsing, audio scheduling, codec decisions, hidden fallback to demo state, native state aggregation, or route-scoped connection state. |
| Android local-player service | Service lifecycle, configured endpoint connection, Sendspin handshake, reconnect/disconnect policy, command dispatch, state aggregation, error state, snapshot reporting to Dart. | Flutter route decisions, wheel gesture interpretation, browse/library commands, or UI-specific presentation. |
| Sendspin protocol layer | Text message models, binary frame demultiplexing, dispatcher, supported roles, supported commands, version/capability validation. | Android audio APIs, Flutter platform channel details, or UI state projection. |
| Timing and clock sync | Monotonic local clock, server-to-local time mapping, sync quality, outlier/stale-sync handling, timing diagnostics. | Seek preview policy, UI progress painting outside reported snapshots, or protocol transport ownership. |
| Stream and buffer layer | `stream/start`, `stream/clear`, `stream/end`, binary audio frame ordering, buffer depth, dropped-frame counters, stream-owned state. | Codec capability advertisement, final audio sink writes, or Flutter state models. |
| Audio sink and codecs | Supported codec probing, decode/passthrough, Android `AudioTrack` stream-mode output, write scheduling, underrun/write failure reporting. | Wheel commands, Dart snapshots, or protocol role negotiation beyond reporting supported codecs. |

There is one native player-state aggregation owner: the Android local-player service. There is one Dart model conversion owner: the Dart `PlayerAdapter` / native bridge mapper that converts native snapshot envelopes into `PlayerState` and `PlaybackSnapshot`.

The current Android host only registers the `mass_mate/haptics` channel in `MainActivity.kt`. Sendspin work should add a separate native local-player service boundary instead of putting long-lived connection or audio code in `MainActivity`.

## Command Flow

The Flutter-to-native command bridge is an adapter boundary, not a protocol boundary exposed to widgets. It may be implemented with platform channels or a generated typed bridge, but it must preserve these semantics:

- Dart sends Mass Mate operation envelopes derived from `PlaybackIntent` values.
- Native code replies with accepted, rejected, or failed results that the Dart adapter surfaces without pretending local success.
- Command envelopes may include target ids, positions, volume levels, or request ids, but they must not require UI widgets to know Sendspin command names.
- The bridge remains usable with a fake native implementation in Dart/widget tests.

The command path is:

1. `ClickWheel` emits gesture/button input only.
2. Dart resolver/state code applies the wheel contracts and creates a `PlaybackIntent` such as play/pause, committed seek, volume, mute, previous/next, stop, or queue-item selection when that intent exists.
3. The active `PlayerAdapter` receives `applyIntent(intent)`.
4. The Sendspin-backed adapter sends an intent-level command envelope over the Flutter-to-native bridge. The envelope names Mass Mate operations, not raw protocol commands.
5. The Android local-player service validates connection readiness, protocol version, roles, capabilities, and supported commands.
6. The native Sendspin controller maps the accepted Mass Mate operation to the appropriate Sendspin controller command and sends it on the active transport.
7. Native code reports acceptance, rejection, or failure back through the bridge. Rejections and protocol errors are visible to Flutter and must not be projected as successful local demo state.

Seek semantics are fixed by `docs/click-wheel-contract.md`: wheel movement changes a local preview target; MODE/back cancels preview; bottom play/pause and transport do not commit preview; only wheel release or center commit emits a committed seek intent. Therefore the native bridge only receives a seek command after commit.

## Snapshot And Event Flow

The native-to-Flutter snapshot bridge is a stream of typed local-player state and typed errors. It is separate from command replies because playback state can change from server events, stream events, timing updates, audio sink failures, or reconnect transitions even when Flutter has not just sent a command.

The snapshot path is:

1. Sendspin server messages, stream state, timing samples, audio sink state, and native service state are aggregated by the Android local-player service.
2. Native snapshot reporting emits typed player snapshots and typed errors through a stream bridge to Dart.
3. The Dart bridge maps snapshots into `PlayerState` and `PlaybackSnapshot` for existing UI consumption.
4. Flutter widgets render the latest state and error indicators without knowing which Sendspin messages produced them.
5. Missing metadata does not erase known fields unless the protocol explicitly clears them. Errors are represented as errors, not as local demo snapshots.

Initial `PlayerState` mapping should cover the existing UI fields first: player name, connection label, media title/subtitle, committed position, duration, volume, queue cursor bounds, and play/pause state. Later issues may add debug-only timing, buffer, codec, and error details without changing the command ownership model.

## Native Android Service Lifecycle

The local-player service is independent of Flutter route lifecycle. Navigation between screens, rebuilding widgets, or temporarily leaving the Now Playing route must not tear down the Sendspin connection, clock sync, active stream, buffer, or audio sink.

The service lifecycle boundary should follow these rules:

- Start or bind when the Sendspin backend is selected and a configured endpoint is available.
- Own one active Sendspin connection session at a time for the selected local-player backend.
- Send the handshake on connect and goodbye on deliberate disconnect when the protocol state allows it.
- Keep emitting snapshots while connected, reconnecting, disconnecting, or failed.
- Release transport, buffers, decoders, and `AudioTrack` resources on explicit disconnect, fatal protocol failure, or app/service shutdown.
- Treat service restart as a new protocol session that must handshake again and rebuild clock/stream state from server messages.

Flutter may subscribe, unsubscribe, or resubscribe to the snapshot bridge as routes change. Those subscriptions observe the service; they do not define whether the service is connected.

## First Scoped Sendspin Roles

Roles are described here at architecture level only. Concrete payload schemas must be verified during implementation against the Sendspin server used for development.

The first Mass Mate local-player session should advertise roles for:

- A local audio player that can receive scheduled audio streams.
- A controller target that can accept playback operations from Mass Mate wheel intents.
- State reporting so Mass Mate can publish local player state, timing quality, buffer state, and errors.
- Time synchronization so audio frames can be scheduled against server time.

Handshake expectations:

- `client/hello` advertises the client identity, protocol version, requested roles, supported codecs, and supported command families.
- `server/hello` activates compatible roles and reports protocol/capability information needed before commands or audio are accepted.
- Any required role that is missing or rejected fails visibly and leaves the player in an explicit error state.
- Optional roles may be disabled only when their absence does not violate the current slice's required behavior.

## Transport Strategy

Initial transport is WebSocket to a configured Sendspin endpoint. Configuration should provide the endpoint explicitly; this slice does not add server discovery, auth flows, proxying, or provider administration.

Transport expectations:

- Use a native transport abstraction so protocol code can run against a fake transport in tests.
- WebSocket carries text protocol messages and binary audio frames.
- Reconnect behavior is deterministic and owned by the native service, not by Flutter widgets.
- Fake transport tests cover handshake, state, commands, stream lifecycle, and binary frames before real-server smoke tests.
- No fallback transport is selected silently when the configured endpoint fails.

Authentication and proxy support are deferred until a later issue verifies concrete Sendspin deployment needs. If a configured server requires a capability or auth mode this client does not support, connection fails visibly instead of degrading into an unsupported partial player.

## Version And Capability Gating

The native service must gate Sendspin use before reporting the backend as ready.

Required gates:

- Protocol version compatibility.
- Activated handshake roles.
- Supported audio codecs and stream formats.
- Supported playback commands for the currently exposed `PlaybackIntent` operations.
- Required timing capability for scheduled audio playback.
- Required binary frame behavior for the selected stream format.

Unsupported required capabilities fail visibly with a typed connection/player error. Unsupported optional commands are exposed to Flutter as unavailable so UI affordances can provide invalid-action feedback instead of sending blind commands. Codec fallback is allowed only when both sides explicitly negotiated a supported fallback, such as baseline PCM.

## Dependency Map To Child Issues

| Issue | Role in this architecture | Depends on |
| --- | --- | --- |
| #25 Native local player service and Flutter bridge | Adds the Android service boundary, Dart adapter bridge, command envelope, snapshot stream, and lifecycle separation from `MainActivity` / Flutter routes. | #24 |
| #26 Sendspin transport and handshake | Implements WebSocket transport, hello/goodbye, role activation, connection states, and deterministic reconnect using fake transport tests. | #24, #25 |
| #27 Sendspin protocol dispatcher and message models | Defines typed protocol messages and dispatches server events without UI dependencies. | #24, #26 |
| #28 Sendspin clock synchronization and timing model | Adds monotonic clock mapping and sync quality used by audio scheduling and snapshot diagnostics. | #24, #27 |
| #29 Sendspin stream lifecycle and binary frame buffer | Handles stream start/clear/end and ordered binary frame buffering. | #24, #27, #28 |
| #30 PCM audio pipeline and Android AudioTrack output | Creates the first audible Android path using negotiated PCM frames and timing gates. | #24, #28, #29 |
| #31 Sendspin codec support for FLAC and Opus | Adds codec probing/advertisement and format handling after the PCM path is stable. | #24, #30 |
| #32 Sendspin controller commands from wheel intents | Maps existing intent-level playback operations to supported Sendspin commands while preserving local seek preview. | #24, #25, #26, #27 |
| #33 Sendspin metadata and playback snapshot bridge | Maps native metadata, timing, buffer, playback, and error state into Dart `PlayerState` / `PlaybackSnapshot`. | #24, #25, #27, #28, #29 |
| #34 Sendspin validation harness and real-server smoke test | Adds fake transport/audio tests, clock tests, protocol validation, debug logging, and real-server smoke checklist. | #24 plus the implementation issues under test |

## Child Issue Acceptance Guidance

| Issue | Accepted when | Must not |
| --- | --- | --- |
| #25 | Android exposes a route-independent local-player service boundary, Dart has a Sendspin-backed `PlayerAdapter`, command envelopes and snapshot stream are fakeable in tests, and unavailable/failing backend states are visible. | Put long-lived Sendspin work in `MainActivity`, leak protocol names into widgets, or silently select the demo adapter after backend failure. |
| #26 | WebSocket transport, hello/goodbye, required-role activation, connection states, reconnect policy, and fake transport tests are implemented with visible failures for rejected roles or endpoint errors. | Treat the backend as ready before version/role gates pass or choose another transport silently. |
| #27 | Typed protocol messages and dispatcher route server events to native owners with parser/dispatcher tests for valid, unknown, malformed, and unsupported messages. | Let Flutter parse protocol messages or turn unknown required protocol data into success. |
| #28 | Clock sync maps server time to Android monotonic time, reports sync quality/stale/outlier states, and has deterministic unit tests for drift and stale sync. | Schedule audio from wall-clock time or hide bad sync behind optimistic ready state. |
| #29 | Stream start/clear/end and binary frame ordering feed a bounded buffer with tests for late, duplicate, missing, clear, and end conditions. | Let binary frames bypass stream ownership or erase errors by resetting to demo playback. |
| #30 | Negotiated PCM reaches Android `AudioTrack` stream-mode output through a fakeable audio sink with tests for underrun, write failure, clear, stop, and fatal audio errors. | Add FLAC/Opus before PCM is stable or report audible playback before audio writes are accepted. |
| #31 | FLAC and Opus are advertised only when supported, decoded or passed through according to negotiated capability, and covered by codec selection/failure tests. | Guess codec support, silently fall back without negotiation, or make codecs a prerequisite for PCM. |
| #32 | Existing `PlaybackIntent` operations map to supported Sendspin controller commands with tests proving seek preview stays local and one committed seek sends one backend command. | Add raw Sendspin command names to Flutter widgets or send preview ticks over the bridge. |
| #33 | Native metadata, position, duration, queue bounds, volume, play state, timing, buffer, connection, and error envelopes map into existing Dart state with bridge tests. | Create a parallel Flutter player-state model or clear known metadata unless the protocol explicitly clears it. |
| #34 | Fake transport, fake audio, clock, protocol, command, and bridge tests run in automation, and a real-server smoke checklist covers connection, roles, PCM audio, commands, snapshots, and visible failures. | Make real-server access the only validation path or introduce browse/library validation into the local-player smoke gate. |

## Validation Gates For Later Issues

Later implementation issues should keep these gates green:

- `flutter analyze` and `flutter test` for Dart/UI and bridge mapping changes.
- Kotlin/JVM unit tests for protocol models, dispatcher, handshake, clock sync, stream buffer, command mapping, and fake transport behavior.
- Fake audio sink tests for clear, stop, late frames, underruns, write failures, and fatal audio errors.
- Fake transport tests proving protocol errors fail loudly and never switch to the demo adapter.
- Resolver/widget tests proving seek preview remains local and committed seek emits exactly one backend command.
- Real-server smoke checklist for configured endpoint connection, hello role activation, audible PCM playback, command dispatch, snapshot updates, and visible failure states.

Browse and library validation belongs to future browse/library slices and must not be introduced as a dependency for the first Sendspin local-player path.
