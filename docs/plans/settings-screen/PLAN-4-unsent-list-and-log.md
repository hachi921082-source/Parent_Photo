# settings-screen PLAN-4: unsent list and send log

Source: ds 9.6, 9.7, 7.5, 9 intro. Package `ui/settings/`. BLOCKED on pending plans outbox-queue / outbox-worker for data (Room Flow, send log table, resume); build UI against fakes of the `OutboxRepository` list flows and log flow.

## Index
1. Unsent list
2. Send log
3. Tests
4. Implementation notes

## 1. Unsent list (未送信一覧; route `settings/unsent`, app bar title 未送信一覧)
- Two groups in this order: "送信待ち n件" (WAITING items; oldest capture time first) and "要確認 n件/10" (ATTENTION items; ascending `attentionSinceMillis`, same as the promotion order). An empty group's header is hidden. The group name "送信待ち" and the state label "待機中" are both kept.
- Row layout: thumbnail (96 dp square, `ContentScale.Crop`) on the left; texts to its right; the two buttons in a row beneath the texts. Texts: recipient name or "（削除された宛先）", capture time `yyyy/MM/dd HH:mm` (Asia/Tokyo), state label 待機中 / 要確認 and, for 要確認, the reason: AUTH ログイン失敗, PERMANENT 送信拒否, RETRY_EXPIRED 24時間再試行しても送れませんでした, RECIPIENT_DELETED 宛先が削除されました.
- Thumbnail: decoded asynchronously from the outbox file at 96 dp; gray placeholder box while loading; "画像なし" placeholder if missing/undecodable (the row stays, only 削除 enabled).
- Buttons: "今すぐ再送" (enabled per ds 7.5; disabled for RECIPIENT_DELETED; for 待機中 triggers an immediate worker run and restarts the 24 h window) and "削除" -> confirm dialog "削除しますか？" with buttons "削除" / "キャンセル" (outside tap/back = キャンセル). Items in CANCEL_WINDOW, SENDING_FG, SENDING_BG show as 待機中 with both buttons disabled.
- 今すぐ再送 feedback: only when no slot is free, a Material3 `Snackbar` (`SnackbarDuration.Short`, no action) "順番待ちです。しばらくお待ちください" (`Waiting` result). No other feedback when a resend starts (the row simply becomes 待機中). The screen has its own `SnackbarHost`. Each manual tap writes `RESEND_REQUESTED`.
- Live updates from the Room Flow. Empty text "未送信の写真はありません" (only when both groups are empty).

## 2. Send log (送信ログ; route `settings/log`, app bar title 送信ログ)
Newest first, max 50 rows. Row: first line = time `yyyy/MM/dd HH:mm:ss` (Asia/Tokyo) and recipient name; second line = result text and short code. Result text mapping:

| Log result | Text |
|---|---|
| SENT | 送信成功 |
| FAILED | 失敗 |
| QUEUED_OFFLINE | オフラインのため保留 |
| DELETED_MANUAL | 削除 |
| DELETED_OVERFLOW | 削除（上限超過） |
| RESEND_REQUESTED | 再送要求 |
| TEST_OK | テスト成功 |
| TEST_FAILED | テスト失敗 |

The short code is shown only for FAILED and TEST_FAILED; blank otherwise. A batch mail of N photos writes ONE entry (writer side: mail-composition-smtp / outbox-worker). Test entries show the chosen recipient name. No images, no clear button. Empty text "送信ログはありません". 再送要求 rows come from 今すぐ再送 taps.

## 3. Tests
Compose tests with fake lists: group order and headers ("送信待ち n件", "要確認 n件/10"), empty group header hidden, sort orders, reason labels, row layout (thumbnail 96 dp left, buttons beneath), disabled states per phase and reason, missing-file placeholder, delete dialog buttons and キャンセル dismissal, Snackbar for Waiting only, empty states, every log mapping row incl. code only for FAILED/TEST_FAILED, 50-row cap display, one entry per batch mail (fake), time formatting in Asia/Tokyo with a non-Tokyo device zone.

## 4. Implementation notes
Code: `ui/settings/UnsentAndLog.kt` (port `UnsentOutbox`, grouping, button rules, label mappings), `UnsentScreen.kt` (list, rows, async thumbnails, delete dialog), `SendLogScreen.kt`, handlers in `SettingsViewModel.kt`, wiring in `SettingsViewModelFactory.kt` and `SettingsNavHost.kt` (former `TODO(PLAN-4)`; `UnsentRoute` owns the screen's `SnackbarHost`).
- Not blocked any more: uses the real `OutboxRepository.items` (the DAO already orders WAITING by capture time, then ATTENTION by `attentionSinceMillis`), `sendLog` (newest 50), `resendNow` and `deleteManual`. The UI only partitions the list; it does not re-sort.
- "One entry per batch mail" is a writer-side property (outbox-worker), not re-tested here; the log screen shows whatever rows exist.
- Flows are collected with `WhileSubscribed(5 s)` so the Activity-scoped ViewModel does not observe Room while settings is closed. Delete-confirm state and pending Waiting events are cleared by `onClosed()`.
- Rapid 今すぐ再送 taps are not debounced in the UI: the row becomes 待機中/送信中 and the button disables itself once the worker claims the item; repeated taps before that go to `resendNow` (outbox-worker PLAN-2 section 5 check 4 confirmed no overlap, see below).
- Thumbnails: `BitmapFactory` with `inSampleSize` (power of two, short side >= 96 dp in px) on `Dispatchers.IO`; missing/undecodable -> "画像なし" and 今すぐ再送 disabled (削除 stays). Placeholder color `#E0E0E0` [provisional].
- Provisional choices (reported as questions): 要確認 rows show the state label and the reason on two separate lines (reason in `onSurfaceVariant`); buttons are outlined; rows separated by dividers; the log row puts time and name on one line with 12 dp spacing, result and code on the next; null recipient name in the log shows "（削除された宛先）" (ds 6.4); no title on the delete dialog.
- Device check outbox-worker PLAN-2 section 5 item 4 (emulator-5570, host 10.255.255.1): two QUEUED items for two recipients; five rapid taps on item A, then three on item B. A: first tap `triggerNow running=false` (REPLACE of the backoff work), the worker claimed A, later taps had no effect (button disabled). B (still QUEUED while A was sending): three `triggerNow running=true` -> one `outbox-send-next` enqueued (KEEP), whose run started only after the running one ended (gate); sends were strictly sequential; the RUNNING work was never cancelled.
- Device check item 3 (partial, no real server): two rows set to ATTENTION/AUTH directly in `outbox.db` (sqlite3 via run-as, app stopped; no source change); 未送信一覧 showed "要確認 2件/10" with ログイン失敗; changing the password and 保存 logged `resumeAfterSetupFix marked=2 promoted=2`, `triggerNow running=false`, worker run started. Remaining for acceptance: a real wrong password -> AUTH from the server -> 要確認 + notification -> fix -> real delivery. Side effect of the sqlite3 edit: after a later reinstall the database came up empty (startup recovery then removed 2 orphan photo files); an untouched reinstall with a queued item kept the row (rechecked), so this is attributed to editing the live WAL database with the CLI, not to the app.
- Known, not fixed (API 26 emulator): pressing キャンセル (or 保存 with the keyboard just hidden) while the password field in the CHANGING state has focus moves focus to the 送信者名 field and opens the keyboard. Tried: clearing focus before/after the change, a focus sink, blocking focus enter on the screen (this broke tapping into fields and was reverted). Likely the pre-API-28 touch-mode refocus rule; to be checked on the real Android 16 device in acceptance.
