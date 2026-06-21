# Music Assistant Wheel Mapping Matrix

Last researched: 2026-06-20

This document maps Music Assistant functionality to the Mass Mate click-wheel control model.
It extends `docs/click-wheel-contract.md`, which remains the lower-level interaction contract for the current prototype's ring/button behavior, haptics, accessibility, hit areas, and preview semantics.
Screen and list navigation state is defined in `docs/wheel-navigation-model.md`; matrix rows that refer to browse, queue, output, or settings surfaces delegate their navigation stack behavior to that model.

The goal is not to expose every Music Assistant feature through the wheel. The goal is to make the wheel the primary playback surface for mobile use, especially for long-form music, podcasts, and audiobooks where ordinary mobile sliders are painful.

Wheel UX strictness is documented in `docs/wheel-ux-strictness.md`. That classification is part of this matrix: backend priority does not mean every surface should receive the same one-handed, rapid-use wheel optimization.

## Prototype versus Music Assistant target

`docs/click-wheel-contract.md` is the source of truth for current prototype behavior. The matrix below describes the intended Music Assistant-integrated target when Music Assistant playback, chapter metadata, relative skip commands, precision options, and overlays exist.

Current prototype seek behavior intentionally differs from the target rows in these places:

- Center in Seek mode commits an active preview; with no active preview, it cycles to the next wheel mode. The Music Assistant target may later use the no-preview center action for a precision toggle or seek options.
- Left/right in Seek mode apply the same adaptive preview movement as wheel rotation. The Music Assistant target may later prefer chapter previous/next when chapter metadata exists, or a fixed relative skip such as +/-30 seconds when it does not.
- Center in current Volume and Queue prototype modes cycles wheel mode. The Music Assistant target rows below reserve those center actions for mute/unmute and Queue screen/list selection after real playback integration exists.
- Left/right in current Volume and Queue prototype modes apply the same small movement as wheel rotation. The Music Assistant target rows below reserve those buttons for previous/next transport in Volume mode and page movement in the Queue screen/list layer.

Future implementation must update both this matrix and `docs/click-wheel-contract.md` when a target Music Assistant behavior becomes current prototype behavior.

## Source assumptions

- Music Assistant exposes an API for custom interfaces at `/api` and generated server-specific docs at `/api-docs`.
- Queue-first playback commands are available under `player_queues/*`.
- Player/output-level commands are available under `players/cmd/*`.
- The exact command schema should be verified against the connected Music Assistant server's generated `/api-docs` before final adapter implementation.
- Wheel preview state is local. Remote Music Assistant commands should only be emitted for committed actions.

Reference links:

- Music Assistant API docs: https://www.music-assistant.io/api/
- Music Assistant usage docs: https://www.music-assistant.io/usage/
- Music Assistant queue controller: https://github.com/music-assistant/server/blob/dev/music_assistant/controllers/player_queues.py
- Music Assistant player controller: https://github.com/music-assistant/server/blob/dev/music_assistant/controllers/players/controller.py
- Mass Mate click-wheel contract: ./click-wheel-contract.md

## Control surfaces

| Surface | Purpose | Wheel role |
| --- | --- | --- |
| Screen-level navigation | Choose among Now Playing, Queue, Library, Search, Player Outputs, and Settings | Long-MODE opens the global surface switcher; rotation changes only the screen candidate until center/select confirms |
| List-level navigation | Move inside the focused list owned by the active screen | Rotation changes row focus and scroll only; center/select opens or selects rows; Back/MODE exits according to `docs/wheel-navigation-model.md` |
| Now Playing | Default playback surface | Global transport, active Mode status, current metadata, preview feedback |
| Seek Mode | Scrub current media | Duration-aware local preview, committed seek only on explicit commit |
| Volume Mode | Control active player or group volume | Continuous level adjustment, mute toggle |
| Queue screen / Queue Items list | Inspect and act on current queue | Queue Items list movement, play selected item, queue item actions |
| Browse surface | Navigate library/search results | List navigation and item action sheet |
| Player Outputs surface | Choose active player, speaker, output, or group | Relaxed setup list navigation and player/group action sheets |
| Playback options overlay | Shuffle, repeat, radio continuation, favorite | Small option list with explicit toggles |

## UX strictness application

`docs/wheel-ux-strictness.md` is the canonical taxonomy for the strictness classes named in the main matrix. This document applies class names only; it does not redefine their expectations.

Every feature/capability row below has a UX strictness class. Future implementation issues should treat that row as the source of truth for whether a capability needs very strict wheel-first behavior, strict session behavior, medium browsing behavior, relaxed setup behavior, or ordinary settings/admin UI.

## Music Assistant target button mapping

| Control | Global rule | Screen navigation | List navigation | Seek Mode | Volume Mode | Queue screen/list | Browse/output overlays |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Ring | Adjust the focused scalar or list only | Move `screenCandidate`; no playback or list mutation | Move focused row/scroll offset | Move seek preview target | Adjust volume | Move queue cursor | Move list cursor |
| Center | Confirm/select, never surprise-mutate | Activate candidate and enter its default list when applicable | Open/select focused row | Commit active preview; target behavior may use no-preview center for precision/options | Toggle mute | Play selected queue item | Open/select focused item |
| MODE/top | Back first, mode-cycle second | Change active Mode only; do not change screen candidate | Exit to owning screen context and preserve list stack | Cancel preview and cycle Mode when on Now Playing | Cycle Mode when on Now Playing | Queue Items uses list navigation behavior; `WheelMode.queue` cycles only when Now Playing has no navigation focus | Back/up |
| Left/right | Transport unless local context has stronger meaning | Transport still uses active Mode/global semantics | Page list when supported | Target behavior: chapter previous/next, else +/-30s preview | Previous/next track | Page queue | Page list / alpha jump |
| Bottom | Always play/pause committed playback | Play/pause committed playback | Play/pause current playback | Play/pause without committing preview | Play/pause | Play/pause current playback | Play/pause current playback |
| Center hold | Open context actions | Screen-level actions for candidate/active screen | Focused item actions | Seek options | Volume/output options | Queue item actions | Item/player actions |
| MODE hold | Open global surface switcher / screen-level navigation | Keep screen-level navigation active | Return to screen-level navigation; preserve list stack | Use the primary screen order in `docs/wheel-navigation-model.md` | Same | Same | Same |
| Left/right hold | Continuous transport or page repeat | Transport repeat only if deliberate | Page repeat | Rewind/fast-forward preview | Previous/next repeat only if deliberate | Page repeat | Page repeat |
| Bottom hold | Stop | Stop | Stop | Stop | Stop | Stop | Stop |

## Functionality matrix

| Feature / capability | Screen context | List context | Wheel Mode | Rotate behavior | Center behavior | Back behavior | Long-press behavior | UX strictness | Notes / non-goals | MA integration target | Priority |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Screen navigation | Global surface switcher from any screen | None | Active Mode is preserved, not changed | Move screen candidate only | Activate candidate; enter default list or clear navigation focus on Now Playing | Cancel candidate or return through screen history per navigation model | MODE hold enters/keeps screen navigation | Browsing/navigation | Delegates stack/history details to `docs/wheel-navigation-model.md`; no playback, queue, output, or Mode mutation by itself | Local navigation state only | P1 |
| List navigation | Queue, Library, Search, Player Outputs | Active screen's focused list frame | Active Mode is preserved while list captures wheel input | Move focused row/scroll only | Open/select focused row through explicit intent | Pop child frame or exit to screen context per navigation model | Center hold opens item actions; MODE hold returns to screen navigation | Browsing/navigation | Delegates nested stack and invalidated-focus rules to `docs/wheel-navigation-model.md`; concrete row actions remain separate feature rows | Local navigation state + list data adapters | P1 |
| Mode behavior switch | Now Playing and any context where navigation is not consuming MODE | None | Seek, Volume, Queue | Rotation continues to use the active Mode when navigation layer is `none` | Mode-specific; current prototype cycles Mode only where documented | MODE/back cancels seek preview before changing state | Long-MODE is screen navigation, not a Mode picker | Immediate playback | Use Mode terminology. Mode switching changes wheel behavior only; it is not screen/list navigation and not output selection. | Local resolver state only | P0 |
| Play / pause | Global, especially Now Playing | None | Any | No-op | Mode-specific | Back/cycle as current context defines | Hold bottom = stop | Immediate playback | Bottom play/pause toggles committed playback and must not commit active seek preview | `player_queues/play_pause` or `players/cmd/play_pause` | P0 |
| Stop | Global | None | Any | No-op | - | - | Hold bottom | Immediate playback | Secondary transport; keep out of accidental center/select paths | `player_queues/stop` or `players/cmd/stop` | P1 |
| Previous / next track | Global transport | None | Any, Volume target uses transport buttons directly | No-op | - | - | Hold left/right = rewind/fast-forward or repeated skip | Immediate playback | Immediate skip/transport must stay wheel/button-first and not require screen hops | `player_queues/previous`, `player_queues/next`, or `players/cmd/*` equivalents | P0 |
| Audiobook / podcast chapter movement | Now Playing | None | Seek | Move seek preview | Commit preview | Cancel preview + cycle/back | Hold left/right = continuous seek preview | Immediate playback | If chapter data is absent, surface a visible/accessibility "chapters unavailable" state and explicitly switch the affordance to relative skip before preview/commit; never silently reinterpret a chapter action as a timed seek | `player_queues/seek`, `players/cmd/seek`, or relative skip support when available | P0 |
| Scrub current track | Now Playing | None | Seek | Move preview target | Commit preview | Cancel preview + cycle/back | Center hold = precision/options | Immediate playback | Preview remains local; remote seek emits only on explicit commit | `player_queues/seek(position)` or `players/cmd/seek(position)` | P0 |
| Fine/coarse seek precision | Now Playing | None | Seek | Uses active precision | Target behavior: toggle precision when no preview is active | Cycle/back | Center hold = seek options | Immediate playback | Resolver-local until a committed seek is emitted | Local resolver only until commit | P0 |
| Volume | Now Playing | None | Volume | Adjust volume | Toggle mute | Cycle/back | Center hold = volume/output options | Immediate playback | Immediate active-target volume control; do not route through setup flows | `players/cmd/volume_set`, `volume_up`, `volume_down` | P0 |
| Mute | Now Playing | None | Volume | Adjust volume | Toggle mute | Cycle/back | - | Immediate playback | Immediate active-target mute; may share Volume Mode center behavior | `players/cmd/volume_mute` or group mute equivalent | P0 |
| Group volume | Now Playing with group active | None | Volume | Adjust active group volume | Toggle group mute | Cycle/back | Center hold = choose group vs member volume | Immediate playback | Active group volume remains immediate playback; group membership editing is a relaxed setup flow below | `players/cmd/group_volume`, `group_volume_up/down`, `group_volume_mute` | P1 |
| Active queue navigation | Queue screen | Queue Items | Queue, or list layer once implemented | Move queue cursor | Play selected item | Cycle/back or list-root exit per navigation model | Center hold = queue item actions | Frequent session controls | Queue actions are strict session controls, not relaxed setup | `player_queues/items`, `player_queues/play_index` or equivalent | P0 |
| Remove queue item | Queue screen | Queue item actions | Queue/list action layer | Scroll action list | Confirm remove | Back | Center hold on queue item | Frequent session controls | Mutating queue action requires explicit confirm semantics | `player_queues/delete_item` | P1 |
| Move queue item | Queue screen | Queue item actions | Queue/list action layer | Move target position | Drop/confirm | Cancel | Center hold = queue item actions | Frequent session controls | Queue reorder is strict session behavior even if lower priority | `player_queues/move_item*` commands where available | P2 |
| Clear queue | Queue screen | Queue header actions | Queue/list action layer | Scroll actions | Confirm clear | Cancel | Center hold on queue header | Frequent session controls | Destructive action; never one-press from normal queue navigation | `player_queues/clear` | P1 |
| Save queue as playlist | Queue screen | Queue header actions | Queue/list action layer | Scroll actions | Confirm, then text input | Cancel | Center hold on queue header | Frequent session controls | Text entry can use ordinary platform input after explicit queue action selection | `player_queues/save_as_playlist` | P2 |
| Shuffle | Now Playing | Playback options overlay | Any | Scroll options | Toggle shuffle | Back | Center hold from Now Playing | Frequent session controls | Session playback option; keep reachable without setup screens | `player_queues/shuffle` | P1 |
| Repeat | Now Playing | Playback options overlay | Any | Scroll options | Cycle repeat mode | Back | Center hold from Now Playing | Frequent session controls | Session playback option; keep reachable without setup screens | `player_queues/repeat` | P1 |
| Radio continuation / don't stop the music | Now Playing | Playback options overlay | Any | Scroll options | Toggle | Back | Center hold from Now Playing | Frequent session controls | Provider-dependent option; keep explicit and reversible | `player_queues/dont_stop_the_music` when provider supports it | P2 |
| Favorite current item | Now Playing | Playback options overlay | Any | Scroll options | Toggle/add favorite | Back | Center hold from Now Playing | Frequent session controls | Favorite for current item belongs with session controls | `music/favorites/add_item` and matching remove/status commands from `/api-docs` | P1 |
| Browse library | Library | Library root/Artists/Albums/Tracks/Playlists/Browse Folder | Navigation captures wheel input | Scroll list | Open selected item or play/open leaf via explicit action | Back/up per navigation model | Center hold = item actions | Browsing/navigation | Wheel owns list movement once browsing is on screen; deep browse can use ordinary mobile affordances | `music/*` browse/search/details commands from `/api-docs` | P1 |
| Search library | Search | Search Results | Navigation captures wheel input | Scroll results | Open selected result | Back per navigation model | OS keyboard for query input; wheel navigates results | Browsing/navigation | Query entry is not wheel-primary; result navigation is wheel-driven | `music/search` or generated search command from `/api-docs` | P2 |
| Play album / playlist / radio station | Library/Search | Collection or result list | Navigation captures wheel input | Scroll items | Open item by default | Back | Center hold = play now / add next / add later | Browsing/navigation | Playing a collection requires explicit focused play action; bottom remains current playback play/pause | Queue load/play-media command from `/api-docs` | P1 |
| Add item to queue / play next | Library/Search | Item action sheet | Navigation captures wheel input | Scroll actions | Confirm action | Back | Center hold on browsed item | Frequent session controls | Queue mutation from browse remains a strict session action with explicit confirmation | Queue enqueue/play-next command from `/api-docs` | P1 |
| Select active player/output | Player Outputs | Available Speakers / Groups | Navigation captures wheel input | Scroll outputs | Select active target | Back per navigation model | MODE hold opens screen navigation; choose Player Outputs to select output target | Configuration/session setup | Relaxed setup flow. Reachable and safe, but not optimized like playback, seek, volume, or queue. | `players/*`, active queue lookup, selected player persistence | P0 |
| Transfer playback between players | Player Outputs | Available Speakers / Groups | Navigation captures wheel input | Scroll destination | Transfer to selected | Back | Center hold on player = transfer actions | Configuration/session setup | Relaxed setup flow with explicit destination confirmation | Transfer queue/action command from `/api-docs` | P1 |
| Join / unjoin speaker group | Player Outputs | Groups / speaker action sheet | Navigation captures wheel input | Scroll action list or group members | Confirm grouping change | Back | Center hold on player/group | Configuration/session setup | Relaxed setup/configuration flow; group membership editing must not consume immediate playback UX budget | Player group/member commands from `/api-docs` | P2 |
| Power on/off player | Player Outputs | Player action sheet | Navigation captures wheel input | Scroll players/actions | Select/confirm | Back | Center hold on player = actions | Configuration/session setup | Relaxed setup flow; power action must be explicit and safe | `players/cmd/power` | P1 |
| Announcements / TTS | Notification/status only | None | None | - | - | Dismiss/back | - | Settings/admin | Not wheel-primary; playback controls remain available where safe | Home Assistant / MA announcement APIs, not wheel-primary | P3 |
| Metadata refresh / admin actions | Settings | Settings/admin rows if implemented | None | Standard settings navigation | Confirm explicit admin action | Back only | Touch/settings surface only | Settings/admin | Keep out of the primary wheel playback path | `music/sync`, metadata refresh commands | P3 |
| Authentication / server discovery | Setup / Settings | Server list if implemented | None | Scroll server list | Connect/select | Back | - | Settings/admin | Infrastructure required for playback, but not a wheel-first interaction | Auth token setup, server discovery, connection persistence | P0 infra |

## Priority definitions

| Priority | Meaning |
| --- | --- |
| P0 | Required for the app to feel like a real Music Assistant mobile player. |
| P1 | Needed for daily use once basic playback works. |
| P2 | Useful, but should not block the first integrated player. |
| P3 | Non-core wheel functionality; keep out of the primary interaction path. |
| P0 infra | Required infrastructure before real MA playback can work, but not a wheel interaction itself. |

## Adapter command buckets

The adapter should avoid leaking Music Assistant command names into UI widgets.
Expose intent-level operations instead:

| Adapter operation | Emits MA command family |
| --- | --- |
| `togglePlayPause(target)` | `player_queues/play_pause` or `players/cmd/play_pause` |
| `stop(target)` | `player_queues/stop` or `players/cmd/stop` |
| `seekTo(target, positionSeconds)` | `player_queues/seek` or `players/cmd/seek` |
| `skipBy(target, deltaSeconds)` | Relative skip command when available; local seek calculation is allowed only with a fresh position snapshot, known bounds/duration, visible fallback feedback, and a hard error when those preconditions are missing |
| `next(target)` / `previous(target)` | `player_queues/next`, `player_queues/previous` or player command equivalent |
| `setVolume(target, level)` | `players/cmd/volume_set` or group volume equivalent |
| `setMuted(target, muted)` | `players/cmd/volume_mute` or group mute equivalent |
| `listQueue(target, offset, limit)` | `player_queues/items` |
| `playQueueItem(target, item)` | Queue index/item playback command from `/api-docs` |
| `queueAction(target, item, action)` | delete/move/clear/save/shuffle/repeat commands |
| `listPlayers()` | player registry command from `/api-docs` |
| `selectTarget(playerOrQueue)` | local selected-target state + active queue lookup |
| `transferPlayback(source, destination)` | player or queue transfer command from `/api-docs` |
| `setPlayerPower(player, powered)` | `players/cmd/power` |
| `updateGroupMembership(group, members)` | player group/member commands from `/api-docs` |
| `browse(queryOrPath)` | generated `music/*` commands |

## Implementation notes

- Prefer queue commands for queue-owned playback state when a queue id is known.
- Prefer player commands for output/device actions such as volume, mute, group volume, power, and transfer.
- Keep seek preview local. Do not emit remote seek commands for every wheel tick.
- Bottom play/pause must never commit an active seek preview.
- MODE/back must cancel active seek preview without committing it.
- Every destructive or high-impact action from an action sheet needs explicit center-confirm semantics.
- Browse/search may use touch text input for query entry. The wheel owns result navigation, selection, and item actions.
- Generated `/api-docs` from the user's actual Music Assistant server is the source of truth for final command names and schemas.

## First implementation slice

1. Keep `ClickWheel` gesture-only.
2. Extend `PlaybackIntent` or equivalent with transport, seek preview/commit, volume, queue cursor, output target, and context-action intents.
3. Implement an MA adapter seam with command buckets above.
4. Wire P0 interactions against a fake adapter and tests first.
5. Wire real MA commands for play/pause, seek commit, previous/next, volume, mute, queue list, and selected player/output.
6. Add P1 overlays after the P0 path is stable.
