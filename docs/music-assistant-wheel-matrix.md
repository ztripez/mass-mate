# Music Assistant Wheel Mapping Matrix

Last researched: 2026-06-20

This document maps Music Assistant functionality to the Mass Mate click-wheel control model.
It extends `docs/click-wheel-contract.md`, which remains the lower-level interaction contract for the current prototype's ring/button behavior, haptics, accessibility, hit areas, and preview semantics.

The goal is not to expose every Music Assistant feature through the wheel. The goal is to make the wheel the primary playback surface for mobile use, especially for long-form music, podcasts, and audiobooks where ordinary mobile sliders are painful.

## Prototype versus Music Assistant target

`docs/click-wheel-contract.md` is the source of truth for current prototype behavior. The matrix below describes the intended Music Assistant-integrated target when Music Assistant playback, chapter metadata, relative skip commands, precision options, and overlays exist.

Current prototype seek behavior intentionally differs from the target rows in these places:

- Center in Seek mode commits an active preview; with no active preview, it cycles to the next wheel mode. The Music Assistant target may later use the no-preview center action for a precision toggle or seek options.
- Left/right in Seek mode apply the same adaptive preview movement as wheel rotation. The Music Assistant target may later prefer chapter previous/next when chapter metadata exists, or a fixed relative skip such as +/-30 seconds when it does not.
- Center in current Volume and Queue prototype modes cycles wheel mode. The Music Assistant target rows below reserve those center actions for mute/unmute and play-selected behavior after real playback integration exists.
- Left/right in current Volume and Queue prototype modes apply the same small movement as wheel rotation. The Music Assistant target rows below reserve those buttons for previous/next transport in Volume mode and page movement in Queue mode.

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
| Now Playing | Default playback surface | Global transport, mode status, current metadata, preview feedback |
| Seek mode | Scrub current media | Duration-aware local preview, committed seek only on explicit commit |
| Volume mode | Control active player or group volume | Continuous level adjustment, mute toggle |
| Queue mode | Inspect and act on current queue | Cursor movement, play selected item, queue item actions |
| Browse surface | Navigate library/search results | List navigation and item action sheet |
| Output surface | Choose active player/output/group | List navigation and player action sheet |
| Playback options overlay | Shuffle, repeat, radio continuation, favorite | Small option list with explicit toggles |

## Music Assistant target button mapping

| Control | Global rule | Seek mode | Volume mode | Queue mode | Browse/output overlays |
| --- | --- | --- | --- | --- | --- |
| Ring | Adjust the focused scalar or list | Move seek preview target | Adjust volume | Move queue cursor | Move list cursor |
| Center | Confirm/select, never surprise-mutate | Commit active preview; target behavior may use no-preview center for precision/options | Toggle mute | Play selected queue item | Open/select focused item |
| MENU/top | Back first, mode-cycle second | Cancel preview and cycle mode when on Now Playing | Cycle mode when on Now Playing | Cycle mode when on Now Playing | Back/up |
| Left/right | Transport unless local mode has stronger meaning | Target behavior: chapter previous/next, else +/-30s preview | Previous/next track | Page queue | Page list / alpha jump |
| Bottom | Always play/pause committed playback | Play/pause without committing preview | Play/pause | Play/pause current playback | Play/pause current playback |
| Center hold | Open context actions | Seek options | Volume/output options | Queue item actions | Item/player actions |
| MENU hold | Open global surface switcher | Now Playing / Browse / Queue / Output / Settings | Same | Same | Same |
| Left/right hold | Continuous transport or page repeat | Rewind/fast-forward preview | Previous/next repeat only if deliberate | Page repeat | Page repeat |
| Bottom hold | Stop | Stop | Stop | Stop | Stop |

## Functionality matrix

| Music Assistant function | Surface / mode | Ring rotation | Center | MENU / top | Left / right | Bottom | Long press / secondary | MA integration target | Priority |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Play / pause | Global | No-op | Mode-specific | Back/cycle | Mode-specific | Toggle play/pause | Hold bottom = stop | `player_queues/play_pause` or `players/cmd/play_pause` | P0 |
| Stop | Global | No-op | - | - | - | - | Hold bottom | `player_queues/stop` or `players/cmd/stop` | P1 |
| Previous / next track | Global transport | No-op | - | - | Previous / next | Play/pause | Hold left/right = rewind/fast-forward or repeated skip | `player_queues/previous`, `player_queues/next`, or `players/cmd/*` equivalents | P0 |
| Audiobook / podcast chapter movement | Seek mode | Move seek preview | Commit preview | Cancel preview + cycle/back | Chapter prev/next if chapter data exists, else +/-30s preview | Play/pause, no preview commit | Hold left/right = continuous seek preview | `player_queues/seek`, `players/cmd/seek`, or relative skip support when available | P0 |
| Scrub current track | Seek mode | Move preview target | Commit preview | Cancel preview + cycle/back | +/-30s or chapter movement | Play/pause, no preview commit | Center hold = precision/options | `player_queues/seek(position)` or `players/cmd/seek(position)` | P0 |
| Fine/coarse seek precision | Seek mode | Uses active precision | Target behavior: toggle precision when no preview is active | Cycle/back | Uses active precision | Play/pause | Center hold = seek options | Local resolver only until commit | P0 |
| Volume | Volume mode | Adjust volume | Toggle mute | Cycle/back | Previous/next track | Play/pause | Center hold = volume/output options | `players/cmd/volume_set`, `volume_up`, `volume_down` | P0 |
| Mute | Volume mode | Adjust volume | Toggle mute | Cycle/back | Previous/next track | Play/pause | - | `players/cmd/volume_mute` or group mute equivalent | P0 |
| Group volume | Volume mode with group active | Adjust group volume | Toggle group mute | Cycle/back | Previous/next track | Play/pause | Center hold = choose group vs member volume | `players/cmd/group_volume`, `group_volume_up/down`, `group_volume_mute` | P1 |
| Power on/off player | Output surface | Scroll players/actions | Select/confirm | Back | Page players | Play/pause active queue | Center hold on player = actions | `players/cmd/power` | P1 |
| Active queue navigation | Queue mode | Move queue cursor | Play selected item | Cycle/back | Page queue | Play/pause current item | Center hold = queue item actions | `player_queues/items`, `player_queues/play_index` or equivalent | P0 |
| Remove queue item | Queue item actions | Scroll action list | Confirm remove | Back | - | Play/pause | Center hold on queue item | `player_queues/delete_item` | P1 |
| Move queue item | Queue item actions | Move target position | Drop/confirm | Cancel | Page target | Play/pause | Center hold = queue item actions | `player_queues/move_item*` commands where available | P2 |
| Clear queue | Queue header actions | Scroll actions | Confirm clear | Cancel | - | Play/pause | Center hold on queue header | `player_queues/clear` | P1 |
| Save queue as playlist | Queue header actions | Scroll actions | Confirm, then text input | Cancel | - | Play/pause | Center hold on queue header | `player_queues/save_as_playlist` | P2 |
| Shuffle | Playback options overlay | Scroll options | Toggle shuffle | Back | - | Play/pause | Center hold from Now Playing | `player_queues/shuffle` | P1 |
| Repeat | Playback options overlay | Scroll options | Cycle repeat mode | Back | - | Play/pause | Center hold from Now Playing | `player_queues/repeat` | P1 |
| Radio continuation / don't stop the music | Playback options overlay | Scroll options | Toggle | Back | - | Play/pause | Center hold from Now Playing | `player_queues/dont_stop_the_music` when provider supports it | P2 |
| Favorite current item | Playback options overlay | Scroll options | Toggle/add favorite | Back | - | Play/pause | Center hold from Now Playing | `music/favorites/add_item` and matching remove/status commands from `/api-docs` | P1 |
| Browse library | Browse surface | Scroll list | Open selected item or play leaf | Back/up | Page or alpha jump | Play/pause current item | Center hold = item actions | `music/*` browse/search/details commands from `/api-docs` | P1 |
| Search library | Search surface | Scroll results | Open selected result | Back | Page results | Play/pause current item | OS keyboard for query input; wheel navigates results | `music/search` or generated search command from `/api-docs` | P2 |
| Play album / playlist / radio station | Browse surface | Scroll items | Open item by default | Back | Page | Play selected collection only when explicitly focused for play | Center hold = play now / add next / add later | Queue load/play-media command from `/api-docs` | P1 |
| Add item to queue / play next | Item action sheet | Scroll actions | Confirm action | Back | - | Play/pause | Center hold on browsed item | Queue enqueue/play-next command from `/api-docs` | P1 |
| Select active player/output | Output surface | Scroll players | Select active target | Back/cycle | Page players | Play/pause active target | MENU hold opens output selector globally | `players/*`, active queue lookup, selected player persistence | P0 |
| Transfer playback between players | Output surface | Scroll destination | Transfer to selected | Back | Page players | Play/pause current target | Center hold on player = transfer actions | Transfer queue/action command from `/api-docs` | P1 |
| Join / unjoin group | Output actions | Scroll action list | Confirm | Back | - | Play/pause | Center hold on player/group | Player group/member commands from `/api-docs` | P2 |
| Announcements / TTS | Notification/status only | - | - | Dismiss/back | - | Play/pause active playback | - | Home Assistant / MA announcement APIs, not wheel-primary | P3 |
| Metadata refresh / admin actions | Not wheel-primary | - | - | Back only | - | Play/pause | Touch/settings surface only | `music/sync`, metadata refresh commands | P3 |
| Authentication / server discovery | Setup surface | Scroll server list | Connect/select | Back | - | - | - | Auth token setup, server discovery, connection persistence | P0 infra |

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
| `skipBy(target, deltaSeconds)` | Relative skip command when available, else local seek calculation + seek commit |
| `next(target)` / `previous(target)` | `player_queues/next`, `player_queues/previous` or player command equivalent |
| `setVolume(target, level)` | `players/cmd/volume_set` or group volume equivalent |
| `setMuted(target, muted)` | `players/cmd/volume_mute` or group mute equivalent |
| `listQueue(target, offset, limit)` | `player_queues/items` |
| `playQueueItem(target, item)` | Queue index/item playback command from `/api-docs` |
| `queueAction(target, item, action)` | delete/move/clear/save/shuffle/repeat commands |
| `listPlayers()` | player registry command from `/api-docs` |
| `selectTarget(playerOrQueue)` | local selected-target state + active queue lookup |
| `browse(queryOrPath)` | generated `music/*` commands |

## Implementation notes

- Prefer queue commands for queue-owned playback state when a queue id is known.
- Prefer player commands for output/device actions such as volume, mute, group volume, power, and transfer.
- Keep seek preview local. Do not emit remote seek commands for every wheel tick.
- Bottom play/pause must never commit an active seek preview.
- MENU/back must cancel active seek preview without committing it.
- Every destructive or high-impact action from a context menu needs explicit center-confirm semantics.
- Browse/search may use touch text input for query entry. The wheel owns result navigation, selection, and item actions.
- Generated `/api-docs` from the user's actual Music Assistant server is the source of truth for final command names and schemas.

## First implementation slice

1. Keep `ClickWheel` gesture-only.
2. Extend `PlaybackIntent` or equivalent with transport, seek preview/commit, volume, queue cursor, output target, and context-action intents.
3. Implement an MA adapter seam with command buckets above.
4. Wire P0 interactions against a fake adapter and tests first.
5. Wire real MA commands for play/pause, seek commit, previous/next, volume, mute, queue list, and selected player/output.
6. Add P1 overlays after the P0 path is stable.
