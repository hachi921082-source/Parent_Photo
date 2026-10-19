# Plan: acceptance-verification

Source: ds 12 (checklist), 11.4. Branch: `feature/acceptance-verification`. Runs after all features including the pending ones are implemented. No new code except fixes found; verification only.

## Index
1. Scope
2. Procedure
3. Environments
4. Output

## 1. Scope
Walk every unchecked item of ds 12 against an R8 release build. Items depending on pending features (outbox, worker, send log, mail format) are verified only when those features exist; until then they stay open.

## 2. Procedure
1. Build release APK (name includes date-time, `FamilyPhoto_YYYYMMDD-HHMMSS.apk`).
2. Execute checklist groups in order: launch/Loading, Camera and permission, Preview, undo/Sending/Sent/QueuedNotice, badges and OutboxFull (including Preview slot reservation), setup, sizes, settings, storage, send format, outbox, notification, worker safety, release manifest, UI audit (step 4).
3. Record pass/fail per item in the review report to `reviewer-lite`; fix failures through the owning feature's plan.
4. UI audit checklist (ds 12 "UI audit (Q51-Q58)"): with the system font scale at maximum, father-facing screens render unchanged (28/36/20 sp) while the hidden settings screen scales; layouts and colors per ds 3.4 for Camera, OutboxFull, Capturing, Preview, CancelWindow, Sending, Sent, QueuedNotice, Loading, SetupRequired, PermissionDenied; 保存 is enabled when the form is dirty, validates on press, scrolls to and focuses the first error; Snackbars appear only for "保存しました" and "順番待ちです。しばらくお待ちください" (Short, no action); the notification has no title, is BigText, alerts only once, is not auto-cancelled and shows the latest event text; launcher label and APK file name say FamilyPhoto; no animation between states.
5. Specific checks: Japanese text of every father-facing string; timers while backgrounded; vibration only in silent mode; predictive-back on Android 13+; time shown in Asia/Tokyo with a non-Tokyo device zone.

## 3. Environments
API 26-level emulator or device, Android 14/15 device, real Gmail app password via local git-ignored file.

## 4. Output
Updated checklist state reported to the user (docs/detailed-spec.md is edited only via designer-lite).
