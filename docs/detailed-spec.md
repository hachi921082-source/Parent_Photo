# Detailed Specification

<!-- Elaboration of spec.md to a level usable for implementation plans (docs/plans/<feature-name>/PLAN.md). Only decided items; rationale is in DECISIONS.md. Items marked [ASSUMPTION] are implementation-level choices not stated in spec.md/QA.md; developer-lite may follow them, and the user can override. -->

## Index
1. Scope and conventions
2. Screen state machine
3. UI strings and layout rules
4. Camera and image processing
5. Mail composition
6. SMTP send, error classification, logging
7. Outbox
8. Settings data model, secret storage, sizes
9. Hidden settings screen
10. Notifications, haptics, sound
11. Module layout, manifest, build notes
12. Acceptance checklist (mapped to spec.md)

## 1. Scope and conventions
- Android only (Kotlin, Jetpack Compose, min API 26). No server. UI text Japanese. Portrait-locked UI.
- Time: all displayed and mailed times use Asia/Tokyo (`ZoneId.of("Asia/Tokyo")`), independent of device time zone. Capture time is stored as epoch millis (UTC) and formatted at use.
- Constants:
  - `UNDO_SECONDS = 3`, `SENT_SCREEN_MS = 2000`, `QUEUED_NOTICE_MS = 2000`
  - `FG_SEND_TIMEOUT_MS = 60000`, SMTP connect 15000, read 30000, write 30000
  - `ACTIVE_CAP = 5`, `ATTENTION_CAP = 10`, `RECIPIENT_CAP = 6`
  - `RETRY_WINDOW_MS = 24 h`, `BACKOFF_INITIAL = 15 min` (exponential), `BATCH_MAX_BYTES = 15 MB` (sum of attachment file sizes; Base64 adds about 37 percent, keeping the mail under Gmail's 25 MB)
  - `SEND_LOG_MAX = 50`, `LONG_PRESS_MS = 3000`, `RECIPIENT_NAME_MAX = 10`
- Items marked [ASSUMPTION] are implementation choices beyond the confirmed spec.

## 2. Screen state machine
Single `MainViewModel` holds `UiState`; Compose renders one screen per state. Navigation to Settings is a separate route (back returns to the previous main state).

### 2.1 States
| State | Description |
|---|---|
| Loading | App start until settings (DataStore first emission) and outbox recovery (7.6) have finished; blank screen with no buttons. [ASSUMPTION] |
| Capturing | From shutter tap until `preview.jpg` is ready (capture + resize); shutter disabled, small progress indicator; other buttons absent. [ASSUMPTION] |
| PermissionDenied | Camera permission not granted: guidance + open-settings button. |
| SetupRequired | Sender address, app password, host, or port empty/invalid. |
| Camera | Full-screen preview, shutter, badges, settings icon. |
| OutboxFull | Camera screen variant: 5 active items; shutter disabled; guidance. |
| Preview | Captured (already resized) photo; やり直す; recipient buttons; settings icon. |
| CancelWindow | 3 s undo window after a recipient tap. |
| Sending | Foreground send, up to 60 s. |
| Sent | "送りました！" for 2 s. |
| QueuedNotice | "あとで送ります" for 2 s. |
| Settings | Hidden settings (separate route). |

### 2.2 Precedence at launch and after returning from Settings
0. Loading is shown until settings and outbox recovery are ready (prevents a SetupRequired flash). [ASSUMPTION]
1. If SetupRequired condition holds -> SetupRequired (camera not started).
2. Else if camera permission is not granted -> request the permission (system dialog) once per cold start, the first time the camera would be shown; if denied -> PermissionDenied. Returning from system app settings (resume) re-checks the permission without a new dialog. [ASSUMPTION: rule "once per cold start"] [ASSUMPTION: SetupRequired is checked before camera permission, since photos could not be sent anyway.]
3. Else if active items = 5 -> OutboxFull; else Camera.
- If a captured photo exists (state was Preview) when opening Settings, returning goes back to Preview with the same photo (photo kept in `cache/preview.jpg`; setup re-evaluated: if now SetupRequired, the photo is kept and Preview is shown again once setup is valid). Preview is never restored after process death or app restart: at every cold start `cache/preview.jpg` and `cache/capture_raw.jpg` are deleted. [CONFIRMED by the user, Q46]

### 2.3 Transitions
| From | Event | To | Actions |
|---|---|---|---|
| (launch) | app start | Loading, then per 2.2 | Delete `cache/preview.jpg` and `cache/capture_raw.jpg`; recover outbox rows (7.6); create the notification channel (10.1). |
| PermissionDenied | tap "設定を開く" | system app settings | `ACTION_APPLICATION_DETAILS_SETTINGS`; on resume re-evaluate 2.2. |
| Camera | tap shutter | Capturing | Record capture time, take photo. |
| Capturing | photo and resize OK | Preview | `preview.jpg` ready (4.3). |
| Capturing | capture/decode/resize error, OOM, camera init error | Camera (or OutboxFull) | Delete partial files; show the message "撮影できませんでした。もう一度お試しください" (36 sp, centered, about 3 s, then hides); log to Logcat (tag `ParentPhoto/Camera`). [CONFIRMED by the user, Q45] |
| Camera | active count reaches 5 | OutboxFull | Observed via outbox Flow. |
| OutboxFull | active count < 5 | Camera | Automatic. |
| Preview | tap やり直す | Camera (or OutboxFull) | Delete cached photo file; no send. |
| Preview | tap recipient button | CancelWindow | Create outbox item (7.3) with phase CANCEL_WINDOW; copy photo into outbox dir. |
| CancelWindow | tap 取り消す | Preview | Delete the item row and its outbox file; the cached preview photo is untouched (photo kept). |
| CancelWindow | 3 s elapsed | Sending | Phase -> SENDING_FG. |
| Sending | success | Sent | Delete item (row + file); log SENT; vibrate + sound. |
| Sending | failure/timeout (60 s) | QueuedNotice | Classify (6.3); phase -> QUEUED (transient/network) or state -> 要確認 (auth/permanent); log; enqueue worker. |
| Sending | device clearly offline before attempt | QueuedNotice | Skip attempt; phase -> QUEUED; enqueue worker (no log entry of failure; log QUEUED_OFFLINE). |
| Sent | 2 s | Camera (or OutboxFull) | Delete cached preview photo. |
| QueuedNotice | 2 s | Camera (or OutboxFull) | Delete cached preview photo. |
| any main state except Loading/Capturing/CancelWindow/Sending/Sent/QueuedNotice | 3 s long press on settings icon | Settings | See 3.3. |
| Settings | back | previous main state | Re-evaluate 2.2 (setup may have changed). |
- Cached preview photo lives at `cache/preview.jpg`; only one exists at a time. Deleted on やり直す, Sent, QueuedNotice.
- Buttons: during CancelWindow all buttons except 取り消す are disabled; during Sending all buttons are disabled; the settings icon is hidden in Loading, Capturing, CancelWindow, Sending, Sent, QueuedNotice. [ASSUMPTION: icon hidden in those transient states.]
- System back in Loading, Capturing, Preview, CancelWindow, Sending, Sent, and QueuedNotice is ignored (no accidental discard). Implementation: each of these composables declares `BackHandler(enabled = true) { }` (also covers Android 13+ predictive back; set `android:enableOnBackInvokedCallback` consistently so the empty handler takes effect). Camera, OutboxFull, SetupRequired, PermissionDenied keep the default back behavior (leaves the app). [ASSUMPTION]
- Background/foreground behavior [CONFIRMED by the user]: the CancelWindow timer and the foreground send run in `viewModelScope` using `SystemClock.elapsedRealtime`; they continue while the app is in the background as long as the process lives. On return the screen shows the current state (e.g., Sending still running, or Camera if Sent/QueuedNotice already elapsed; the Sent/QueuedNotice 2 s timers also run on elapsed time and the vibration/sound is not replayed). If the process is killed, recovery (7.6) sends the photo via the worker.
- Zero recipients: Preview shows the text "送り先が登録されていません" in place of recipient buttons; やり直す stays.

### 2.4 Active count
- Active items = outbox items with phase in {CANCEL_WINDOW, SENDING_FG, SENDING_BG, QUEUED} (state 待機中). 要確認 items are never counted.
- Strict cap: the active count never exceeds 5 (no 6th item). Shutter blocked (OutboxFull) iff active count >= 5. A photo can only be taken when active <= 4. While a photo exists in Preview, it reserves one slot: promotions of 要確認 items by 今すぐ再送 / settings save (7.5) are allowed only while `active + (previewExists ? 1 : 0) < 5`; otherwise the item waits (`resendPending`). As a defensive guard, a recipient tap with active >= 5 is rejected and shows the OutboxFull guidance. [CONFIRMED by the user, Q47; reservation rule also CONFIRMED by the user]

## 3. UI strings and layout rules

### 3.1 Exact strings (father-facing)
| Place | String |
|---|---|
| Preview, retry button | やり直す |
| Preview, 0 recipients | 送り先が登録されていません |
| CancelWindow label | `{n}秒後に送ります` with n = 3, 2, 1 (updates every second) |
| CancelWindow button | 取り消す |
| Sending | 送信中... |
| Sent | 送りました！ |
| QueuedNotice | あとで送ります |
| Badge, active items (shown on Camera/OutboxFull when N > 0) | 未送信 N件 |
| Badge, 要確認 (separate, warning color, shown when M > 0) | 送れていません M件 |
| OutboxFull guidance | 送れていない写真が5枚あります。ネットワークを確認してください |
| SetupRequired | 設定が必要です (no button) |
| PermissionDenied guidance [ASSUMPTION wording] | カメラの使用を許可してください |
| PermissionDenied button [ASSUMPTION wording] | 設定を開く |
| Capture failure message | 撮影できませんでした。もう一度お試しください |
| Notification (requires attention) | 写真が送れていません。家族に連絡してください |
| Notification (overflow deleted oldest) | 写真が送れていません。古い写真1枚を削除しました。家族に連絡してください |
- The word "要確認" is administrator-facing only (hidden settings); never shown to the father.
- Recipient button label: the display name (max 10 characters, `RECIPIENT_NAME_MAX`), wraps to at most 2 lines (`maxLines = 2`), button grows in height beyond the minimum if needed; list order is registration order (fixed; no reordering UI; editing keeps the position). [CONFIRMED by the user, Q48]
- Shutter content description: 撮影 (accessibility only). [ASSUMPTION]

### 3.2 Sizes
- Father-facing text: buttons 28 sp, messages 36 sp, fixed (not affected by the button size setting; specified in `sp`).
- Button size levels (min height, full width): 標準 56 dp / 大 96 dp / 特大 128 dp, default 大. Applies to: やり直す, recipient buttons, 取り消す, 設定を開く (PermissionDenied). Does not apply to: the shutter button (standard, a 72 dp circular button [ASSUMPTION]), the settings icon, and the hidden settings screen.
- Recipient list: vertically scrollable `LazyColumn` above the やり直す button, max 6 entries; やり直す is pinned at the bottom.
- Badges: top-left corner of Camera/OutboxFull; "未送信 N件" neutral color, "送れていません M件" warning color (e.g., orange/red), text 20 sp [ASSUMPTION: smaller than message text].

### 3.3 Settings icon and long press
- Small gear icon (24 dp visual, 48 dp touch target) in the top-right corner of Camera, OutboxFull, Preview, SetupRequired, PermissionDenied.
- Tap does nothing visible. Long press of 3000 ms (finger held continuously; movement beyond touch slop cancels) opens Settings. Implement with `pointerInput` + `awaitLongPressOrCancellation`-like timer at 3 s. No PIN, no haptic hint required.

## 4. Camera and image processing

### 4.1 CameraX
- `ProcessCameraProvider`, `Preview` (PreviewView, FILL_CENTER), `ImageCapture` (CAPTURE_MODE_MINIMIZE_LATENCY), back camera, lifecycle-bound to the activity while state is Camera/OutboxFull.
- Activity `screenOrientation="portrait"` (UI never rotates).
- Photo orientation follows how the device is held: an `OrientationEventListener` maps device orientation to `Surface.ROTATION_*` and sets `imageCapture.targetRotation` continuously.

### 4.2 Capture
- On shutter tap (state -> Capturing): record `capturedAtMillis = System.currentTimeMillis()`, call `takePicture` to `cache/capture_raw.jpg`. Any `ImageCaptureException`, or camera provider/bind failure at startup, follows the Capturing error transition in 2.3 (camera init failure at startup also shows the same message and retries binding on the next resume).

### 4.3 Resize
- After capture (before showing Preview): decode with EXIF orientation applied (bake rotation into pixels), scale so the long edge is at most 1600 px (no upscaling; keep aspect ratio), encode JPEG quality 85, write `cache/preview.jpg` (no EXIF orientation tag needed since rotation is baked in), delete the raw file. Resizing happens right after capture (not at send time). Any exception/OOM -> error transition in 2.3.
- Run on `Dispatchers.Default`/IO. Preview displays `preview.jpg` (already correctly oriented) with `ContentScale.Fit`.
- The same bytes are copied into the outbox at send time (no second resize).

### 4.4 Permission
- Request `CAMERA` once when the camera is first needed. Denied (including "don't ask again") -> PermissionDenied with the open-settings button.

## 5. Mail composition
- Built with `javax.mail` (`Session`, `MimeMessage`, `MimeMultipart("mixed")`).
- From: `InternetAddress(senderAddress, senderName, "UTF-8")` -> "sender name <address>"; if the sender name setting is empty, address only. CR/LF characters are stripped from the sender name before use (header-injection guard); it is also trimmed. [ASSUMPTION] [ASSUMPTION: no "家族" fallback in From; the fallback applies to the body only.]
- Reply-To: not set.
- To: the recipient's email address only (single recipient; no Cc/Bcc). [ASSUMPTION: display name not included in To.]
- Date: now.
- Subject (UTF-8, `setSubject(s, "UTF-8")`): 1 photo "写真が届きました"; N >= 2 photos "写真が届きました（N枚）" (full-width parentheses).
- Body (text/plain; charset=UTF-8; lines separated by `\n`), with `{sender}` = sender name setting, or "家族" when empty:
  - 1 photo:
    ```
    {sender}さんから写真が届きました。
    撮影日時: yyyy/MM/dd HH:mm
    ```
  - N photos: first line `{sender}さんから写真が届きました。`, then one line `撮影日時: yyyy/MM/dd HH:mm` per photo in ascending capture order (same order as attachments).
- Attachments: `image/jpeg`, one `MimeBodyPart` per photo from the outbox file (`FileDataSource` / `ByteArrayDataSource`), Base64 transfer encoding, file name `MimeUtility.encodeText`-safe ASCII `yyyyMMdd_HHmmss.jpg` from the capture time (Asia/Tokyo). Same-second duplicates within one mail get `_2`, `_3` before the extension (e.g., `20261017_091500_2.jpg`).
- Batching (outbox retry only; foreground send is always 1 photo): group queued items by recipient id, sort by capture time ascending, fill a mail until the sum of file sizes would exceed 15 MB, then start a new mail. Each mail uses the subject/body rules above with its own N. With 5 photos at 1600 px this almost never splits.
- Test mail (9.5): subject "テスト送信", body "これはテスト送信です。", no attachment.

## 6. SMTP send, error classification, logging

### 6.0 Early spike (FIRST implementation task)
- Before building the outbox or UI, implement a throwaway spike that sends a multipart mail with a JPEG attachment through `android-mail` + `android-activation` using a real Gmail app password, built as an R8-minified release build, and verify on (a) an API 26-level device/emulator and (b) a recent device (Android 14/15).
- Known risks to check: `javax.activation` / mailcap handler loading failing on multipart (`UnsupportedDataTypeException: no object DCH for MIME type multipart/mixed`), `NoClassDefFoundError` for `java.awt.*` / `javax.activation` classes, R8 stripping `javax.mail` / `com.sun.mail` classes or META-INF mailcap resources, duplicate META-INF resource packaging errors.
- Result recorded before proceeding; if it fails, stop and ask the user (library choice Q30 may need revisiting). [ASSUMPTION]

### 6.1 Library and session
- Dependencies: `com.sun.mail:android-mail` and `com.sun.mail:android-activation` (1.6.x; pick the latest 1.6.x at implementation time and verify on API 26 and a release build).
- Session properties: `mail.smtp.host`, `mail.smtp.port`, `mail.smtp.auth=true`, `mail.smtp.connectiontimeout=15000`, `mail.smtp.timeout=30000`, `mail.smtp.writetimeout=30000`.
  - Port 465: `mail.smtp.ssl.enable=true`. Any other port: `mail.smtp.starttls.enable=true` and `mail.smtp.starttls.required=true` (cleartext never used). [ASSUMPTION]
  - `mail.smtp.ssl.checkserveridentity=true`.
  - Authenticator returns (sender address, app password); the send uses `Transport.send(message)`.
- Defaults: host `smtp.gmail.com`, port `465`.
- All sending runs on `Dispatchers.IO`. Overall cap per attempt: `withTimeout(60_000)` around the whole `Transport.send`; on timeout treat as NETWORK (code TIMEOUT).
- Settings (address, password, host, port) are read from storage at send time (not cached across sends).

### 6.2 Offline shortcut (foreground only)
- Before a foreground attempt, check `ConnectivityManager.activeNetwork` has `NET_CAPABILITY_INTERNET`; if not (or null), skip the attempt and queue immediately (6.4). Otherwise attempt even if not `VALIDATED`.

### 6.3 Error classification
| Class | Detection | Outbox effect | Code (log) |
|---|---|---|---|
| NETWORK / TRANSIENT | `UnknownHostException`, `ConnectException`, `SocketTimeoutException`, `SocketException`, `SSLException` (handshake/IO), other `IOException`, our 60 s timeout, SMTP 4xx replies | stay 待機中 (phase QUEUED); retry per 7.4 | NETWORK, TIMEOUT |
| AUTH | `AuthenticationFailedException`; SMTP 530/534/535 | -> 要確認 (reason AUTH) immediately | AUTH |
| PERMANENT | `SendFailedException` with 5xx reply (e.g., recipient rejected 550/553), `AddressException`, other non-auth 5xx | -> 要確認 (reason PERMANENT) immediately | REJECTED, OTHER |
| Unknown exception | anything else | treated as TRANSIENT (retry until 24 h, then 要確認) [ASSUMPTION] | OTHER |
- The father sees no cause (UI: QueuedNotice for every failure).
- A `MessagingException: Exception reading response` (connection dropped after the data was sent) is classed NETWORK. The mail may already have been delivered, so a retry can deliver a duplicate. Accepted (DECISIONS.md D14).

### 6.4 Recording
- Logcat: tag `ParentPhoto/Mail`; record class, code, exception class and message, SMTP reply code. Never log the password or the message body.
- Send log (7.7, last 50 entries, visible in hidden settings): one entry per mail attempt result with time, recipient display name (or "（削除された宛先）"), result, and short code.
  - Results: `SENT`, `FAILED` (with code NETWORK/TIMEOUT/AUTH/REJECTED/OTHER), `QUEUED_OFFLINE`, `DELETED_MANUAL`, `DELETED_OVERFLOW` (shown as "削除（上限超過）"), `RESEND_REQUESTED` (written when the family taps 今すぐ再送, with the recipient name), `TEST_OK`, `TEST_FAILED`.

## 7. Outbox

### 7.1 Storage layout
- Photos: app-private `filesDir/outbox/{itemId}.jpg` (not in shared storage; excluded from backup; not scanned by media).
- Metadata: Room database `outbox.db` (dependency `androidx.room`), tables `outbox_item` and `send_log`. [CONFIRMED: Room chosen for atomic queries; DataStore JSON is an acceptable alternative.]
- `outbox_item` columns: `id` (UUID text, PK), `recipientId` (text), `capturedAtMillis` (long), `retryStartMillis` (long; start of the 24 h window; reset on resume), `attentionSinceMillis` (long, nullable), `state` (`WAITING` | `ATTENTION`), `phase` (`CANCEL_WINDOW` | `SENDING_FG` | `SENDING_BG` | `QUEUED`, meaningful when WAITING), `attentionReason` (`AUTH` | `PERMANENT` | `RETRY_EXPIRED` | `RECIPIENT_DELETED`, nullable), `resendPending` (boolean), `fileSize` (long; read by batching to sum attachment size without file I/O).
- The destination email is NOT stored in the item (read from the recipient list by `recipientId` at send time).
- `send_log` columns: `id` (auto), `timeMillis`, `recipientName`, `result`, `code`. Trimmed to the newest 50 after each insert.

### 7.2 Counting
- Active = `state = WAITING` (phases CANCEL_WINDOW, SENDING_FG, SENDING_BG, QUEUED). 要確認 = `state = ATTENTION`.
- Observed through Room `Flow` for badges and OutboxFull.

### 7.3 Item creation
- At the start of CancelWindow: insert row (WAITING, phase CANCEL_WINDOW, `retryStartMillis = now`) and copy `preview.jpg` to `outbox/{id}.jpg`. This makes the photo survive an app kill.

### 7.4 Worker
- `OutboxWorker` (`CoroutineWorker`) in unique work `outbox-send` (enqueue policy: `KEEP` by default; never `REPLACE` while the work is RUNNING), constraint `NetworkType.CONNECTED`, backoff `BackoffPolicy.EXPONENTIAL` initial 15 min (WorkManager caps the delay at about 5 h).
- Enqueue when: an item becomes QUEUED after a failed/offline foreground send; settings are saved with SMTP fields (7.5); a test send succeeds; 今すぐ再送 is tapped; app start recovery (7.6) found queued items.
- Run:
  1. Load WAITING items with phase QUEUED; if none, `Result.success()`.
  2. For each item whose `now - retryStartMillis > 24 h` (and not yet sent): set state ATTENTION, reason RETRY_EXPIRED, `attentionSinceMillis = now`; apply 7.8 overflow rule; notify (10.1). Skip them below.
  3. Read SMTP settings; if SetupRequired condition holds, treat as AUTH for all remaining items (-> 要確認, reason AUTH, notify once) and return success.
  4. Group remaining items by `recipientId`; for a missing recipient -> 要確認 reason RECIPIENT_DELETED (notify); otherwise batch per 5 and send each mail with the current recipient address.
  5. Per mail: success -> delete rows and files, log SENT; NETWORK/TRANSIENT -> keep QUEUED, log FAILED, mark the run for `Result.retry()`; AUTH/PERMANENT -> those items to 要確認 (reason accordingly), log FAILED, notify.
  6. Return `Result.retry()` if any item remains QUEUED after transient failures; else `Result.success()`.
- Triggers (今すぐ再送 / settings save / test success): first write the state change to Room (reset `retryStartMillis`, clear attention state, set phase QUEUED), then enqueue. A manual 今すぐ再送 on a 待機中/QUEUED item also resets `retryStartMillis` (restarts the 24 h window) and triggers an immediate worker run. [ASSUMPTION] If `WorkInfo` state is not RUNNING (absent/ENQUEUED/BLOCKED), enqueue with `REPLACE` so the backoff delay is reset; if it is RUNNING, do not replace: enqueue a follow-up one-time work `outbox-send-next` (`KEEP`, same constraints). [ASSUMPTION]
- The running worker re-queries Room for QUEUED items after each mail batch, so items resumed during a run are picked up.
- Before `Transport.send()` the worker sets the batch items' phase to `SENDING_BG` (counts as active; treated like SENDING_FG in recovery 7.6). `onStopped` / cancellation (`CancellationException`) leaves the items WAITING/QUEUED (phase restored to QUEUED) with no log entry, so a cancelled run never leaves a half-done state; a duplicate mail is possible only in the ambiguous case already accepted (7.7).
- A mail is atomic: all photos in a failed mail stay or move together.
- The worker never touches items in CANCEL_WINDOW or SENDING_FG (SENDING_BG items are its own).
- Retries stop after 24 h from `retryStartMillis` (step 2): the photo is kept as 要確認 (not deleted).

### 7.5 要確認 handling
- Reasons: AUTH, PERMANENT, RETRY_EXPIRED, RECIPIENT_DELETED.
- Resume triggers (-> 待機中/QUEUED, `retryStartMillis = now`, enqueue worker): saving SMTP settings (address, password, host, port) resumes all ATTENTION items with reason AUTH, PERMANENT, RETRY_EXPIRED; a successful test send does the same; 今すぐ再送 on a single item resumes it regardless of reason except RECIPIENT_DELETED (button disabled for that reason; the item can only be deleted). [ASSUMPTION: RECIPIENT_DELETED items are not resumable; re-adding a recipient does not restore the link.]
- Active cap on resume: if the active count is 5, a manual 今すぐ再送 tap does NOT send immediately. The item stays 要確認 with `resendPending = true`, the UI shows the short notice "順番待ちです。しばらくお待ちください" (snackbar/toast in hidden settings), and the item is promoted automatically, oldest first (by `attentionSinceMillis`), when a slot is free (2.4). `OutboxRepository` observes the active-count Flow (and the Preview-reservation flag) and performs the promotion (sets phase QUEUED, resets `retryStartMillis`, enqueues the worker). [CONFIRMED by the user, Q47; observer location is an ASSUMPTION] Each manual 今すぐ再送 tap writes a `RESEND_REQUESTED` log entry. [ASSUMPTION: notice wording] The same applies to resumes triggered by settings save / test success.
- Recipient edited: the new address is used at the next send (read by id). Recipient deleted: every WAITING/QUEUED item of that recipient moves to 要確認 (reason RECIPIENT_DELETED) immediately in the same transaction as the deletion; items in CANCEL_WINDOW/SENDING_FG of that recipient finish normally (attempt uses the address read at attempt start).

### 7.6 Startup recovery
- On process start: rows with phase CANCEL_WINDOW, SENDING_FG, or SENDING_BG (left by a kill) -> phase QUEUED so the photo is sent by the worker; orphan files without a row are deleted; rows without a file are deleted; enqueue the worker if any QUEUED exists. [CONFIRMED: a kill during the undo window sends the photo rather than discarding it.]

### 7.7 Duplicates
- Ambiguous failures (e.g., timeout after DATA) are retried anyway; no dedupe; each mail attempt gets a new Message-ID (default JavaMail behavior).

### 7.8 要確認 cap and overflow
- When an item becomes ATTENTION and the ATTENTION count exceeds 10: delete the ATTENTION item with the smallest `attentionSinceMillis` (row + file), log `DELETED_OVERFLOW` (recipient name, capture time not required), and use the overflow notification text (10.1). This is the only automatic deletion.
- Manual delete from the unsent list: row + file, log `DELETED_MANUAL`.

## 8. Settings data model, secret storage, sizes

### 8.1 DataStore (Preferences) `settings`
| Key | Type | Default | Notes |
|---|---|---|---|
| `sender_name` | String | "" | Used as `{sender}` and in From. |
| `smtp_user` | String | "" | Sender email address. |
| `smtp_host` | String | `smtp.gmail.com` | |
| `smtp_port` | Int | 465 | 1-65535. |
| `password_cipher` | String | "" | Base64(IV 12 bytes + AES-GCM ciphertext+tag). |
| `button_size` | String | `LARGE` | `STANDARD` / `LARGE` / `XLARGE` -> 56/96/128 dp. |
| `recipients_json` | String | `[]` | JSON array of `{id, name, email}`, max 6, order preserved. |
| `notif_permission_asked` | Boolean | false | POST_NOTIFICATIONS requested once. |
- Recipient `id` = UUID generated at creation; never reused.

### 8.2 App password encryption
- Android Keystore AES-256 key, alias `parent_photo_smtp_pw`, `AES/GCM/NoPadding`, random 12-byte IV per encryption, no user-authentication requirement. Implemented with `javax.crypto` + `AndroidKeyStore` (Tink not required). [ASSUMPTION]
- Stored only as `password_cipher`. Decrypt at send time. If decryption fails (key lost) treat the password as unset -> SetupRequired.
- The plaintext is never shown again after saving (9.2), never logged.
- Backup exclusion: `android:allowBackup="false"` in the manifest (excludes DataStore, outbox photos, and `outbox.db`). [CONFIRMED: whole-app backup disabled instead of fine-grained rules.]

### 8.3 SetupRequired condition
- True if any: `smtp_user` not a valid email format; password unset/undecryptable; `smtp_host` blank; `smtp_port` outside 1-65535. (Sender name and recipients are not part of this check.)

## 9. Hidden settings screen
Standard Material sizes (not the father-facing button sizes). A scrollable single screen with a top app bar titled "設定" and a back arrow.

### 9.1 Sections and labels [ASSUMPTION: labels]
1. 送り先 (recipients): header "送り先 n/6"; list of rows in registration order (display name, email) with edit and delete icons; "追加" button (disabled at 6 recipients). Add/edit dialog: fields "表示名" and "メールアドレス", buttons "保存"/"キャンセル". Delete shows a confirm dialog: with no queued photos for that recipient "この宛先を削除しますか？"; with N queued photos (WAITING or ATTENTION) "この宛先を削除しますか？ 送れていない写真が{N}枚あります。削除すると、その写真は送られなくなります（未送信一覧に残ります）。" Buttons "削除"/"キャンセル". [ASSUMPTION: wording] Saved immediately to DataStore.
2. 送信者名: field "送信者名（例: お父さん）".
3. メール設定 (SMTP) [dirty handling: pressing back (or the app bar arrow) with unsaved changes in 送信者名/SMTP fields shows the confirm dialog "保存していない変更があります。破棄しますか？" with buttons "破棄" (leave, changes discarded) and "戻る" (stay). [ASSUMPTION]]: "送信元メールアドレス", "アプリパスワード", "SMTPホスト", "SMTPポート"; one "保存" button for sections 2-3 (button enabled when the form is dirty and valid).
4. ボタンの大きさ: radio group 標準 / 大 / 特大; saved immediately.
5. テスト送信 (9.5).
6. 未送信一覧 (opens 9.6), 送信ログ (opens 9.7).

### 9.2 App password field
- Not saved yet: empty masked field with a show/hide toggle; paste allowed; all whitespace (spaces, newlines) removed from the input on change.
- Already saved: field shows `●●●●●●●●●●●●●●●●` (disabled) and a "変更" button; tapping 変更 clears the field to an empty editable masked field. The saved value is never revealed. Leaving the field untouched keeps the saved password.

### 9.3 Validation (on 保存; show error text under the field; do not save if any error)
- Recipient: display name non-empty after trim ("表示名を入力してください") and at most 10 characters ("10文字以内で入力してください"); email trimmed before validation; email must match a standard email pattern (`android.util.Patterns.EMAIL_ADDRESS`) ("メールアドレスの形式が正しくありません"); no duplicate check; max 6.
- 送信元メールアドレス: valid email format.
- アプリパスワード: after removing whitespace, exactly 16 characters ("16文字で入力してください"). Required when no password is saved.
- SMTPホスト: non-empty after trim.
- SMTPポート: integer 1-65535.
- 送信者名: no validation (may be empty); trimmed; CR/LF removed on save.

### 9.4 After saving SMTP fields
- Persist, then run resume (7.5) for ATTENTION items with reasons AUTH, PERMANENT, RETRY_EXPIRED.

### 9.5 Test send
- Section: recipient dropdown (selection is transient UI state held in the ViewModel / `rememberSaveable`, NOT persisted in DataStore) [ASSUMPTION] (from the recipient list; button disabled with hint "送り先を登録してください" when empty) and button "テスト送信". Disabled while the SMTP form is dirty (hint "先に保存してください").
- Sends the text-only test mail (5) using saved settings, `Dispatchers.IO`, same timeouts, 60 s cap; shows a modal progress dialog "送信中..." (no cancel; back ignored; test button disabled meanwhile) [ASSUMPTION], then a result dialog with an "OK" button:
  - Success: "送信できました" (log TEST_OK; triggers resume per 7.5).
  - AUTH: "ログインできませんでした。送信元アドレスとアプリパスワードを確認してください"
  - NETWORK/TIMEOUT: "ネットワークに接続できませんでした"
  - REJECTED: "宛先アドレスが受け付けられませんでした"
  - Other: "送信に失敗しました（{code}）"
  - Logged as TEST_FAILED with the code.

### 9.6 Unsent list (未送信一覧)
- Two groups: "送信待ち" (WAITING items) and "要確認" (ATTENTION items, max 10). Row: thumbnail (decoded from the outbox file, about 96 dp), recipient name (or "（削除された宛先）"), capture time `yyyy/MM/dd HH:mm`, state label (待機中 / 要確認) and, for 要確認, the reason in Japanese: AUTH "ログイン失敗", PERMANENT "送信拒否", RETRY_EXPIRED "24時間再試行しても送れませんでした", RECIPIENT_DELETED "宛先が削除されました". [ASSUMPTION: reason labels]
- Buttons per row: "今すぐ再送" (enabled per 7.5; for 待機中 items it triggers an immediate worker run and restarts the 24 h window) and "削除" (confirm dialog "削除しますか？").
- Items in CANCEL_WINDOW, SENDING_FG, or SENDING_BG are shown as 待機中 with both buttons disabled.
- Thumbnails: decoded asynchronously at about 96 dp; while loading a gray placeholder box; if the file is missing or undecodable a placeholder with the text "画像なし" (the row stays, only 削除 is enabled). The list updates live from the Room Flow. [ASSUMPTION]
- Empty text: "未送信の写真はありません".

### 9.7 Send log
- Newest first, up to 50 rows: time `yyyy/MM/dd HH:mm:ss`, recipient name, result text (送信成功 / 失敗 / オフラインのため保留 / 削除 / 削除（上限超過） / 再送要求 / テスト成功 / テスト失敗), short code. No images. Empty state text: "送信ログはありません". No clear-log button. [ASSUMPTION] "再送要求" entries come from 今すぐ再送 taps (7.5).

## 10. Notifications, haptics, sound

### 10.1 Notification
- Channel id `attention`, name "送信できていない写真", importance DEFAULT, created in `Application.onCreate` (idempotent). Single notification id 1001 (replaced, not stacked). Small icon: drawable resource `ic_stat_photo` (monochrome vector). [ASSUMPTION]
- Tap: `PendingIntent.getActivity` (FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT) to `MainActivity` with `FLAG_ACTIVITY_REORDER_TO_FRONT | FLAG_ACTIVITY_SINGLE_TOP`: brings the existing task to the front without changing the current screen (no deep link; the family still uses the 3 s long press to reach settings). [ASSUMPTION]
- Dismissal: the notification is cancelled (`cancel(1001)`) when the 要確認 count becomes 0 (observed by `OutboxRepository`). [ASSUMPTION]
- Fires when an item becomes 要確認 (any reason: auth, permanent, retry expired, recipient deleted). Text: "写真が送れていません。家族に連絡してください". If that transition caused an overflow deletion (7.8): "写真が送れていません。古い写真1枚を削除しました。家族に連絡してください". No technical detail.
- Permission: `POST_NOTIFICATIONS` on API 33+. Requested once (flag `notif_permission_asked`) the first time the Camera screen is shown after camera permission has been resolved. [ASSUMPTION: timing] If denied, only the badges are shown. On API < 33 no request.
- No notification for success, queued notice, or successful later delivery.

### 10.2 Success feedback
- On entering Sent: vibration one-shot 300 ms (`VibrationEffect.createOneShot`, DEFAULT_AMPLITUDE) and a short sound (`ToneGenerator(STREAM_NOTIFICATION, 80)` `TONE_PROP_ACK`, about 200 ms) together. [CONFIRMED by the user: durations and tone] In silent/vibrate ringer mode (`AudioManager.ringerMode != RINGER_MODE_NORMAL`) only the vibration is performed; the sound is not forced. [CONFIRMED by the user, Q49]
- Queued notice and later (worker) deliveries: silent, no vibration.
- Screen-keep-on is NOT adopted.

## 11. Module layout, manifest, build notes

### 11.1 Single module `app`, package `com.example.parentphoto` [CONFIRMED by the user: package name]
- `ui/` — `MainActivity`, `MainViewModel`, screens (`CameraScreen`, `PreviewScreen`, `CancelWindowScreen`, `SendingScreen`, `SentScreen`, `QueuedNoticeScreen`, `SetupRequiredScreen`, `PermissionDeniedScreen`, `settings/*`), theme.
- `camera/` — CameraX binding, orientation listener, `ImageProcessor` (resize/rotate).
- `mail/` — `MailComposer`, `SmtpSender`, `ErrorClassifier`, `MailResult`.
- `outbox/` — Room entities/DAOs, `OutboxRepository`, `OutboxWorker`, `OutboxScheduler`.
- `settings/` — `SettingsRepository` (DataStore), `PasswordCrypto` (Keystore), `Recipient`, validators.
- `notify/` — `AttentionNotifier`, `Feedback` (vibration/sound).
- `util/` — `TokyoTime` formatter, connectivity check.
- `ParentPhotoApp` (Application class, registered with `android:name` in the manifest): creates the notification channel, initializes Room/WorkManager lazily.

### 11.2 Manifest
- Permissions: `INTERNET`, `CAMERA`, `POST_NOTIFICATIONS`, `VIBRATE`, `ACCESS_NETWORK_STATE` [ASSUMPTION: needed for the offline shortcut check].
- `<uses-feature android:name="android.hardware.camera" android:required="true"/>`.
- Activity: `screenOrientation="portrait"`, `exported=true` (launcher), `windowSoftInputMode="adjustResize"`.
- `android:allowBackup="false"`; `minSdk 26`.

### 11.3 Dependencies
- CameraX (core, camera2, lifecycle, view), Compose BOM + Material3, DataStore Preferences, Room (runtime, ktx, ksp compiler), WorkManager (ktx), Kotlin Coroutines, `com.sun.mail:android-mail`, `com.sun.mail:android-activation`.

### 11.4 R8 / packaging
- If `minifyEnabled true` for release, add keep rules: `-keep class javax.mail.** { *; }`, `-keep class com.sun.mail.** { *; }`, `-keep class javax.activation.** { *; }`, `-keep class com.sun.activation.** { *; }`, and `-dontwarn` for `java.awt.**`, `java.beans.**`, `javax.security.sasl.**`, `javax.naming.**`, `org.apache.harmony.**`.
- Packaging: add `packaging { resources { pickFirsts += ["META-INF/mailcap", "META-INF/mimetypes.default", "META-INF/mailcap.default"] } }` if duplicate-resource errors occur (mailcap registration must remain so `image/jpeg` and `multipart/mixed` data handlers work).
- Also exclude the duplicate license files: `packaging { resources { excludes += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md") } }` (android-mail and android-activation both ship them; the build fails otherwise). [VERIFIED by spike, `docs/plans/smtp-send-spike/RESULT.md`]
- The keep rules above are REQUIRED: without them `msg.writeTo` fails with `no object DCH for MIME type multipart/mixed` in the R8 release build. The verified rule set is `spike/app/proguard-rules.pro`. [VERIFIED by spike]
- Verify with a real release build: send a photo mail from a release APK on API 26+ before closing the feature.
- Release artifact name includes date-time (e.g., `ParentPhoto_YYYYMMDD-HHMMSS.apk`) per global convention.

## 12. Acceptance checklist (numbering follows spec.md sections; "2-1" = spec section 2, item 1)
- [x] FIRST TASK: spike (6.0) passed on API 26-level and Android 14/15 with an R8 release build (multipart mail with JPEG attachment received).
- [ ] 1 Overview: no server component; SMTP direct on Dispatchers.IO; portrait-locked UI; landscape-held shot gives a landscape photo.
- [ ] 2-0 Launch: Loading state until settings/recovery ready (no SetupRequired flash); `cache/preview.jpg` and raw file deleted at cold start; Preview never restored.
- [ ] 2-1 Camera: launches to full-screen preview; one standard-size shutter; Capturing state; capture/resize/camera-init failure returns to camera with "撮影できませんでした。もう一度お試しください" and a Logcat entry; permission denied shows guidance + "設定を開く"; permission dialog once per cold start; settings icon visible on Camera and Preview; photo kept when returning from settings.
- [ ] 2-2 Preview: やり直す deletes the photo and returns to camera; one button per recipient in registration order (max 6, name max 10 chars, wraps to 2 lines, scrollable); 0 recipients shows "送り先が登録されていません"; no send-to-everyone.
- [ ] 2-3 Sending: 3 s "取り消す" window (3-2-1 countdown; cancel returns to Preview with the photo kept); "送信中..." with all buttons disabled; success "送りました！" about 2 s with vibration and sound (vibration only in silent mode); failure queues and returns via "あとで送ります" (no retry button); no cause shown; codes in Logcat only; first attempt capped at 60 s; offline skips the attempt; timers behave per 2.3 when backgrounded.
- [ ] 2-3 Badges and full state: "未送信 N件" for active, "送れていません M件" for 要確認; at 5 active items shooting blocked with the guidance text; 要確認 items never block shooting; no 6th active item ever (Preview reserves a slot).
- [ ] 2-4 Setup: unset/invalid address, password, host or port shows "設定が必要です" without a button; settings only via 3 s long press.
- [ ] 2-5 Sizes: text 28/36 sp fixed; button levels 56/96/128 dp, default 大, applied to やり直す, recipient buttons, 取り消す, 設定を開く; shutter and settings icon unaffected.
- [ ] 3 Settings: recipient CRUD (max 6; name max 10 chars; email format; empty name rejected; delete warning mentions queued photos); sender name (used in body and From, CR/LF stripped); SMTP address/password (masked, 16 chars after removing spaces, never re-displayed)/host/port with defaults; button size; test send (progress dialog, Japanese result dialog); dirty-form back confirmation; 未送信一覧 (SENDING_BG rows disabled, thumbnail placeholders); send log of 50 with empty state.
- [ ] 3 Storage: non-secret settings in DataStore; password encrypted with Keystore AES-GCM; backup disabled.
- [ ] 4 Send: resize 1600 px / JPEG 85 right after capture; From "sender name <address>"; subject/body/file names per section 5; Asia/Tokyo; timeouts 15/30/60 s; port 465 SSL default; classification per 6.3.
- [ ] 5 Outbox: failure queues the photo; WorkManager network constraint, 15 min exponential backoff, stops after 24 h -> 要確認 (photo kept); auth/permanent -> 要確認 immediately; batching per recipient with 15 MB split; settings read at send time; recipient edit uses new address, delete -> 要確認; 要確認 cap 10 with oldest deleted on overflow and logged; resume on settings save / test success / 今すぐ再送 (waits if no slot; promoted oldest-first by OutboxRepository; manual tap writes RESEND_REQUESTED and restarts the 24 h window); duplicates tolerated; startup recovery sends killed-in-flight items.
- [ ] 5 Notification: channel created at app start; shown only on becoming 要確認; overflow wording when the oldest photo was removed; tap brings the existing task to front; cancelled when 要確認 count is 0; permission requested once on API 33+.
- [ ] 6 Worker safety: triggers never cancel a RUNNING worker; items set to SENDING_BG before send; cancellation leaves items QUEUED; back handlers ignore back in Preview/CancelWindow/Sending/Sent/QueuedNotice (Capturing and Loading also).
- [ ] 7 Unresolved: none; all Q1-Q50 reflected.
- [ ] Release: R8 build sends mail correctly; manifest has INTERNET, CAMERA, POST_NOTIFICATIONS, VIBRATE, ACCESS_NETWORK_STATE.
