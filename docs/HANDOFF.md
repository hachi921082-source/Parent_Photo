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
- Phase: implementation in progress. 9 of 10 features DONE and merged to `main` (`docs/plans/README.md` Status column).

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
| acceptance-verification | next | |

- Agent set: lite (`designer-lite`, `developer-lite`, `reviewer-lite`). Declared in `CLAUDE.md`.
- Git: local repo on `main`, no remote, nothing pushed. Clean working tree.
- `docs/QA.md`: Q1-Q73 all resolved (Q73 = look/wording adopted without per-item confirmation). Q1, Q2, Q8, Q10 are obsolete (GAS dropped).

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
- Settings-screen leftovers for acceptance-verification: outbox-worker PLAN-2 section 5 check 3 remainder (real server rejects wrong password -> 要確認 + notification -> fix and save -> mail arrives); test-send success path and AUTH/REJECTED wording with a real server; focus issue on real device (Android 16): after sender-name entry + 保存, or password 変更 -> キャンセル/保存, focus may jump to the sender-name field and open the keyboard (reproduced on API 26 only; if it reproduces, needs a dedicated focus design); Snackbar "順番待ちです" needs 5 active items; Japanese/emoji names typed on a real keyboard.
- Real-device manual checks (acceptance-verification): CameraX bind failure (not reproducible on emulator); Q69 swipe from recents with process alive -> worker attempts at once; real foreground success: "送りました！", 300 ms vibration + tone, vibration only in silent/vibrate; check 1 photo arrives and badge clears; readability of father screens. (Permission deny / never-ask-again and landscape capture already passed on emulator.)
- Manual checks on an API 33+ device (only API 26 AVD exists; M06 is Android 16):
  - notification permission dialog appears once only, and not again after deny + restart;
  - no crash on `show` without permission;
  - notification appears after grant;
  - tap brings the app to front with the camera screen kept.
- acceptance-verification MUST recover the unmet mail-composition-smtp Done-when:
  - real 1- and 2-photo send via R8 release APK on both device classes, attachments readable;
  - 587 STARTTLS;
  - no password/body in Logcat;
  - outbox-worker PLAN-2 section 5 device checks 5 (24 h expiry, shortened window or test clock) and 6 (multi-photo worker send on R8 release APK, real mail).

Other open items:
- Release keystore not decided (debug key for now).
- No Gradle wrapper (see section 6 for how to build).
- Accepted risks: credentials on the father's phone (D10), no expiry for 要確認 until overflow (D12), M06 "Exception reading response" false failures on 2-attachment sends (D14).
- Remaining `[ASSUMPTION]` tags in ds are accepted as written; a conflict found during implementation = new question to the user, never decided by the agent.
- Spike gap: API 29 (sense4) not tested.
- Not adopted ideas (can be reopened): QR-code settings import, screen-keep-on, send-to-everyone, pre-send confirmation dialog.
- Known minor: name length check counts UTF-16 units (emoji = 2); unused mailcap `pickFirsts` in packaging (harmless).

## 5. Next Step
1. Implement acceptance-verification (`docs/plans/acceptance-verification/PLAN.md`; runs the ds 12 checklist and the release build check; needs real device M06 (serial M06-SERIAL, Android 16, USB debugging approval) and the user's Gmail app password in the git-ignored `spike.local.properties`; ask the user to prepare both before starting; real mail is allowed only there). New working rule (user approved 2026-10-21): ask the user right away only about behavior gaps and rare error paths; build look/wording as recommended, record in Implementation notes, and list them at the end of the feature. Order: acceptance-verification is the last feature. Strictly sequential, no parallel worktrees (user decision).
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
