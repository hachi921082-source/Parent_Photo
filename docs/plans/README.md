# Implementation Plans

Each feature's implementation plan lives in its own directory: `docs/plans/<feature-name>/PLAN.md`. Large features have `PLAN.md` as an index plus `PLAN-N-<topic>.md` sub-plans. Branch name: `feature/<feature>` (merge with `--no-ff`; push only with user confirmation).

Source of truth: `docs/spec.md`, `docs/detailed-spec.md` (ds), `docs/QA.md`. Section references below are ds sections.

## Index
1. Features and order
2. Pending features
3. Confirmed planning decisions
4. Cross-plan interface contracts

## 1. Features and order

| # | Feature | Directory | Scope | Branch |
|---|---|---|---|---|
| 1 | smtp-send-spike (FIRST) | `smtp-send-spike/` | Throwaway JavaMail multipart+JPEG send, R8 release, API 26-level and Android 14/15 (ds 6.0) | `feature/smtp-send-spike` |
| 2 | app-foundation | `app-foundation/` | Gradle, manifest, deps, R8/packaging, Application, constants, time helper | `feature/app-foundation` |
| 3 | settings-storage | `settings-storage/` | DataStore, Keystore password crypto, recipients, SetupRequired (ds 8) | `feature/settings-storage` |
| 4 | camera-capture | `camera-capture/` | CameraX, capture, resize, permission (ds 4) | `feature/camera-capture` |
| 5 | photo-send-flow | `photo-send-flow/` (3 sub-plans) | MainViewModel state machine, father-facing UI, undo window, foreground send, feedback (ds 2, 3, 10.2) | `feature/photo-send-flow` |
| 6 | settings-screen | `settings-screen/` (4 sub-plans) | Hidden settings UI (ds 9, 3.3) | `feature/settings-screen` |
| 7 | attention-notifications | `attention-notifications/` | Notification, badges, notification permission (ds 10.1, 3.2) | `feature/attention-notifications` |
| 8 | acceptance-verification | `acceptance-verification/` | Run ds 12 checklist, release build check | `feature/acceptance-verification` |

Order: 1 first. Then 2, 3, 4 (3 and 4 are independent after 2). Then 5, 6, 7 UI-side parts. The pending features (section 2) must be planned and built before the send-dependent parts of 5, 6, 7 are completed; those parts are marked "BLOCKED on pending" inside the plans.

## 2. Pending features (pending spike result)

Per user decision (Q7), these are NOT planned yet; their directories do not exist. They are written only after the spike result is recorded and the user is consulted.

| Feature | Scope | ds sections |
|---|---|---|
| mail-composition-smtp | MIME composition, session properties, offline shortcut, error classification, send log recording | 5, 6.1-6.4 |
| outbox-queue | Room schema, counting, item creation, batching, 15 MB split, 要確認 cap/overflow, duplicates, startup recovery | 7.1-7.3, 7.6-7.8, 2.4 |
| outbox-worker | WorkManager KEEP, SENDING_BG, backoff, 24 h expiry, resume triggers, 今すぐ再送 | 7.4, 7.5 |

## 3. Confirmed planning decisions (user answers)
- Preview reserves one active slot (ds 2.4): confirmed.
- Capture-failure message shown about 3 s, then Camera.
- Timers (undo window, foreground send, Sent/QueuedNotice) keep running in background while the process lives; on process death recovery sends via the worker.
- Package name `com.example.parentphoto`.
- Success feedback: vibration 300 ms + `TONE_PROP_ACK`; vibration only in silent/vibrate mode.
- Scaffolding is its own plan (`app-foundation`).
- All other ds `[ASSUMPTION]` items are accepted as written. Any conflict found while planning is a new question to the user, not decided by the developer.
- User supplies a Gmail app password via a local git-ignored file, an API 26-level emulator, and an Android 14/15 device (for the spike).

## 4. Cross-plan interface contracts
Plans 5, 6, 7 are written against these names from ds 11.1 so the pending plans can implement them later without rework. Exact signatures are fixed by the pending plans.
- `OutboxRepository`: observe `activeCount`, `attentionCount`, item lists; create item (phase CANCEL_WINDOW); phase transitions; delete; resume.
- `SmtpSender` / `MailResult`: foreground single-photo send and text-only test send, with error classification.
- Send log writer.
Until the pending plans exist, sub-plans use fakes behind these interfaces only for UI development and tests.
