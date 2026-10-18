# photo-send-flow PLAN-3: undo window, foreground send, feedback

Source: ds 2.3, 10.2, 4.3 (copy), 6.2 (referenced). Package `ui/`, `notify/Feedback`. BLOCKED in part on pending plans (outbox-queue, mail-composition-smtp); this plan defines only what the ViewModel needs from them.

## Index
1. Contracts needed from pending plans
2. Flow
3. Feedback
4. Tests
5. Steps

## 1. Contracts needed from pending plans
- `OutboxRepository.createItem(recipientId, capturedAtMillis, previewFile): ItemId` creates a row with phase CANCEL_WINDOW and copies the photo into the outbox dir (ds 7.3).
- `OutboxRepository.deleteItem(itemId)` (row + file), `setPhase(itemId, SENDING_FG)`, `markSent(itemId)`, `markQueued(itemId, result)` (classification applied in the repository/sender).
- `SmtpSender.sendForeground(itemId): MailResult` with offline shortcut (device clearly offline -> returns Offline without attempt) and 60 s cap handled by the caller via `withTimeoutOrNull(FG_SEND_TIMEOUT_MS)`.
Until implemented, use fakes. Do not define Room/mail internals here.

## 2. Flow
1. Recipient tap (active < 5 guard) -> `createItem` -> CancelWindow(n=3). One-second ticks update label 3,2,1 using elapsed realtime.
2. 取り消す -> `deleteItem`, state Preview (cached preview photo untouched).
3. After 3 s -> `setPhase(SENDING_FG)` -> Sending; run `sendForeground` on `Dispatchers.IO` inside `withTimeoutOrNull(60000)`.
4. Success -> `markSent` (row + file deleted, log SENT by the sender/repository), enter Sent: feedback once, show 2000 ms, then Camera/OutboxFull and delete `cache/preview.jpg`.
5. Failure, timeout, or offline -> `markQueued` (queued or 要確認 per classification, worker enqueued by the repository), enter QueuedNotice 2000 ms (silent), then Camera/OutboxFull and delete preview file.
- Timers and the send job live in `viewModelScope`; they keep running in background (confirmed). If the process dies, startup recovery (pending outbox-queue) handles the item.

## 3. Feedback (`notify/Feedback`)
- On entering Sent only (exactly once per entry; not replayed on return from background): `VibrationEffect.createOneShot(300, DEFAULT_AMPLITUDE)` via `Vibrator`/`VibratorManager`, and `ToneGenerator(STREAM_NOTIFICATION, 80)` `TONE_PROP_ACK` (about 200 ms; release the generator afterwards) together. If `AudioManager.ringerMode != RINGER_MODE_NORMAL`, vibration only. Permission `VIBRATE` in manifest.
- No feedback for QueuedNotice or worker deliveries.
- Screen-keep-on is not adopted.

## 4. Tests
- JVM with fakes: cancel returns to Preview and calls deleteItem; 3 s elapse transitions; success path; failure/offline path; 60 s timeout path with virtual time; no replay of feedback after background simulation.
- Device: vibration and tone occur; silent mode gives vibration only.

## 5. Steps
1. Fakes for the contracts above + ViewModel logic + tests.
2. `Feedback`.
3. Replace fakes with real implementations after the pending plans are implemented.
