# Session Handoff

Resume point for a fresh conversation. Updated 2026-10-18.

## Index
1. Status
2. Where Things Are
3. Key Decisions
4. Open Items
5. Next Step
6. Working Notes

## 1. Status
- Phase: specification and all PLANs complete (11 features, see `docs/plans/README.md`); spike DONE (R8 release mail send passed on API 26 emulator + M06; `docs/plans/smtp-send-spike/RESULT.md`). Implementation of the app not started.
- Agent set: lite (`designer-lite`, `developer-lite`, `reviewer-lite`). Declared in `CLAUDE.md`.
- Git: local repo on `main`, no remote. All docs committed (one file per commit, Japanese messages).
- `docs/QA.md`: Q1-Q50 all resolved. Q1, Q2, Q8, Q10 are obsolete (GAS dropped).

## 2. Where Things Are
- `CLAUDE.md`: app summary, agent set, status line.
- `docs/spec.md`: confirmed spec (7 sections).
- `docs/detailed-spec.md`: implementation-level spec (state machine, UI strings, SMTP, outbox, data model, settings, checklist). Read its index, then only the needed section.
- `docs/QA.md`: every user decision with the chosen option.
- `docs/DECISIONS.md`: rationale and accepted risks (D1-D13).
- `docs/plans/README.md`: empty plan index.

## 3. Key Decisions
- Android app only; no GAS/Drive. Direct SMTP via `com.sun.mail:android-mail` + `android-activation` (Gmail, default `smtp.gmail.com:465`).
- App password: 16 chars after removing spaces, Keystore AES-GCM in DataStore, `allowBackup=false`.
- Flow: camera -> preview/select -> 3 s undo window -> foreground send (max 60 s) -> "送りました！" / failure goes to outbox.
- Outbox: Room; WorkManager `KEEP` (never REPLACE a RUNNING worker); max 5 active, separate pool of 10 "要確認" (oldest overflow auto-deleted); batched per recipient; split at about 15 MB; retries stop after 24 h; auth failure goes straight to 要確認.
- UI: portrait lock, min API 26, shutter standard size, other father-facing buttons 56/96/128 dp (default 96), text 28/36 sp, settings via 3 s long press (no PIN).

## 4. Open Items
- Accepted risks: credentials on the father's phone (D10), no expiry for 要確認 until overflow (D12).
- Unconfirmed `[ASSUMPTION]` tags remain in `docs/detailed-spec.md` (for example: preview reserves one active slot, 3 s capture-failure message, background behavior of the undo window, package name `com.example.parentphoto`, 300 ms vibration). Review them or let developer-lite flag conflicts.
- Not adopted ideas (can be reopened): QR-code settings import, screen-keep-on, send-to-everyone, pre-send confirmation dialog.

## 5. Next Step
1. Implement per `docs/plans/README.md` order: app-foundation -> settings-storage and camera-capture -> mail-composition-smtp -> outbox-queue -> outbox-worker -> real wiring of photo-send-flow / settings-screen / attention-notifications -> acceptance-verification. developer-lite implements, reviewer-lite reviews.
2. Decision (user): implement ALL features sequentially, one at a time, no parallel worktrees. Order: app-foundation -> settings-storage -> camera-capture -> attention-notifications -> mail-composition-smtp -> outbox-queue -> outbox-worker -> photo-send-flow -> settings-screen -> acceptance-verification. Use one conversation per feature; read only the feature PLAN and the ds sections it cites. UI work before outbox wiring uses fakes.
3. Follow dev-workflow section 7 (feature branch per feature, `--no-ff` merge, push only with user confirmation).
4. Spike tooling: `spike/run-spike.sh`, AVD `spike26` (API 26), device M06 (serial M06-SERIAL). `spike.local.properties` (git-ignored) holds Gmail credentials; never read or commit it.

## 6. Working Notes
- The user prefers questions as selectable options (up to 4 at a time) with a recommendation; recommended option listed first.
- Check label wording when relaying answers to subagents (a Q24 answer was once mis-relayed and corrected).
- Docs are English only; chat reports to the user are Japanese.
- External review (Gemini) points were already applied (WorkManager policy, JavaMail spike, BackHandler).
