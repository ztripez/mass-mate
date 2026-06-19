# Click-Wheel Interaction Contract

This contract defines the intended Mass Mate click-wheel behavior before Music Assistant integration is added. Future implementation should preserve these interaction rules unless the contract is deliberately revised.

## Scope

The contract covers the Android-first touch click wheel, its current seek, volume, and queue modes, and the physical layout implied by the prototype: rotary ring, center button, MENU/top button, left/right transport buttons, and bottom play/pause button.

This is a product and interaction contract only. It does not add backend playback control, Music Assistant authentication, device discovery, output selection, or queue mutation beyond local UI intent.

## Fixed Behavior

### Ring Rotation

- Clockwise rotation increases the active mode value; counterclockwise rotation decreases it.
- Rotation is incremental and continuous. Small partial rotations must accumulate instead of being dropped.
- The ring always acts on the current mode only.
- Reaching a range boundary clamps at the boundary; rotation must not wrap seek position, volume, or queue cursor.
- Visual feedback must show the value being adjusted while the user rotates.

### Mode Table

| Mode | Wheel rotation | Center button | Left/right | MENU |
| --- | --- | --- | --- | --- |
| Seek | Move preview seek target | Commit active preview; if no preview is active, toggle seek precision | Move preview to previous/next chapter when chapter data exists; otherwise move preview by -30s/+30s | Cycle mode |
| Volume | Fine volume adjustment | Mute/unmute | Previous/next track | Cycle mode |
| Queue | Move queue cursor | Play selected item | Page queue backward/forward | Cycle mode |

### Seek Preview And Commit

- Seek mode is preview-first. Wheel rotation changes a preview target, not the committed playback position.
- The preview target starts from the current committed playback position when the first seek input begins.
- Long-form seeking is first-class: users can keep rotating for large time jumps while the preview target remains visible and separate from the committed position.
- Center press always commits the active preview target and applies the seek.
- Additional commit triggers, such as wheel release or a short idle timeout, are part of the adaptive seek work and must be made explicit before implementation.
- If center is pressed with no active preview, it toggles seek precision between coarse and fine seek adjustment.
- Left/right seek actions update the preview target and follow the active seek commit policy.
- MENU or direct mode selection cancels any active seek preview without committing it.
- Play/pause and track transport actions must not silently commit a seek preview.
- Preview targets clamp to the current playable range.

### Buttons

- MENU/top button cycles modes in this order: Seek, Volume, Queue, then Seek again.
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
- MENU is labeled as changing the wheel mode.
- Center uses mode-specific labels and hints: commit/toggle precision in Seek, mute/unmute in Volume, and play selected item in Queue.
- Left/right labels and hints change with the active mode.
- Bottom play/pause label reflects the current committed playback state.
- Seek preview state must be announced or otherwise exposed separately from committed playback position.

### Hit Areas

- Every tappable control must have at least a 48 by 48 logical-pixel hit area.
- The center button target should remain large enough for thumb use, with a preferred minimum diameter of 96 logical pixels.
- The rotary ring must be easy to acquire with one thumb and must not require precise contact with thin decorative ticks.
- Button hit areas must not overlap in a way that causes routine ring rotation to trigger button taps.

## Non-Goals

- No Music Assistant API integration, authentication, device discovery, or output routing is defined here.
- No real playback queue mutation, playlist editing, or library browsing is defined here.
- No release-signing, platform-runner, or non-Android interaction contract is defined here.
- No exact visual design system is defined here beyond feedback, labeling, and hit-area expectations.

## Open Tuning Decisions

- Exact seek speed per wheel turn and the coarse/fine seek increments.
- Seek commit policy: center-only versus release/idle/center, preview expiry behavior, and timeout duration.
- Exact volume step size per detent and whether acceleration is needed for long rotations.
- Queue page size and whether it maps to visible rows or a fixed item count.
- Exact haptic waveform strength and platform-specific fallbacks.
- Whether a future long-press or alternate center action opens an output selector.
- Final localized accessibility wording.
