@~/.claude/shared/dev-workflow.md

## About This App

One-touch photo sending app for an elderly, smartphone-unfamiliar father. Authoritative spec: `docs/spec.md` (index at top; read only the needed section).

- Component: Android app only (Kotlin / Jetpack Compose, CameraX, DataStore). No server; GAS was dropped.
- Main flow: camera (full screen, one shutter button) -> preview/select ("やり直す" or a recipient button) -> "送信中..." -> "送りました！" -> camera.
- Hidden settings: 3 s long-press on a small corner icon; recipient (display name + email) add/edit/delete, SMTP settings (sender address, app password, host, port), sender name, button size, test send. App password stored encrypted (Keystore) in DataStore.
- Send: the app mails the resized JPEG directly via JavaMail (`com.sun.mail:android-mail`) over SMTP on `Dispatchers.IO`.
- UI text is Japanese.
- Failed sends go to an app-private outbox (WorkManager auto-resend, max 5 active + 10 "要確認", batched per recipient, 3 s undo window before sending).
- App name: FamilyPhoto (package `com.example.parentphoto`).
- Status: all 10 features implemented and merged; acceptance run done on emulator and real device M06 (2026-10-21/22); `docs/QA.md` Q1-Q76 all resolved; remote is a private GitHub repo. Release APK is signed with the debug key (decided). Open items and working rules: `docs/HANDOFF.md`.

## Agent Set

Lite set: `designer-lite`, `developer-lite`, `reviewer-lite` (spec.md / DESIGN.md / QA.md, one-way escalation, no persistent log). Small self-contained personal app with no `AGENT_LOG.md`. Do not mix with the full set (`designer`, `developer`, `reviewer`, `tester`).
