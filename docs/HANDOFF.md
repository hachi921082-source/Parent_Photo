# Session Handoff

Resume point for a fresh conversation. Updated 2026-10-21.

## Index
1. Status
2. Where Things Are
3. Key Decisions
4. Open Items
5. Next Step
6. Working Notes

## 1. Status
- Phase: all 10 features DONE and merged to `main` (`docs/plans/README.md` Status column). Acceptance run done 2026-10-21 (emulator API 26, real device M06 Android 16, 7 real mails to the user's mailbox, user confirmed). Remaining: open acceptance items below, release signing, optional push.

| Feature | State | Notes |
|---|---|---|
| app-foundation | done | compile/targetSdk 35 (AGP 8.7.3 limit, user-confirmed); release signed with debug key (final signing decided later, user-confirmed) |
| settings-storage | done | validation messages in `strings.xml` `err_*` via `ValidationError` enum (not Validators constants) |
| camera-capture | done | |
| attention-notifications | done | `AttentionNotifier` + `NotificationPermissionRequester` provided; callers not wired yet |
| mail-composition-smtp | done | `ItemId = String` (matches `outbox_item.id` UUID); `SmtpSettingsSource` interface extracted, implemented by `SettingsRepository`; sendForeground checks recipient before offline (Q63) |
| outbox-queue | done | `AttentionAlerter` interface extracted from `AttentionNotifier` (test seam); `ParentPhotoApp` builds `settingsRepository`/`outboxRepository` lazily, `appScope` (SupervisorJob + logging handler) runs observers and startup recovery; `createItem` awaits recovery; androidTest 73/73 x3 |
| outbox-worker | done | `OutboxRunner` holds the run algorithm (`OutboxWorker` is thin; non-cancel exceptions -> `Result.retry()`); process `Mutex` prevents overlapping runs; `OutboxScheduler` (KEEP default; triggerNow REPLACE unless RUNNING, else `outbox-send-next`); `ParentPhotoApp` adds lazy `smtpSender` and `workScheduler`; startup recovery uses `triggerNow` when QUEUED rows exist (Q64); JVM 87/87, androidTest 82/82 x3 |
| photo-send-flow | done | ports in `ui/MainPorts.kt` over real repos (`MainViewModelFactory`); timers on `elapsedRealtime` (`DeadlineTimer`), re-checked in `onResume`; father text `lineHeight = 1.3.em` (agent choice to stop 2-line overlap; user may override); Q65 notice overlay 3000 ms; foreground failure -> `triggerNow` (Q68); `onCleared` during CancelWindow/Sending -> `requeueAbandoned` + `triggerNow` (Q69); JVM 139/139, androidTest 107/107 x3 |
| settings-screen | done | Activity-scoped `SettingsViewModel` (`onOpened`/`onClosed` reset); navigation-compose nested graph (`settings`, `settings/unsent`, `settings/log`); `MainViewModel` no longer evaluates precedence while settings are open; Q70-Q73; JVM 188, androidTest 141 x3 |
| acceptance-verification | done | R8 release APK; edge-to-edge insets fix found on M06 and fixed (`fatherInsetsPadding`, `SystemBars`, `SettingsInsets`, `enableEdgeToEdge`); real mail items pass; ds 12 checklist updated (open items kept with reasons); Q73, Q74, D16 |

- Agent set: lite (`designer-lite`, `developer-lite`, `reviewer-lite`). Declared in `CLAUDE.md`.
- Git: local repo on `main`, no remote, nothing pushed. Clean working tree.
- `docs/QA.md`: Q1-Q76 all resolved (Q73 = look/wording adopted without per-item confirmation; Q74 = swipe-away limit accepted). Q1, Q2, Q8, Q10 are obsolete (GAS dropped).

## 2. Where Things Are
- `CLAUDE.md`: app summary, agent set, status line.
- `docs/spec.md`: confirmed spec (7 sections).
- `docs/detailed-spec.md` (ds): implementation-level spec. Read its index, then only the needed section.
- `docs/QA.md`: every user decision with the chosen option.
- `docs/DECISIONS.md`: rationale and accepted risks (D1-D15).
- `docs/plans/README.md`: plan index with order, dependencies, status.
- Code: single module `app/`, package `com.example.parentphoto` (`camera/`, `mail/`, `notify/`, `settings/`, `ui/`, `util/`).

## 3. Key Decisions
- Android app only; no GAS/Drive. Direct SMTP via `com.sun.mail:android-mail` + `android-activation` (Gmail, default `smtp.gmail.com:465`).
- App password: 16 chars after removing spaces, Keystore AES-GCM in DataStore, `allowBackup=false`.
- Flow: camera -> preview/select -> 3 s undo window -> foreground send (max 60 s) -> "送りました！" / failure goes to outbox.
- Outbox: Room; WorkManager `KEEP` (never REPLACE a RUNNING worker); max 5 active, separate pool of 10 "要確認" (oldest overflow auto-deleted); batched per recipient; split at about 15 MB; retries stop after 24 h; auth failure goes straight to 要確認; every app start with QUEUED rows retries immediately (backoff reset, Q64).
- UI: portrait lock, min API 26, shutter standard size, other father-facing buttons 56/96/128 dp (default 96), text 28/36 sp, settings via 3 s long press (no PIN).

## 4. Open Items
Carry-overs that later features MUST handle (check them in the named feature's review):
- Acceptance items still open (see ds 12 notes; none blocks use): need a human with the phone: edge-swipe back on Loading (lasts under about 0.25 s on M06, cannot be hit; shutter-then-swipe verified 8/8 by script; Camera/OutboxFull correctly exit on back); predictive back/QueuedNotice 2 s duration; Sent screen under gesture navigation (needs real mail); badge legibility over a bright real camera image; (photo sizes verified: portrait 1205x1600 and landscape 1600x1205, q about 85). Not provokable or not yet tried: first-attempt 60 s cap and 30/60 s timeouts; PERMANENT/REJECTED classification; recipient edit -> new address with real mail; sender-name CR/LF (unit tests only); resize-stage failure; 15 MB split; Android 14/15 device (spike covered it); 587 port itself not visible in Logcat (send succeeded after saving the port).
- Fixed after acceptance: success vibration was dropped on M06 (usage TOUCH, touch vibration off); now explicit attributes + 600 ms at amplitude 255 (Q75, D17), verified by the user in normal/vibrate/silent.
- Verified on M06 with injected faults (2026-10-21): CameraX bind failure and capture/save failure behave per spec (3.0 s message, timer restart, no leftovers); gesture navigation OK on all screens; edge-swipe back ignored in Preview/CancelWindow/Sending, closes the app on Camera/OutboxFull. After a bind failure the screen stays black until resume (Q76, accepted).
- Accepted limits: swipe from recents within ms of `triggerNow` may delay the send until next app start or a pending backoff job (Q74, D16; reopen with the delayed safety-net work idea if the father swipes often). Dark-photo gear on Preview is barely visible (ds 3.4 behaviour). Camera feed extends under bars with white status icons (may be hard to read in a very bright scene).
- Not adopted ideas (can be reopened): QR-code settings import, screen-keep-on, send-to-everyone, pre-send confirmation dialog.
- Known minor: name length check counts UTF-16 units (emoji = 2); unused mailcap `pickFirsts` in packaging (harmless).

## 5. Next Step
1. No feature work is pending. Decide with the user: (a) release signing key (currently debug key), (b) whether to push (no remote yet), (c) whether to split commit `4cad22c` (bundles QA.md + plans/README.md + HANDOFF.md), (d) re-run the open acceptance items on a device when convenient (esp. vibration settings, predictive back, gesture navigation). New work follows the same cycle below.
2. Per feature, follow this cycle (dev-workflow section 7):
   1. `git checkout -b feature/<name>`.
   2. developer-lite implements.
   3. reviewer-lite reviews; fix loop via developer-lite if needed.
   4. `git merge --no-ff` to `main` and delete the branch.
   5. Update the `docs/plans/README.md` Status column and this file.
   6. Push only with user confirmation.

## 6. Working Notes
- Questions to the user: selectable options (up to 4) with a recommendation listed first. Agents never decide user-facing or spec-ambiguous matters: subagents return questions; the main Claude asks via AskUserQuestion and records the answer in `docs/QA.md`.
- Subagent prompts that worked: tell the agent the branch is already checked out; give it the Gradle/adb paths; tell it to read only the PLAN and the ds sections it cites; forbid reading `spike.local.properties` / `local.properties`; forbid real mail sends; for temp test harnesses in `src/`, use Edit/Write, not Bash overwrites (blocked by the permission classifier), and revert them.
- Build: Gradle 8.9 run directly, `~/.gradle/wrapper/dists/gradle-8.9-bin/*/gradle-8.9/bin/gradle`, with `JAVA_HOME='/c/Program Files/Android/Android Studio/jbr'`. adb: `~/AppData/Local/Android/Sdk/platform-tools/adb.exe`.
- Devices: emulator `spike26` (API 26) is emulator-5570. Real device M06 (serial M06-SERIAL, Android 16) is not connected; reconnect it with USB debugging approval when needed.
- Docs are English only; chat reports to the user are in Japanese.
- Commits:
  - one file per commit, with a Japanese message describing the file's role;
  - trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` (model switched to Opus on 2026-10-19);
  - never push without user confirmation;
  - known slip: commit `4cad22c` bundled `QA.md` + `plans/README.md` + `HANDOFF.md`; not yet split, user's call.
- Token saving: one conversation per feature; build/logcat output reduced to pass/fail plus a few lines.
- `spike.local.properties` (git-ignored) holds Gmail credentials; never read or commit it.
