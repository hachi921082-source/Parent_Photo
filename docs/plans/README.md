# Implementation Plans

Each feature's implementation plan lives in its own directory: `docs/plans/<feature-name>/PLAN.md`. Large features have `PLAN.md` as an index plus `PLAN-N-<topic>.md` sub-plans. Branch name: `feature/<feature>` (merge with `--no-ff`; push only with user confirmation).

Source of truth: `docs/spec.md`, `docs/detailed-spec.md` (ds), `docs/QA.md`. Section references below are ds sections.

## Index
1. Features and order
2. Former pending features (now planned)
3. Confirmed planning decisions
4. Cross-plan interface contracts
5. Status of earlier BLOCKED/fake markers
6. Notes for implementers

## 1. Features and order

| # | Feature | Directory | Scope | Branch | Status |
|---|---|---|---|---|---|
| 1 | smtp-send-spike | `smtp-send-spike/` | Throwaway JavaMail multipart+JPEG send, R8 release, API 26-level and Android 14/15 (ds 6.0) | `feature/smtp-send-spike` | DONE (PASS, merged to main; `RESULT.md`; ds 11.4/6.3 and D14 updated). Code under `spike/` is throwaway and is not part of `app`. |
| 2 | app-foundation | `app-foundation/` | Gradle, manifest, deps, R8/packaging, Application, constants, time helper | `feature/app-foundation` | done (merged) |
| 3 | settings-storage | `settings-storage/` | DataStore, Keystore password crypto, recipients, SetupRequired (ds 8) | `feature/settings-storage` | done (merged) |
| 4 | camera-capture | `camera-capture/` | CameraX, capture, resize, permission (ds 4) | `feature/camera-capture` | done (merged) |
| 5 | mail-composition-smtp | `mail-composition-smtp/` (1 plan) | MIME composition, session, offline shortcut, error classification, Logcat/send-log recording (ds 5, 6.1-6.4) | `feature/mail-composition-smtp` | done (merged; real-send smoke deferred to acceptance-verification) |
| 6 | outbox-queue | `outbox-queue/` (3 sub-plans) | Room, counting, creation, batching, overflow, recovery, resume/promotion (ds 7.1-7.3, 7.5-7.8, 2.4) | `feature/outbox-queue` | done (merged; uses `NoOpWorkScheduler` until outbox-worker; photo-send-flow end-to-end check deferred to photo-send-flow) |
| 7 | outbox-worker | `outbox-worker/` (2 sub-plans) | WorkManager run, KEEP/REPLACE scheduling, backoff, 24 h expiry, resume triggers (ds 7.4, 7.5) | `feature/outbox-worker` | done (merged; Q64 startup triggerNow; device checks 1-2 UI side -> photo-send-flow, 3-4 -> settings-screen, 5-6 -> acceptance-verification) |
| 8 | photo-send-flow | `photo-send-flow/` (3 sub-plans) | MainViewModel state machine, father-facing UI, undo window, foreground send, feedback (ds 2, 3, 10.2) | `feature/photo-send-flow` | done (merged; Q65-Q69; real-device checks -> acceptance-verification) |
| 9 | settings-screen | `settings-screen/` (4 sub-plans) | Hidden settings UI (ds 9, 3.3) | `feature/settings-screen` | done (merged; Q70-Q73; real-server AUTH check and focus issue on real device -> acceptance-verification) |
| 10 | attention-notifications | `attention-notifications/` | Notification, badges, notification permission (ds 10.1, 3.2) | `feature/attention-notifications` | done (merged; call sites are in outbox-queue PLAN-2) |
| 11 | acceptance-verification | `acceptance-verification/` | Run ds 12 checklist, release build check | `feature/acceptance-verification` | done (merged 2026-10-21; open items listed in HANDOFF section 4 and ds 12) |
| 12 | connectivity-indicator | `connectivity-indicator/` | Camera-screen online/offline pill with signal icon (issue #3; ds 3.1, 3.4, 6.2) | `feature/connectivity-indicator` | done (merged; JVM 191 + androidTest 151 pass on emulator; real-device check pending) |
| 13 | preview-many-recipients | `preview-many-recipients/` | Preview: やり直す on top, recipient buttons compress at 4+, photo floor = 3-recipient photo, half-cut scroll hint (issue #4; ds 3.2, 3.4) | `feature/preview-many-recipients` | implemented (JVM 200 + androidTest 155 pass on emulator; real-device check pending) |

(Numbers are the build order; the old numbering 5-8 became 8-11.)

Order: spike done. Then 2 (app-foundation); 3 and 4 are independent after 2. Then 5 (mail), 6 (outbox-queue), 7 (outbox-worker). Plan 6 needs the types of 5 and `AttentionNotifier` from 10 (use a fake notifier until 10 is built; 10 can be built any time after 2-3). The UI-side parts of 8 and 9 can be built earlier against fakes (section 4), but their real wiring is done after 5-7. 11 last.

Dependency summary: 5 <- 2, 3; 6 <- 2, 3, 5 (types), 10 (interface); 7 <- 5, 6; 8 <- 2, 3, 4 and 5-6 for wiring; 9 <- 2, 3 and 5-7 for wiring.

## 2. Former pending features (now planned)

| Feature | Scope | ds sections |
|---|---|---|
| mail-composition-smtp | MIME composition, session properties, offline shortcut, error classification, Logcat and send-log recording | 5, 6.1-6.4 |
| outbox-queue | Room schema, counting, item creation, batching planner, 15 MB split, 要確認 cap/overflow, duplicates, startup recovery, resume and promotion | 7.1-7.3, 7.5-7.8, 2.4 |
| outbox-worker | WorkManager KEEP, SENDING_BG, backoff, 24 h expiry, resume triggers, 今すぐ再送 scheduling | 7.4, 7.5 |

Split decisions: outbox-queue has three sub-plans (storage-counting, recovery-overflow, resume-promotion; resume/promotion was split out of the earlier two-way proposal because it is large and self-contained); outbox-worker has worker-run and scheduling-resume.

## 3. Confirmed planning decisions (user answers)
- Preview reserves one active slot (ds 2.4): confirmed.
- Capture-failure message shown exactly 3000 ms (`CAPTURE_FAILURE_MESSAGE_MS`), then hidden; a second failure while visible restarts the timer (Q45, Q58).
- Timers (undo window, foreground send, Sent/QueuedNotice) keep running in background while the process lives; on process death recovery sends via the worker.
- Package name `com.example.parentphoto`.
- Success feedback: vibration 300 ms + `TONE_PROP_ACK`; vibration only in silent/vibrate mode.
- Scaffolding is its own plan (`app-foundation`).
- All other ds `[ASSUMPTION]` items are accepted as written. Any conflict found while planning is a new question to the user, not decided by the developer.
- User supplies a Gmail app password via a local git-ignored file, an API 26-level emulator, and an Android 14/15 device (used by the spike; the acceptance run reuses them).
- Duplicate mails after an ambiguous failure ("Exception reading response") are accepted (D14).
- UI audit decisions Q51-Q58 (D15; ds 3.2, 3.4, 9, 10): father-facing screens ignore the system font scale (`fontScale = 1f` via `LocalDensity`), the hidden settings screen follows it (Q51); father-facing layouts/colors per ds 3.4, Material3 default light scheme, warning badge `#E65100`, badges 20 sp, no animation (Q52, Q55); 保存 is enabled whenever the form is dirty, validates on press, scrolls to/focuses the first error, shows Snackbar "保存しました" (Q53); app name `FamilyPhoto` (launcher label, `app_name`, release file `FamilyPhoto_YYYYMMDD-HHMMSS.apk`; package unchanged) (Q54); notification: no title, `BigTextStyle`, `setOnlyAlertOnce(true)`, `setAutoCancel(false)`, text of the latest event (Q56); Snackbars are Material3 `SnackbarDuration.Short` without action and only for "保存しました" and "順番待ちです。しばらくお待ちください"; no other resend feedback (Q57); all UI strings in `res/values/strings.xml`, precedence re-evaluated on every `onResume` without a new permission dialog, sub-screen routes/dialog dismissal rules, form keyboards/IME/autofill, test-send hint priority, unsent-list and send-log layouts, content descriptions (Q58, ds 9.1-9.9).

## 4. Cross-plan interface contracts
Defined now (exact names in the owning plans); plans 8, 9, 10 use fakes behind them until the real feature is built.
- `OutboxRepository` (owner: `outbox-queue/PLAN.md` section 2): observe `activeCount`, `attentionCount`, `items`, `sendLog`, `recoveryDone`; `setPreviewExists`; `createItem`, `deleteItem`, `setPhase`, `markSent`, `markQueued(itemId, MailResult)`; `countForRecipient`, `onRecipientDeleted`, `resendNow`, `resumeAfterSetupFix`, `deleteManual`.
- `SmtpSender` / `MailResult` (owner: `mail-composition-smtp/PLAN.md` section 2): `sendForeground(itemId)`, `sendBatch(request)`, `sendTest(recipient)`; results `Success | Offline | RecipientMissing | Failure(kind, code, ...)`.
- Send log writer: `SendLogWriter` interface (mail-composition-smtp section 2.3), Room implementation in outbox-queue PLAN-1 section 6; display strings stay in settings-screen PLAN-4.
- `WorkScheduler` (`enqueueDefault`, `triggerNow`): owned by outbox-queue, implemented by outbox-worker `OutboxScheduler`.
- `AttentionNotifier` (`show(overflowDeleted)`, `cancel()`): owned by attention-notifications; called from outbox-queue PLAN-2.

## 5. Status of earlier BLOCKED/fake markers
The plans written earlier (photo-send-flow, settings-screen, attention-notifications, app-foundation, acceptance-verification) were not rewritten. Read their phrases like this:
- "BLOCKED on pending ..." / "pending plans" / "fake until pending plans" now means: the dependency is planned in the new plans above; build the UI side against fakes, then replace the fakes when plans 5-7 are implemented. The contract names they use (`createItem`, `deleteItem`, `setPhase`, `markSent`, `markQueued`, `sendForeground`, `activeCount`, `attentionCount`, recovery-finished signal) all exist unchanged in the new plans.
- attention-notifications section 4 "call sites added by outbox-queue" = outbox-queue PLAN-2 section 3.
- app-foundation section 3 says R8 and `pickFirsts` "exactly as found necessary in the spike result": that result is `smtp-send-spike/RESULT.md` sections 3 and 6 and ds 11.4 (keep rules from `spike/app/proguard-rules.pro` are mandatory; plus `resources.excludes += META-INF/LICENSE.md, META-INF/NOTICE.md`; mailcap `pickFirsts`). `ParentPhotoApp` additionally builds the outbox container and starts recovery and observers (outbox-queue PLAN.md section 3) when plan 6 is built.

## 6. Notes for implementers (clarifications, no contract change)
- Recovery runs in `ParentPhotoApp.onCreate` on the application scope and exposes `recoveryDone` / `awaitRecovery()`; `MainViewModel` keeps Loading until it is true (photo-send-flow PLAN-1 "recovery-finished signal"), the worker waits for it too (outbox-queue PLAN-2 section 4).
- `previewExists` (photo-send-flow PLAN-1 section 7): true in Preview; on 取り消す set it true before `deleteItem`; after `createItem` set it false (outbox-queue PLAN-1 section 4).
- Recipient delete (settings-screen PLAN-2): call `SettingsRepository.deleteRecipient` first, then `OutboxRepository.onRecipientDeleted(id)`; delete dialog count from `countForRecipient(id)` (outbox-queue PLAN-2 section 5).
- Test send (settings-screen PLAN-3): `SmtpSender.sendTest` writes TEST_OK / TEST_FAILED itself; on success the ViewModel calls `OutboxRepository.resumeAfterSetupFix()`. SMTP save (PLAN-1) also calls `resumeAfterSetupFix()`.
- 今すぐ再送 (PLAN-4) calls `resendNow(itemId)`; `Waiting` shows the Material3 `Snackbar` (`SnackbarDuration.Short`, no action) "順番待ちです。しばらくお待ちください"; no other feedback when a resend starts (Q57).
- 60 s foreground cap: enforced inside `SmtpSender` (authoritative); the `withTimeoutOrNull(FG_SEND_TIMEOUT_MS)` in photo-send-flow PLAN-3 is a harmless backstop (null = timeout).
