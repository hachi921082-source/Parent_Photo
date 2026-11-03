# Session Handoff

Resume point for a fresh conversation. Updated 2026-10-22.

## Index
1. Status
2. Where Things Are
3. Key Decisions
4. Open Items
5. Next Step
6. Working Notes

## 1. Status
- Issue #4 `preview-many-recipients` merged 2026-10-23 (plans/README row 13): Preview has the やり直す row on top; with 4+ recipients buttons compress (height to 56 dp, then spacing 16 -> 8 dp) inside the 3-recipient list area, the photo is never smaller than its 3-recipient size (25 % small-screen safety floor), scrolling shows a half-cut last button; CancelWindow/Sending use the same frame so the photo does not move, and 取り消す sits in the top row (where やり直す is). Look items to confirm on a real phone: 取り消す at the top is a longer reach after tapping a recipient at the bottom (3 s window); scroll hint is only the half-cut button (no scrollbar). JVM 200 and androidTest 155 pass (emulator API 26). Not yet seen on M06 with 4-6 recipients (add recipients in settings, delete them afterwards). M06 still has FamilyPhoto_20261023-185903 (old layout): rebuild and install before checking.
- Badge tap-to-resend (issue #1) was merged 2026-10-22 and removed 2026-10-23 (D18). Issue #3 `connectivity-indicator` done 2026-10-23: Camera screen shows a signal icon + 「撮影してすぐ送ります」/「現在圏外なので後で送ります」 pill (fixed top-left, 108 dp below the inset); badge is plain 「未送信 N件」. JVM 191 and androidTest 151 pass (emulator API 26). Real-device check on M06 done 2026-10-23 (airplane mode ON -> 圏外 text, OFF -> 送れます text, offline shot queued as 未送信 1件, auto-resend within about 4 s of the network returning, mail arrived; user: no problem). App icon changed 2026-10-23 (white camera with an orange envelope badge on purple; `drawable/ic_launcher_foreground.xml`; user checked on M06). M06 has FamilyPhoto_20261023-185903 (repo root copy, git-ignored) with the pill, the new icon and without the badge tap; give that APK (or a newer build) to the father. Emulator now has the old spike APK (`com.example.parentphoto.spike`) installed by a review step; reinstall the app with `installDebug` when needed.
- Issue #2 (message text on Preview / mail subject) closed as not planned on 2026-10-22 (user decision); analysis and alternatives are in the issue comment. Reopen candidates: per-recipient fixed subject suffix in settings (smallest), preset phrase buttons, voice input.
- All 10 features are DONE and merged to `main` (`docs/plans/README.md` Status column). Acceptance run finished 2026-10-21/22: emulator API 26, real device M06 (Android 16), real mails to the user's mailbox (user confirmed in the inbox), user hands-on checks (vibration/sound, badge legibility, landscape shot). The release APK is built from the current code.
- Git: `main` is pushed to the PRIVATE GitHub repo `hachi921082-source/Parent_Photo` (remote `origin`; initial push approved 2026-10-22; user wants it to stay private). `spike.local.properties` / `local.properties` are git-ignored and not in history; no real mailbox addresses in the repo. Working tree clean.
- Release APK: `app/build/outputs/apk/release/FamilyPhoto_20261021-223722.apk` (about 3.2 MB, R8, signed with the debug key; git-ignored; rebuild with `:app:assembleRelease`, the file name carries date-time). No app code changed after it was built (only docs).
- Tests at last run: JVM 188+ and androidTest 148 passing (emulator API 26).
- `docs/QA.md`: Q1-Q76 all resolved (Q1, Q2, Q8, Q10 obsolete: GAS dropped). Q73 = look/wording adopted without per-item confirmation; Q74 = swipe-away delay accepted; Q75 = 600 ms vibration; Q76 = black screen after CameraX bind failure accepted.
- Agent set: lite (`designer-lite`, `developer-lite`, `reviewer-lite`), declared in `CLAUDE.md`.

| Feature | Notes |
|---|---|
| app-foundation | compile/targetSdk 35 (AGP 8.7.3 limit); release signed with the debug key (decided, see Open Items) |
| settings-storage | validation messages in `strings.xml` `err_*` via `ValidationError` enum |
| camera-capture | CameraX capture, resize 1600 px / JPEG 85, EXIF rotation burned into pixels |
| attention-notifications | `AttentionNotifier` (called by outbox-queue) + `NotificationPermissionRequester` |
| mail-composition-smtp | `ItemId = String`; `SmtpSettingsSource`; sendForeground checks recipient before offline (Q63) |
| outbox-queue | Room outbox, `OutboxRepository`, `AttentionAlerter` seam, `appScope`, startup recovery |
| outbox-worker | `OutboxRunner` + thin `OutboxWorker`, `OutboxScheduler`; startup recovery and failed foreground sends use `triggerNow` (Q64, Q68) |
| photo-send-flow | `MainViewModel` state machine over ports (`MainPorts.kt`), father UI, undo window, feedback; Q65-Q69 |
| settings-screen | hidden settings (SMTP form, recipients, test send, unsent list, send log); Q70-Q73 |
| acceptance-verification | ds 12 checklist updated with evidence; edge-to-edge insets fix (`fatherInsetsPadding`, `SystemBars`, `SettingsInsets`, `enableEdgeToEdge`); vibration fix (D17, Q75) |

## 2. Where Things Are
- `CLAUDE.md`: app summary, agent set, status line.
- `docs/spec.md`: confirmed spec (7 sections).
- `docs/detailed-spec.md` (ds): implementation-level spec; section 12 is the acceptance checklist with evidence notes. Read its index, then only the needed section.
- `docs/QA.md`: every user decision with the chosen option (Q1-Q76).
- `docs/DECISIONS.md`: rationale and accepted risks (D1-D17).
- `docs/plans/README.md`: plan index with order and status; per-feature plans have "Implementation notes".
- Code: single module `app/`, package `com.example.parentphoto` (`camera/`, `mail/`, `notify/`, `outbox/`, `settings/`, `ui/`, `util/`). Entry wiring in `ParentPhotoApp` (lazy `settingsRepository`, `outboxRepository`, `smtpSender`, `workScheduler`, `previewFiles`, `cameraController`, `appScope`).

## 3. Key Decisions
- Android app only; no GAS/Drive. Direct SMTP via `com.sun.mail:android-mail` + `android-activation` (Gmail, default `smtp.gmail.com:465`; 587 STARTTLS works).
- App password: 16 chars after removing spaces, Keystore AES-GCM in DataStore, `allowBackup=false`.
- Flow: camera -> preview/select -> 3 s undo window -> foreground send (max 60 s) -> "送りました！" (600 ms vibration at amplitude 255 + tone in normal mode; vibration only in vibrate/silent) / failure -> "あとで送ります" and the outbox.
- Outbox: Room; WorkManager `KEEP` (never REPLACE a RUNNING worker); max 5 active, separate pool of 10 "要確認" (oldest overflow auto-deleted); batched per recipient; split at about 15 MB; retries stop after 24 h; auth failure goes straight to 要確認; every app start with QUEUED rows retries immediately (backoff reset, Q64).
- UI: portrait lock, min API 26, edge-to-edge with system-bar insets handled, shutter standard size, other father-facing buttons 56/96/128 dp (default 96), text 28/36 sp (system font scale ignored on father screens, followed on settings), settings via 3 s long press on the gear (no PIN).

## 4. Open Items
- Signing (decided 2026-10-22, user): keep the Android debug key for release builds (changing it would force M06 to reinstall and lose its settings). Key: `~/.android/debug.keystore`, alias `androiddebugkey`, store password `android` (standard debug key, not secret), valid until 2056-06-13, SHA-256 `F0:AE:B7:D8:49:8E:4D:5C:F4:D9:83:C3:DD:7E:B9:99:A5:8B:34:4E:8A:9F:09:ED:08:FD:B4:65:A8:71:8B:90`. Backup copy: `%USERPROFILE%DocumentsFamilyPhoto-keystore-backupdebug.keystore` (same PC; the user should also copy it to a USB stick or cloud). If this PC is replaced, restore that file to `~/.android/debug.keystore` before building, otherwise installed phones (M06, the father's phone) cannot be updated in place. Never commit the key.
- Father's phone: the user installs and configures it personally with the release APK (3 s long-press on the gear opens settings; needs "install unknown apps" once).
- Acceptance items still open (none blocks use; see ds 12 notes): edge-swipe back on Loading cannot be hit by hand (lasts under about 0.25 s on M06; shutter-then-swipe verified 8/8 by script; Camera/OutboxFull correctly exit on back); first-attempt 60 s cap and 30/60 s timeouts; PERMANENT/REJECTED classification; recipient edit -> new address with real mail; sender-name CR/LF (unit tests only); resize-stage failure; 15 MB split; Android 14/15 device (spike covered it); port 587 is not visible in Logcat (send succeeded after saving the port).
- Accepted limits: swipe from recents within ms of `triggerNow` may delay the send until next app start or a pending backoff job (Q74, D16; reopen with the delayed safety-net work idea if the father swipes often). After a CameraX bind failure the screen stays black until resume (Q76). Dark-photo gear on Preview is barely visible (ds 3.4). Camera feed extends under the bars with white status icons (hard to read in a very bright scene). The system halves the vibration amplitude on M06 (D17). Credentials live on the father's phone (D10), no expiry for 要確認 until overflow (D12), M06 "Exception reading response" false failures did not reproduce (D14).
- Not adopted ideas (can be reopened): QR-code settings import, screen-keep-on, send-to-everyone, pre-send confirmation dialog.
- Known minor: name length check counts UTF-16 units (emoji = 2); unused mailcap `pickFirsts` in packaging (harmless); commit `4cad22c` bundled three docs (left as is, see Working Notes).

## 5. Next Step
1. No feature work is pending. Likely next actions: the user installs the APK on the father's phone; fixes after real use; the open acceptance items when convenient.
2. For any change, follow the cycle (dev-workflow section 7):
   1. `git checkout -b feature/<name>` (bug fixes `fix/<slug>`).
   2. developer-lite implements.
   3. reviewer-lite reviews; fix loop via developer-lite if needed.
   4. `git merge --no-ff` to `main` and delete the branch.
   5. Update `docs/plans/README.md` Status, QA/DECISIONS/ds when a decision changes, and this file.
   6. Push only with the user's confirmation (approval is per push; the user approved the initial push and one follow-up).

## 6. Working Notes
- Questions to the user: ask right away only about behavior gaps and rare error paths (selectable options, up to 4, recommendation first). Look/wording choices are built as recommended, recorded in the plan's Implementation notes and shown as one list at the end of a feature (user approved this 2026-10-21). Agents never decide user-facing or spec-ambiguous matters: subagents return questions; the main Claude asks via AskUserQuestion and records the answer in `docs/QA.md`.
- Subagent prompts that worked: say the branch is already checked out; give Gradle/adb paths; tell it to read only the PLAN and the ds sections it cites; one developer agent continued via SendMessage per sub-plan, then one reviewer-lite pass, fixes back to the same developer; for temp test harnesses in `src/`, use Edit/Write and revert them; always target devices with `-s <serial>` (emulator and M06 are both attached).
- Credentials and real mail: `spike.local.properties` (git-ignored) holds the Gmail sender address and app password; never print, log, commit or paste its values. Reading it programmatically to type into the app, and sending real mail, are allowed only after the user's explicit approval for that task (recipient only the user's test mailbox = `smtp.to` in `spike.local.properties`, small mail cap, no credential output, no `pm clear` of the credentials without telling the user). Subagents refuse approvals that are only relayed by another agent: state the user's direct answer and keep the terms narrow. Without approval use dummy values (host 10.255.255.1, dummy@example.com / abcdabcdabcdabcd, recipient Test / test@example.com).
- Build: Gradle 8.9 run directly, `~/.gradle/wrapper/dists/gradle-8.9-bin/*/gradle-8.9/bin/gradle`, with `JAVA_HOME='/c/Program Files/Android/Android Studio/jbr'` (no wrapper). adb: `~/AppData/Local/Android/Sdk/platform-tools/adb.exe`. In Git Bash set `MSYS_NO_PATHCONV=1` for `/sdcard` paths; Python run from Git Bash cannot read `/tmp` (use Windows-style paths).
- Devices: emulator `spike26` (API 26) is emulator-5570. Real device M06 (serial: see `adb devices`, FCNT arrows, Android 16, 1080x2400, weak vibration motor, touch vibration off) is the real-phone test device: left with the release APK, the user's real Gmail settings saved (recipient Test = the user's test mailbox), 3-button navigation and the Camera screen showing. Do not `pm clear` its app data after checks (user's request 2026-10-22): keep settings and permissions, delete only queued test photos from 未送信一覧; restore nav mode (`navigation_mode` 0), `stay_on_while_plugged_in` (0), Wi-Fi/data, ringer mode afterwards. The user may unplug it at any time. Emulator app state: debug APK reinstalled with empty app data (SetupRequired screen).
- Docs are English only; chat reports to the user are in Japanese. Reports to the user: short, with what was done, what is open and what the user must do.
- Commits: one file per commit, Japanese message describing the file's role (no file-path prefix); trailer `Co-Authored-By: <current model> <noreply@anthropic.com>` (Opus 5.5 until 2026-10-21, Sonnet 5.5 since); never push without the user's confirmation. Known slip: commit `4cad22c` bundled `QA.md` + `plans/README.md` + `HANDOFF.md`; not split on purpose (buried under about 380 later commits including `--no-ff` merges; rewriting would change every later hash, decided 2026-10-22).
- Token saving: one conversation per feature; build/logcat output reduced to pass/fail plus a few lines.
