# Wheel UX Strictness

This document classifies Mass Mate features by how strongly they must preserve the one-handed, click-wheel-first experience. It complements `docs/click-wheel-contract.md` and `docs/music-assistant-wheel-matrix.md`; it does not replace either contract.

The purpose is to stop future work from optimizing every Music Assistant surface equally. Playback-critical and frequent-session controls should receive the strictest wheel treatment. Speaker output, grouping, provider setup, and administration must remain reachable, but they should not consume the same wheel-native design budget until playback, queue, seek, volume, and browsing are solid.

This document is product documentation only. It does not implement Music Assistant output selection, grouping, provider configuration, discovery, authentication, or real playback behavior.

## Strictness Classes

| Class | Examples | UX strictness | Expectations |
| --- | --- | --- | --- |
| Immediate playback | Play/pause, seek and scrub preview, volume, previous/next track, chapter or relative skip | Very strict wheel-first | Must work from the primary playback posture with one thumb, visible state feedback, accessible labels, and no required screen hop. Wheel ticks and buttons should feel predictable during rapid repeated use. Seek remains preview-first and emits remote commands only on explicit commit. |
| Frequent session controls | Queue navigation, play selected queue item, repeat, shuffle, favorite current item, queue item actions | Strict wheel-first | Should be reachable during an active listening session through wheel-native modes, overlays, or context actions. Flows may require explicit confirmation for mutation, but the wheel should own movement, focus, and confirmation. |
| Browsing/navigation | Library browsing, search result navigation, album/playlist/radio station browsing, item action sheets | Medium | Should support wheel-driven list movement and selection where lists are already on screen. Touch text entry and ordinary mobile list affordances are acceptable for query input and deep browsing. Do not optimize browsing ahead of immediate playback and frequent session controls. |
| Configuration/session setup | Speaker output selection, player transfer, grouping and ungrouping, provider selection, player power actions | Relaxed setup/configuration | Must be discoverable and reachable, but does not need one-handed rapid-change optimization before playback, queue, seek, volume, and browsing are reliable. Standard list and confirmation flows are acceptable when they remain clear and safe. |
| Settings/admin | Diagnostics, server configuration, authentication, discovery, metadata refresh, provider administration | Normal app UI | Can use conventional settings screens, forms, and touch controls. These surfaces should be explicit, safe, and understandable, but they are not wheel-first playback flows. |

## Design Rules

- Treat play/pause, seek, scrub, volume, previous, next, and chapter or relative skip as the most strict wheel-first paths.
- Treat queue navigation, repeat, shuffle, favorite, and focused queue actions as strict session controls, not as secondary settings.
- Treat library and search browsing as medium strictness: the wheel should navigate visible results, while text entry and deeper provider browsing can use normal mobile controls.
- Treat speaker output and grouping as relaxed setup/configuration flows. They must be reachable, but they should not be optimized for one-handed rapid changes before playback, queue, seek, volume, and browsing are solid.
- Treat diagnostics, server configuration, provider administration, discovery, and authentication as normal app UI unless a future contract explicitly promotes a specific action into a wheel-first flow.
- Use Mode terminology for wheel behavior switching. The MODE/top control may cycle or back out according to the current contract; this doc does not define a separate navigation model.

## Matrix Usage

`docs/music-assistant-wheel-matrix.md` contains the Music Assistant feature mapping and a compact strictness classification table. When adding or revising rows there, classify the feature by user-session urgency rather than by backend priority alone.

A feature can be high integration priority without being a strict wheel-first flow. For example, selected-player persistence may be necessary infrastructure for real playback, while speaker grouping remains a relaxed setup/configuration flow from a wheel UX perspective.

## Non-Goals

- No Music Assistant output or grouping behavior is implemented by this document.
- No screen/list navigation model is defined here.
- No new click-wheel gestures, buttons, haptics, or accessibility strings are specified here.
- No backend command names or schemas are made authoritative here; generated Music Assistant `/api-docs` remains the final source for adapter implementation details.
