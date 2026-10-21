# Plan: photo-send-flow (index)

Source: ds 2, 3 (incl. 3.4), 10.2, 12. Branch: `feature/photo-send-flow`. Depends on: app-foundation, settings-storage, camera-capture. Parts BLOCKED on pending plans: outbox item creation, active count, foreground send, recovery (see README section 4 contracts).

## Sub-plans
- `PLAN-1-state-machine.md`: `MainViewModel`, `UiState`, precedence, transitions, timers, back handling, active-slot reservation.
- `PLAN-2-father-ui.md`: all father-facing screens, strings, sizes, settings icon long press, badges.
- `PLAN-3-foreground-send-feedback.md`: undo window to Sending to Sent/QueuedNotice, foreground send contract, vibration/sound.

## Confirmed decisions applied (user, this session)
- Preview reserves one active slot (ds 2.4).
- Capture-failure message shown exactly 3000 ms (`CAPTURE_FAILURE_MESSAGE_MS`), a second failure restarts the timer (ds 2.3).
- UI audit (Q51-Q58): ds 3.4 layouts/colors, father screens ignore system font scale, precedence re-evaluated on every `onResume` without a new permission dialog (ds 2.2).
- Timers continue in background while the process lives; process death -> recovery via worker (pending plan).
- Vibration 600 ms (amplitude 255) + `TONE_PROP_ACK`; vibration only when ringer mode is not NORMAL.

## Build order inside the feature
PLAN-1 (with fakes for outbox/sender) -> PLAN-2 -> PLAN-3. The real outbox/sender wiring is done after the pending plans are implemented.
