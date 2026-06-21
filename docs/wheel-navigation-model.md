# Wheel Navigation Model

This document is the canonical navigation model for how the Mass Mate click wheel moves between app screens and inside scrollable lists. It complements `docs/click-wheel-contract.md`, which remains the lower-level contract for seek, volume, queue, haptics, accessibility, and hit-area behavior.

The model is intentionally separate from Music Assistant API wiring. It defines navigation state and control routing so future UI and resolver tests can be written without guessing.

## Scope And Status

- Current prototype behavior: the app implements a Now Playing shell with `WheelMode.seek`, `WheelMode.volume`, and `WheelMode.queue`. The MODE button cycles those modes. Queue movement is local demo state, not the list navigation layer defined here.
- Future target behavior: the app has a screen navigation layer and a list navigation layer. These layers compose with `WheelMode`; they do not add new wheel modes and they do not rename the MODE behavior switch.
- This document does not implement Music Assistant integration, authentication, device discovery, real playback control, or UI behavior.

## Terms

| Term | Meaning |
| --- | --- |
| Screen context | The root navigation layer that chooses among primary app screens. Screen changes are deliberate context changes. |
| List context | A focused scrollable collection inside a screen. List movement is frequent, reversible, and local to that screen. |
| Screen candidate | The primary screen currently highlighted while screen-level navigation has focus. It is not active until center/select confirms it. |
| List frame | One level in a nested list stack, including the list context type, optional parent item id/path, focused row, and scroll offset. |
| `WheelMode` | The playback behavior mode, currently Seek, Volume, or Queue. `WheelMode` determines playback/control behavior only when navigation is not capturing the wheel. |

## Primary App Screens

The target screen order is fixed for screen-level wheel navigation:

1. Now Playing
2. Queue
3. Library
4. Search
5. Player Outputs
6. Settings

| Screen | Purpose | Default list context | Current prototype status | Future target entry behavior |
| --- | --- | --- | --- | --- |
| Now Playing | Playback card, transport, active `WheelMode`, and preview feedback. | None. | Implemented as the only primary screen. | Center/select activates the screen. With no list focus, wheel rotation routes to active `WheelMode`. |
| Queue | Inspect and act on the active playback queue. | Queue Items. | Current `WheelMode.queue` moves a local queue cursor only. | Center/select enters Queue Items at the saved cursor. |
| Library | Browse media collections. | Library root, then Artists, Albums, Tracks, Playlists, or Browse Folder. | Not implemented. | Center/select enters the saved library list stack or the Library root if no stack exists. |
| Search | Enter a query with platform text input, then navigate results with the wheel. | Search Results once a query has results. | Not implemented. | Center/select focuses query entry when empty, or Search Results when results exist. |
| Player Outputs | Choose active speakers, players, or groups. | Available Speakers. | Not implemented. | Center/select enters Available Speakers or the saved output list stack. |
| Settings | Configure app-level preferences. | None in the initial wheel model. | Not implemented. | Center/select activates the screen. If Settings later adds rows, those rows must use the list layer rules. |

## Initial List Contexts

These are the first list contexts the implementation should support. New contexts must follow the same stack and control rules.

| List context | Owning screen | Rows | Center/select target | Long-center target | Back at root |
| --- | --- | --- | --- | --- | --- |
| Queue Items | Queue | Active queue entries. | Play or select the focused queue item through an explicit queue item intent. | Queue item actions such as remove or move. | Return to Queue screen context. |
| Artists | Library | Artists in the library. | Open the selected artist's child list. | Artist actions. | Return to Library screen context or previous library parent. |
| Albums | Library | Albums, optionally scoped by artist or folder. | Open the album Tracks list. | Album actions. | Return to Library screen context or previous library parent. |
| Tracks | Library | Tracks, optionally scoped by artist, album, playlist, folder, or search result. | Open a track action sheet or explicit play/select action. | Track actions. | Return to Library screen context or previous library parent. |
| Playlists | Library | Saved playlists. | Open the playlist Tracks list. | Playlist actions. | Return to Library screen context or previous library parent. |
| Browse Folder | Library | Folder children and media items. | Open child folders; open an action sheet for leaf media. | Folder or item actions. | Return to Library screen context or previous folder parent. |
| Search Results | Search | Mixed search result rows. | Open child lists for containers; open an action sheet for leaf media. | Result item actions. | Return to Search screen context while preserving query text and results. |
| Available Speakers | Player Outputs | Individual players/speakers. | Select the focused speaker as the active output target. | Speaker actions such as power or transfer. | Return to Player Outputs screen context. |
| Groups | Player Outputs | Player groups. | Select the focused group as the active output target. | Group actions such as join, unjoin, or member management. | Return to Player Outputs screen context or previous output parent. |

## Navigation State

Navigation state should live above playback intent resolution, not inside Music Assistant adapters and not inside `ClickWheel`. `ClickWheel` should continue to emit raw wheel and button events.

The navigation owner should track:

| State | Required behavior |
| --- | --- |
| `activeScreen` | Exactly one primary screen is active. Startup target is Now Playing. |
| `screenCandidate` | Exists only while screen-level navigation has focus. Rotation changes this candidate without changing `activeScreen`. |
| `screenHistory` | Records deliberate screen changes so Back can return to the previous active screen. Duplicate adjacent entries are not stored. |
| `navigationLayer` | `screen`, `list`, or `none`. `screen` routes wheel movement to screen candidates. `list` routes wheel movement to the active list frame. `none` means no navigation context has focus and wheel movement routes to `WheelMode`. |
| `listStacksByScreen` | Each screen owns its own list stack and cursor state. Leaving a screen preserves that screen's stack until data invalidation or an explicit reset. |
| `activeListFrame` | The top frame of the active screen's stack when `navigationLayer == list`. It stores list context, parent key/path, focused row, and scroll offset. |
| `wheelMode` | Remains the existing `WheelMode` value. It is independent from `navigationLayer` and must not be used to represent screen or list focus. |

State composition rules:

- A list frame captures rotate, center/select, Back, MODE, long-center, and long-MODE before those events reach playback `WheelMode` behavior.
- Now Playing without an active list uses `navigationLayer == none`; rotation continues to resolve through the active `WheelMode` as in the current prototype.
- Screen-level navigation captures rotation for screen candidates. It must not adjust seek, volume, or queue cursor while the screen candidate is changing.
- Back and MODE must cancel an active seek preview without committing it before changing navigation or `WheelMode` state.
- The navigation layer must emit explicit intents for high-impact actions. It must not call Music Assistant commands directly.

## Screen-Level Control Mapping

Screen-level navigation is active when `navigationLayer == screen`. It changes the active app context only after explicit center/select confirmation.

| Control | Screen-level behavior |
| --- | --- |
| Rotate | Move `screenCandidate` through Now Playing, Queue, Library, Search, Player Outputs, and Settings. Movement clamps at the first and last screen; it does not wrap. |
| Center/select | Activate `screenCandidate`. If the screen changed, append the previous `activeScreen` to `screenHistory`. Enter the screen's saved default list when it has one. If the activated screen is Now Playing, clear navigation focus so rotation routes to `WheelMode`. |
| Back | If `screenCandidate` differs from `activeScreen`, cancel the candidate and refocus `activeScreen`. Otherwise return to the previous screen from `screenHistory`. If no previous screen exists, provide invalid-action feedback and leave state unchanged. |
| MODE | Change the active `WheelMode` only. It must not change `activeScreen`, `screenCandidate`, or any list cursor. Current prototype behavior is MODE cycling Seek, Volume, Queue. |
| Long-center | Open screen-level context actions for the active screen or highlighted screen candidate. The opened actions are navigated as a list/action layer and require center/select confirmation. |
| Long-MODE | Open the `WheelMode` picker at the screen layer. Choosing a mode changes `wheelMode` only and preserves screen and list navigation state. |

## List-Level Control Mapping

List-level navigation is active when `navigationLayer == list`. It moves within the active list stack and must not change primary screens unless the user exits the list layer.

| Control | List-level behavior |
| --- | --- |
| Rotate | Move the focused row and scroll position in the active list frame. Movement clamps at list boundaries; it does not wrap. Empty lists keep no focused row and emit invalid-action feedback on selection. |
| Center/select | Open or select the focused row. Container rows push a child list frame. Leaf rows emit an explicit select/open intent or open an action sheet, depending on the owning context table above. |
| Back | Close an open action sheet first. Otherwise pop one child list frame. If already at the root frame, exit to screen-level navigation for the owning screen without changing `activeScreen`. |
| MODE | Exit list navigation to the owning screen's screen context and preserve the full list stack and cursor state. A later center/select can re-enter the preserved list position. |
| Long-center | Open item actions for the focused row. Destructive or high-impact actions require a separate center/select confirmation inside the action sheet. |
| Long-MODE | Return directly to screen-level navigation for the owning screen from any nested list or action sheet. The list stack is preserved; no parent frames are popped. |

Global transport controls are outside this navigation model: bottom play/pause remains play/pause for committed playback, and left/right retain the mode-specific transport/page semantics defined by the click-wheel contract and Music Assistant matrix. They must not silently commit an active seek preview.

## Nested Back-Stack Behavior

Each primary screen owns an independent stack of list frames. A frame is pushed only when center/select opens a child collection or action sheet. Back pops exactly one frame at a time, except that Back at a root frame exits to screen-level navigation.

General stack rules:

- Pushing a child frame stores the parent focused row and scroll offset.
- Popping a child frame restores the parent frame exactly where it was when the child opened.
- Exiting list navigation with MODE or long-MODE preserves the entire stack.
- Switching screens preserves each screen's stack. If fresh data later removes the saved focused row, restore focus to the nearest valid row; if the list becomes empty, restore no focused row and show empty-state feedback.
- Back, MODE, and long-MODE never trigger playback, queue mutation, or output selection by themselves.

Library examples:

- Library screen -> Artists -> selected artist Albums -> selected album Tracks.
- Back from Tracks returns to the selected artist Albums list with the album row still focused.
- Back again returns to Artists with the artist row still focused.
- Back from root Artists exits to Library screen context.
- MODE or long-MODE from Tracks exits directly to Library screen context and preserves the full Artists -> Albums -> Tracks stack.

Search examples:

- Search screen with query text -> Search Results -> selected album Tracks.
- Back from Tracks returns to Search Results with the original query, result set, focused result, and scroll offset preserved.
- Back from root Search Results exits to Search screen context without clearing query text.
- New query submission replaces the Search Results root frame and clears child result frames for the previous query.

Player Outputs examples:

- Player Outputs screen -> Available Speakers.
- Center/select on a speaker selects that speaker as the active output target through an explicit output selection intent.
- Long-center on a speaker opens speaker actions; Back closes those actions and returns to Available Speakers.
- Player Outputs screen -> Groups -> selected group actions or members. Back returns to Groups with the selected group focused, then to Player Outputs screen context.

## Testable Invariants

Future implementation and widget/resolver tests should treat these as required behavior:

- Rotating in screen context changes only `screenCandidate`.
- Rotating in list context changes only the active list frame cursor/scroll state.
- Rotating on Now Playing without navigation focus continues to use the active `WheelMode`.
- Center/select is the only control that activates a screen candidate or opens/selects a focused list row.
- Back from a child list restores the parent list's previous cursor and scroll offset.
- Back from a root list exits to screen context and does not change the active screen.
- MODE and long-MODE preserve list stacks and never commit seek previews.
- `WheelMode` remains Seek, Volume, or Queue until deliberately extended; screen and list focus are navigation layers, not wheel modes.

## Non-Goals

- No Music Assistant command names or schemas are defined here.
- No authentication, discovery, selected-player persistence, or websocket state model is defined here.
- No visual layout, animation, or accessibility copy beyond navigation semantics is defined here.
- No broad expansion of `docs/music-assistant-wheel-matrix.md` is included here; later matrix work should reference this navigation model instead of duplicating it.
