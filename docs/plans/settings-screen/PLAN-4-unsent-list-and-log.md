# settings-screen PLAN-4: unsent list and send log

Source: ds 9.6, 9.7, 7.5. Package `ui/settings/`. BLOCKED on pending plans outbox-queue / outbox-worker for data (Room Flow, send log table, resume); build UI against fakes of the `OutboxRepository` list flows and log flow.

## Index
1. Unsent list
2. Send log
3. Tests

## 1. Unsent list (未送信一覧)
- Groups: "送信待ち" (WAITING) and "要確認" (ATTENTION, max 10). Row: thumbnail (decoded asynchronously from the outbox file at about 96 dp; gray placeholder while loading; "画像なし" placeholder if missing/undecodable, then only 削除 enabled), recipient name or "（削除された宛先）", capture time `yyyy/MM/dd HH:mm` (Asia/Tokyo), state label 待機中 / 要確認, and for 要確認 the reason: AUTH ログイン失敗, PERMANENT 送信拒否, RETRY_EXPIRED 24時間再試行しても送れませんでした, RECIPIENT_DELETED 宛先が削除されました.
- Buttons: "今すぐ再送" (enabled per ds 7.5; disabled for RECIPIENT_DELETED; for 待機中 triggers immediate worker run and restarts the 24 h window) and "削除" with confirm "削除しますか？". Items in CANCEL_WINDOW, SENDING_FG, SENDING_BG show as 待機中 with both buttons disabled.
- Live updates from the Room Flow. Empty text "未送信の写真はありません".

## 2. Send log (送信ログ)
Newest first, max 50 rows: time `yyyy/MM/dd HH:mm:ss`, recipient name, result text (送信成功 / 失敗 / オフラインのため保留 / 削除 / 削除（上限超過） / 再送要求 / テスト成功 / テスト失敗), short code. No images, no clear button. Empty text "送信ログはありません". "再送要求" rows come from 今すぐ再送 taps.

## 3. Tests
Compose tests with fake lists: group split, reason labels, disabled states per phase and reason, missing-file placeholder, empty states, 50-row cap display, time formatting in Asia/Tokyo with a non-Tokyo device zone.
