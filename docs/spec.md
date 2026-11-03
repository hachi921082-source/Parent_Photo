# Specification

<!-- Confirmed specification. Built up collaboratively with designer-lite; records only "what is currently decided." Architecture: Android app only, direct SMTP (GAS dropped; see DECISIONS.md D9). -->

## Index
1. Overview
2. Main Screen (Father)
3. Hidden Settings Screen (Administrator)
4. Send Behavior (SMTP)
5. Outbox (failed sends)
6. Android App Scope
7. Unresolved Items

## 1. Overview
- One-touch photo sending app for elderly people who find sending photos from a smartphone hard.
- Single component: Android app (Kotlin / Jetpack Compose). No server side.
- The app sends mail directly over SMTP using JavaMail (com.sun.mail:android-mail), in the background (Coroutines, Dispatchers.IO).
- UI text is Japanese. App name: FamilyPhoto (launcher label; package stays com.example.parentphoto).
- Min Android API 26. UI is portrait-locked; captured photo orientation follows how the device is held.
- Details: `docs/detailed-spec.md`.

## 2. Main Screen (Father)
Flow: Camera -> Preview/Select -> Undo window (3 s) -> Sending -> Sent -> Camera. Failure branch: Sending -> queued in the outbox -> Camera. Camera permission denied: guidance screen. Required settings unset: dedicated display.

1. Camera screen
   - Shown immediately on app launch; full-screen camera preview.
   - One shutter button (standard size); tap takes a photo.
   - A small settings icon is shown in a corner (also on the Preview/Select screen). When returning from the settings screen, the current photo is kept.
   - If capture, resize, or camera initialization fails: back to the camera screen with the message "撮影できませんでした。もう一度お試しください" (error logged to Logcat).
   - A photo left on the preview screen is never restored after the app is restarted; it is deleted at app start.
   - If camera permission is denied: Japanese guidance screen with a large button that opens the app's system settings.
2. Preview/Select screen
   - Shows the captured photo.
   - Large buttons at the bottom:
     - "やり直す": deletes the captured image from the phone completely, sends nothing, returns to the camera screen immediately.
     - Recipient buttons: one large button per registered recipient, labeled with the display name (e.g., "〇〇（息子）"). Scrollable list in registration order; maximum 6 registered recipients; display name max 10 characters, wraps to at most 2 lines.
   - No "send to everyone" button.
   - 0 registered recipients: "送り先が登録されていません" is shown instead of buttons.
3. Sending
   - Tapping a recipient button starts a 3-second "取り消す" (undo) window; otherwise one touch, no dialog. During the window the other buttons are disabled; a large "取り消す" button (father button size level) shows "3秒後に送ります" with a 3-2-1 countdown. Tapping it cancels the send and returns to the preview screen with the photo kept. After the window, "送信中..." is shown and all buttons are disabled while sending. The photo is written to the outbox store at the start of the window so it survives an app kill.
   - The first attempt runs in the foreground (60 s cap). If it fails the photo is queued; if no network is connected, it is queued immediately without an attempt.
   - On success: "送りました！" shown clearly for about 2 seconds with a short vibration and a short sound (vibration only when the phone is in silent mode), then the app returns to the camera screen.
   - On failure: the resized photo is saved in the outbox (section 5) and the app returns to the camera screen immediately. There is no retry button; the father does not retry manually. The father sees "あとで送ります" for about 2 seconds (silent), then the camera with a corner badge "未送信 N件" (active items) while items remain; items needing the family's attention are shown by a separate warning-colored badge "送れていません M件" (shown only while M > 0). A later delivery is silent and the badge decreases. Error codes go to Logcat only.
   - Outbox full (5 active items; items in the undo window, foreground sending, or waiting count; "要確認" items do not count): the camera screen shows "送れていない写真が5枚あります。ネットワークを確認してください" and the shutter is disabled; it unblocks automatically when active items are delivered or deleted by the family. The active count never exceeds 5.
4. Unset configuration
   - When the sender address, app password, SMTP host, or SMTP port is empty or invalid, a dedicated "設定が必要です" display is shown. No button; settings are opened only by the 3 s long press on the settings icon.
5. Sizes
   - Text on father-facing screens is large and fixed (buttons 28sp, messages 36sp), independent of the system font-size setting.
   - Size of "やり直す", recipient buttons, the "取り消す" button, and the camera-permission "設定を開く" button is changeable in the hidden settings screen: 3 levels 標準 56 dp / 大 96 dp / 特大 128 dp, default 大. The shutter button (standard size) and the corner settings icon are not affected.

## 3. Hidden Settings Screen (Administrator)
- Opened by a 3-second long press on a small settings icon placed in a corner of the main screen. No PIN.
- Uses standard-size UI.
- Settings:
  - Recipient management: add, edit, delete (max 6). Fields: display name (e.g., 太郎; max 10 characters), destination email address. Email format validated; empty name rejected; no duplicate check; order fixed to registration order.
  - Sender name (e.g., "お父さん"), used in the mail body and as the From display name (CR/LF stripped).
  - SMTP settings:
    - Sender email address (e.g., father@gmail.com).
    - Sender app password (16-character Google app password). Input masked with a show/hide toggle; once saved, never displayed again (dots and a "変更" button only); paste allowed, spaces ignored. Saving is rejected unless exactly 16 characters remain after removing spaces.
    - SMTP host (default smtp.gmail.com), editable.
    - SMTP port (default 465, SSL), editable; validated as a number 1-65535.
  - "テスト送信" button: sends a text-only mail to a chosen recipient and shows the result or error reason in Japanese (administrator only).
  - Father-facing button size.
- Non-secret settings are stored locally in Android DataStore; the recipient list generates the recipient buttons.
- The app password is encrypted with an Android Keystore key (AES-GCM, e.g. via Tink); the ciphertext is stored in DataStore and excluded from Android backup.
- Sender account: the father's own Gmail (2-step verification on, app password issued once by the family). Sent mail also appears in that account's Sent folder.

## 4. Send Behavior (SMTP)
- Image: resized to long edge 1600 px, JPEG quality 85, right after capture (before the preview is shown).
- Mail is built with JavaMail (Session / MimeMessage / Transport.send()); the image is a MIME attachment. Sent from the sender address in settings to the selected recipient's address (one recipient per send; no Cc/Bcc). From = "{sender name} <sender address>"; no Reply-To.
- Subject "写真が届きました". Body (plain text, UTF-8): "{sender}さんから写真が届きました。" + line break + "撮影日時: yyyy/MM/dd HH:mm" ({sender} = sender name setting; empty falls back to "家族"). Attachment file name `yyyyMMdd_HHmmss.jpg` from the capture time.
- Timestamps use Asia/Tokyo, formatted in the app.
- Timeouts: connect 15 s, read/write 30 s, overall cap 60 s per attempt (attempt cancelled at 60 s). Typical size well below Gmail's 25 MB limit.
- Library: com.sun.mail:android-mail + android-activation; minimal R8 keep rules; verify with a release build.
- No family shared album; photos reach the family by mail only.
- Failure display: no cause-specific message for the father; codes only in Logcat. Authentication problems are diagnosed with "テスト送信" or Logcat.
- Security: the app password sits on the phone; accepted risk, no PIN. If the phone is lost, revoke the app password in the Google account.
- The image is held on the phone only until send success, "やり直す", or (if queued) delivery/removal from the outbox; nothing is saved to any cloud storage.

## 5. Outbox (failed sends)
- On send failure the resized photo (long edge 1600 px, JPEG 85) is saved in app-private storage together with recipient and capture time.
- Queued items are sent automatically when the network is available, by WorkManager with a network-connected constraint, even if the app is closed.
- Active cap 5 items ("待機中": undo window, sending, waiting for retry). When 5 active items exist, shooting is blocked with a guidance message. "要確認" items do not count toward this cap.
- Batching: on retry, multiple queued photos for the same recipient go out in one mail with multiple attachments. Subject "写真が届きました（N枚）" (single photo: "写真が届きました"); body "{sender}さんから写真が届きました。" + line break + one line "撮影日時: yyyy/MM/dd HH:mm" per photo in capture order. Each attachment is named by its own capture time `yyyyMMdd_HHmmss.jpg` (same-second duplicates get `_2`, `_3`). Split into several mails when attachments exceed about 15 MB total (each split mail uses the same format with its own count).
- Retry policy: network-type failures (no network, timeout, connection refused) are retried by WorkManager with exponential backoff from about 15 min for up to 24 h. After 24 h the item becomes "要確認" and automatic retries stop. Authentication failures and other permanent errors become "要確認" immediately.
- "要確認" items are retried when SMTP settings are saved, when a test send succeeds, or when the family taps "今すぐ再送" (the item returns to "待機中"; if no slot is free it waits and is promoted automatically, oldest first).
- No time-based expiry: a photo stays in the outbox until delivered or deleted by the family. "要確認" has its own cap of 10; when an 11th item becomes "要確認", the oldest "要確認" item is deleted automatically and recorded in the send log ("削除（上限超過）"). This overflow is the only automatic deletion.
- Duplicates are accepted: an ambiguous failure (e.g., timeout after data was sent) is retried anyway; no dedupe.
- Sender account, password, host and port are read at send time, so newly saved SMTP settings apply to queued items. Queued items store photo, recipient id and capture time.
- Recipient edited: the new address is used. Recipient deleted: the item becomes "要確認" and is not sent.
- Hidden settings has an "未送信一覧" screen: thumbnail, recipient name, capture time, state (待機中 / 要確認; the two groups listed separately), "削除" and "今すぐ再送" buttons; plus a send log of the last 50 entries (time, recipient, result including overflow deletions, short error code); no image is kept after delivery. The father cannot touch queued items.
- Notification: an Android notification "写真が送れていません。家族に連絡してください" (no technical detail) on the father's phone only when an item becomes "要確認"; if that causes the oldest "要確認" photo to be deleted, the text is "写真が送れていません。古い写真1枚を削除しました。家族に連絡してください". The notification is cancelled when the "要確認" count becomes 0; tapping it brings the app to the front. POST_NOTIFICATIONS is requested once on API 33+; if denied, only the badge is shown.

## 6. Android App Scope
- Kotlin / Jetpack Compose.
- Full-screen camera preview and capture via CameraX.
- "やり直す": deletes the photo shown on the preview screen and returns to the camera (queued photos are not affected).
- Dynamic recipient buttons from the saved recipient list.
- Hidden settings screen (long press; DataStore plus secure storage for the app password).
- On recipient tap: undo window, then build the MIME message from the already-resized photo, send via SMTP on Dispatchers.IO; success goes to the Sent screen, failure goes to the outbox. Queued items are sent by WorkManager.

## 7. Unresolved Items
None. All questions in `docs/QA.md` (Q1-Q62) are resolved.
