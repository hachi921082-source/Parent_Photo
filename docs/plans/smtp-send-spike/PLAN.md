# Plan: smtp-send-spike (FIRST implementation task)

Source: ds 6.0, 6.1, 11.3, 11.4. Branch: `feature/smtp-send-spike`. Status: to be done FIRST, before any other plan is implemented.

## Index
1. Goal
2. Inputs the user provides
3. Design
4. Steps
5. Pass criteria and recording
6. Failure handling

## 1. Goal
Prove that `com.sun.mail:android-mail` + `android-activation` send a `multipart/mixed` mail with an `image/jpeg` attachment from an R8-minified release build on an API 26-level device and an Android 14/15 device. Result decides whether the pending plans (mail-composition-smtp, outbox-queue, outbox-worker) can be written.

## 2. Inputs the user provides (confirmed Q9)
- Gmail address and 16-char app password in a local file `spike.local.properties` (keys `smtp.user`, `smtp.password`, `smtp.to`) at the project root. Add to `.gitignore` before creating it. Never commit, log, or paste it.
- API 26-level emulator and an Android 14/15 device.

## 3. Design
- Throwaway module/app in the repo under `spike/` (or a branch-only `app` with a single Activity); package `com.example.parentphoto.spike`. Deleted or left unmerged after the result is recorded (decision at merge time with the user).
- Build config: release type with `minifyEnabled true`, signed with the debug key; keep rules and packaging options from ds 11.4 added only when an error requires them (record which were needed).
- One screen: button "send". On click, `Dispatchers.IO` builds a Session (host `smtp.gmail.com`, port 465, `mail.smtp.ssl.enable=true`, `auth=true`, timeouts 15000/30000/30000), a `MimeMessage` with a UTF-8 subject and Japanese body, and one JPEG attachment (a bundled sample image of about 300 KB, `FileDataSource` or `ByteArrayDataSource`, file name `yyyyMMdd_HHmmss.jpg`). Result (success or full exception class + message) shown on screen and in Logcat. No password logging.
- A second variant sends two attachments (verifies multi-attachment and same-name handling).

## 4. Steps
1. Add `spike.local.properties` to `.gitignore`; commit that first.
2. Create the spike project with dependencies `android-mail` and `android-activation` (latest 1.6.x), minSdk 26.
3. Implement the send screen.
4. Run debug build on both devices (baseline).
5. Run R8 release build on both devices. Fix only with the ds 11.4 keep rules / `pickFirsts`; note each.
6. Confirm the mail arrives with correct subject, body, and openable JPEG attachment(s).

## 5. Pass criteria and recording
- Pass: mail with attachment received from the R8 release build on both devices, with no `UnsupportedDataTypeException`, `NoClassDefFoundError`, or packaging error.
- Record in `docs/plans/smtp-send-spike/RESULT.md` (create at that time): library version, devices/API levels, the exact keep rules and packaging options required, any problems.

## 6. Failure handling
If the pass criteria cannot be met after applying ds 11.4 measures: STOP, do not write the pending plans, report to the user (library choice Q30 may need revisiting).
