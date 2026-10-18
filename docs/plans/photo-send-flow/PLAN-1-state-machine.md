# photo-send-flow PLAN-1: state machine

Source: ds 2.1-2.4, 4.2. Package `ui/`.

## Index
1. UiState
2. Inputs
3. Launch and precedence
4. Transitions
5. Timers and background
6. Back handling
7. Active slot reservation
8. Tests

## 1. UiState
Sealed interface: `Loading`, `PermissionDenied`, `SetupRequired`, `Camera`, `OutboxFull`, `Capturing`, `Preview(capturedAtMillis)`, `CancelWindow(itemId, secondsLeft)`, `Sending(itemId)`, `Sent`, `QueuedNotice`. Settings is a separate navigation route (back returns to the previous main state and re-evaluates precedence). Extra non-state fields: `captureFailureMessageVisible: Boolean`, `previewExists`, badge counts, recipients list, button size.

## 2. Inputs (single `MainViewModel`)
- `SettingsRepository.settings` flow (first emission ends settings loading) and `readPassword()` presence.
- Camera permission state (from camera-capture helper).
- `OutboxRepository.activeCount`, `attentionCount` flows (contract; fake until pending plans).
- Recovery-finished signal from outbox startup recovery (contract; fake returns immediately).

## 3. Launch and precedence (ds 2.2)
Cold start: state Loading; delete `cache/preview.jpg` and `cache/capture_raw.jpg`; run outbox recovery; channel already created in the Application. Then evaluate in order:
1. Loading until settings first emission and recovery finish.
2. SetupRequired if setup invalid (camera not started).
3. Camera permission not granted -> request once per cold start the first time the camera would be shown; denied -> PermissionDenied. Resume after returning from system settings re-checks without a new dialog.
4. `activeCount >= 5` -> OutboxFull else Camera.
If a Preview photo exists when entering Settings, returning restores Preview with the same photo (even if setup became invalid in between: Preview is shown once setup is valid). Preview is never restored after process death.

## 4. Transitions (ds 2.3)
- Camera + shutter -> Capturing: record `capturedAtMillis`, call camera-capture. Success -> Preview. Failure -> back to Camera/OutboxFull (per active count), delete partials, set `captureFailureMessageVisible` for 3000 ms (`CAPTURE_FAILURE_MESSAGE_MS`), Logcat tag `ParentPhoto/Camera`.
- Camera <-> OutboxFull automatic with `activeCount`.
- Preview + やり直す -> Camera/OutboxFull, delete preview file.
- Preview + recipient tap -> CancelWindow (item creation, PLAN-3). Rejected defensively with OutboxFull guidance if `activeCount >= 5`.
- CancelWindow + 取り消す -> Preview (item row and outbox file deleted; cached preview untouched).
- CancelWindow 3 s -> Sending; Sending success -> Sent; failure/timeout/offline -> QueuedNotice; Sent and QueuedNotice 2 s -> Camera/OutboxFull and delete preview file (PLAN-3 details).
- 3 s long press on settings icon -> Settings route, from Camera, OutboxFull, Preview, SetupRequired, PermissionDenied only.
- Zero recipients: Preview shows "送り先が登録されていません" in place of recipient buttons.

## 5. Timers and background (confirmed)
Undo countdown, foreground send (60 s cap), Sent and QueuedNotice 2 s timers and the 3 s failure message run in `viewModelScope` using `SystemClock.elapsedRealtime`. They continue in background while the process lives. On return the screen shows the current state (for example Camera if Sent already elapsed); vibration/sound are not replayed. On process death, outbox recovery (pending plan) sends the photo through the worker.

## 6. Back handling
`BackHandler(enabled = true) {}` in Loading, Capturing, Preview, CancelWindow, Sending, Sent, QueuedNotice. Camera, OutboxFull, SetupRequired, PermissionDenied keep default behavior. Manifest `enableOnBackInvokedCallback` set consistently (app-foundation). Verify on an Android 13+ device with predictive back.

## 7. Active slot reservation (confirmed)
`previewExists` is exposed to the outbox layer. Promotion of 要確認 items (今すぐ再送, settings save; pending outbox plans) is allowed only while `active + (previewExists ? 1 : 0) < 5`; otherwise the item waits (`resendPending`). This plan owns publishing `previewExists`; the promotion logic is in outbox-queue.

## 8. Tests
JVM tests with fake dependencies and a test dispatcher/clock: precedence matrix; each transition row; failure message auto-hide; Preview kept across Settings; timers continue when "backgrounded" (clock based); defensive reject at active 5.
