# Click-Wheel Interaction Contract

This contract defines the intended Mass Mate click-wheel behavior before Music Assistant integration is added. Future implementation should preserve these interaction rules unless the contract is deliberately revised.

## Scope

The contract covers the Android-first touch click wheel, its current seek, volume, and queue modes, and the physical layout implied by the prototype: rotary ring, center button, MODE/top button, left/right transport buttons, and bottom play/pause button.

This is a product and interaction contract only. It does not add backend playback control, Music Assistant authentication, device discovery, output selection, or queue mutation beyond local UI intent.

## Fixed Behavior

### Ring Rotation

- Clockwise rotation increases the active mode value; counterclockwise rotation decreases it.
- Rotation is incremental and continuous. Small partial rotations must accumulate instead of being dropped.
- The ring always acts on the current mode only.
- Reaching a range boundary clamps at the boundary; rotation must not wrap seek position, volume, or queue cursor.
- Visual feedback must show the value being adjusted while the user rotates.

### Mode Table

| Mode | Wheel rotation | Center button | Left/right | MODE |
| --- | --- | --- | --- | --- |
| Seek | Move preview seek target | Commit active preview; if no preview is active, cycle mode in the current prototype | Apply the same adaptive preview movement as wheel input in the current prototype | Cycle mode |
| Volume | Fine volume adjustment | Cycle mode in the current prototype; future integration may use this for mute/unmute | Apply the same small volume movement as wheel input in the current prototype | Cycle mode |
| Queue | Move queue cursor | Cycle mode in the current prototype; future integration may use this for playing the selected item | Apply the same small queue movement as wheel input in the current prototype | Cycle mode |

### Seek Preview And Commit

- Seek mode is preview-first. Wheel rotation changes a preview target, not the committed playback position.
- The preview target starts from the current committed playback position when the first seek input begins.
- Long-form seeking is first-class: users can keep rotating for large time jumps while the preview target remains visible and separate from the committed position.
- Wheel release commits the active preview target and applies the seek.
- Center press commits the active preview target and applies the seek.
- If center is pressed with no active preview in the current prototype, it cycles to the next wheel mode.
- Left/right seek actions update the preview target with the same adaptive model as wheel input and follow the active seek commit policy in the current prototype.
- MODE or direct mode selection cancels any active seek preview without committing it.
- Play/pause and track transport actions must not silently commit a seek preview.
- Preview targets clamp to the current playable range.

### Buttons

- MODE/top button cycles modes in this order: Seek, Volume, Queue, then Seek again.
- Left/right buttons are mode-dependent transport controls, as defined in the mode table.
- Bottom button toggles play/pause for the currently committed playback item.
- Center button is mode-dependent selection or confirmation, as defined in the mode table.
- Invalid button actions are no-ops for playback state, but must provide user feedback instead of failing silently.

### Mode Switching

- Mode switching changes only the wheel interpretation and mode affordances.
- Mode switching must not change playback position, volume, queue cursor, or selected queue item except for canceling an uncommitted seek preview.
- Mode-specific sub-step accumulators do not carry across modes.

### Haptics

- Ring rotation emits regular tick haptics at wheel detents.
- Boundary haptics fire once when a rotation first reaches a seek, volume, or queue boundary.
- Boundary haptics do not repeat while the user keeps pushing into the same boundary; they become eligible again after the user moves away from that boundary.
- Committing a seek preview emits a distinct confirmation haptic.
- Invalid actions emit an error haptic and leave playback state unchanged.
- Haptics support the interaction but do not define success. The visible state and accessibility feedback must still communicate the result.

### Accessibility

- The wheel exposes a mode-specific label, such as `Seek click wheel`, `Volume click wheel`, or `Queue click wheel`.
- The wheel hint explains what rotation changes in the active mode.
- MODE is labeled as changing the wheel mode.
- Center uses mode-specific labels and hints: commit active preview or cycle mode with no preview in Seek, and cycle mode in the current Volume and Queue prototype modes.
- Left/right labels and hints change with the active mode.
- Bottom play/pause label reflects the current committed playback state.
- Seek preview state must be announced or otherwise exposed separately from committed playback position.

### Hit Areas

- Every tappable control must have at least a 48 by 48 logical-pixel hit area.
- The center button target should remain large enough for thumb use, with a preferred minimum diameter of 96 logical pixels.
- The rotary ring must be easy to acquire with one thumb and must not require precise contact with thin decorative ticks.
- Button hit areas must not overlap in a way that causes routine ring rotation to trigger button taps.

### Mobile Layout

- On portrait phone layouts, the wheel should size from the available viewport instead of using a fixed pixel square.
- The wheel should remain the dominant lower control, with its center in the lower thumb-reachable half of common phone viewports.
- The now-playing card must keep a visible gap above the wheel and must not crowd or overlap the wheel on compact phones.
- Very tall phones may cap wheel diameter to preserve comfortable reach instead of scaling the wheel indefinitely.
- Landscape and wide layouts may place the wheel in a side control column as long as the wheel remains large and easy to acquire.
- Gesture-navigation safe areas must leave clearance below the wheel and must not push the wheel into the card.
- During active seek preview, the card should reduce secondary metadata density and emphasize the preview target, committed position, and commit/cancel actions.
- Viewports that cannot fit the minimum supported wheel and safe hit areas must surface an explicit unsupported-viewport message instead of silently overlapping controls.

## Non-Goals

- No Music Assistant API integration, authentication, device discovery, or output routing is defined here.
- No real playback queue mutation, playlist editing, or library browsing is defined here.
- No release-signing, platform-runner, or non-Android interaction contract is defined here.
- No exact visual design system is defined here beyond feedback, labeling, and hit-area expectations.

## Open Tuning Decisions

- Exact seek speed band thresholds and per-detent increments after Android device testing.
- Seek idle commit policy, preview expiry behavior, and timeout duration.
- Whether adaptive seek speed bands need an additional explicit precision toggle, and which control invokes it.
- Exact volume step size per detent and whether acceleration is needed for long rotations.
- Queue page size and whether it maps to visible rows or a fixed item count.
- Exact haptic waveform strength and platform-specific fallbacks.
- Whether a future long-press or alternate center action opens an output selector.
- Final localized accessibility wording.
