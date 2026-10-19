# outbox-queue PLAN-2: 要確認 transitions, overflow, notification hooks, recovery, recipient deletion

Source: ds 7.5 (recipient edit/delete), 7.6, 7.7, 7.8, 10.1 (triggers and dismissal), 7.4 steps 2-3 (state changes only). Package `outbox/`.

## Index
1. `moveToAttention`
2. Overflow (cap 10)
3. Notification call sites and observers
4. Startup recovery and the recovery gate
5. Recipient edit and deletion
6. Worker-support calls (expiry, setup invalid)
7. Manual delete
8. Duplicates
9. Tests

## 1. `moveToAttention(ids, reason): AttentionOutcome`
Internal (used by PLAN-1 `markQueued`, expiry, recipient deletion, batch results). One `withTransaction`:
1. For each row that still exists and is WAITING: `state = ATTENTION`, `attentionReason = reason`, `attentionSinceMillis = now`, `resendPending = false`, `phase = QUEUED` (placeholder). Rows already deleted are skipped.
2. Apply the overflow rule (section 2) once after all rows of the call are moved.
3. Commit, then delete files of overflow-deleted rows, write `DELETED_OVERFLOW` log lines, and call `notifier.show(overflowDeleted = outcome.overflowDeleted > 0)` exactly once per call (not once per item). If nothing moved (all ids gone), no notification.
`AttentionOutcome(moved: Int, overflowDeleted: Int)`. The caller writes its own FAILED log line (PLAN-1 section 5) before step 3's notification so the log order reads "FAILED, then DELETED_OVERFLOW".

## 2. Overflow (ds 7.8)
- While `ATTENTION count > ATTENTION_CAP (10)`: pick the ATTENTION row with the smallest `attentionSinceMillis` (ties: smaller `capturedAtMillis`, then `id`), delete it (row now, file after commit), remember its recipient name (resolved from settings; null when gone) for the log line. This is the only automatic deletion in the app.
- Log `DELETED_OVERFLOW` with the recipient name; no capture time needed.
- Notification text: overflow wording if any row was deleted by this call (section 3). Across separate calls the last notification wins (id 1001 is replaced, not stacked); this is accepted and not a spec question.
- A row with `resendPending = true` is still ATTENTION and is subject to overflow like any other.

## 3. Notification call sites and observers
- Show: only inside `moveToAttention` (any reason). No notification for success, QueuedNotice, offline, or later successful delivery.
- Cancel: an application-scope collector in `OutboxRepository.startObservers(appScope)`: `attentionCount.distinctUntilChanged().collect { if (it == 0) notifier.cancel() }`. The initial 0 at start is harmless (`cancel(1001)` is idempotent). This collector lives in `ParentPhotoApp`'s scope so it also works when only the worker runs.
- `AttentionNotifier` is injected as an interface (`show(overflowDeleted)`, `cancel()`); real class from attention-notifications, fake in tests.

## 4. Startup recovery and the recovery gate (ds 7.6)
Runs once per process, started from `ParentPhotoApp.onCreate` on the application scope (not from the ViewModel) so that a process started by WorkManager is also covered, and finishes before any worker run uses the DB.
1. Transaction: `UPDATE outbox_item SET phase='QUEUED' WHERE state='WAITING' AND phase IN (CANCEL_WINDOW, SENDING_FG, SENDING_BG)`. (There is no in-process owner of such rows at process start. A kill during the undo window sends the photo.)
2. List `outbox/` files: delete files whose id has no row (includes `*.tmp`). Delete rows (any state) whose file does not exist.
3. If any `WAITING / QUEUED` row remains: `scheduler.enqueueDefault()`.
4. Then run `promote()` once (PLAN-3) so a `resendPending` item left across a restart can proceed.
5. Set `recoveryDone = true` (`MutableStateFlow`); `awaitRecovery()` suspends until then.
- Contract for consumers: `MainViewModel` keeps Loading until `recoveryDone` (photo-send-flow PLAN-1 section 2 "recovery-finished signal"); the outbox worker calls `awaitRecovery()` first (outbox-worker PLAN-1 section 2) so it never sees or resets rows it does not own.
- Recovery failure (exception): log to Logcat, still set `recoveryDone = true` so the app does not hang in Loading; the next cold start retries.
- The cold-start deletion of `cache/preview.jpg` and `cache/capture_raw.jpg` stays in photo-send-flow.

## 5. Recipient edit and deletion (ds 7.5)
- Edit: nothing to do; the address is read by `recipientId` at send time.
- `onRecipientDeleted(recipientId)`: called by settings-screen PLAN-2 AFTER `SettingsRepository.deleteRecipient` succeeded. Transaction: every row of that recipient with `state = WAITING AND phase = QUEUED` -> `moveToAttention(ids, RECIPIENT_DELETED)` (including overflow and notification, section 1). Rows in `CANCEL_WINDOW`, `SENDING_FG`, `SENDING_BG` are left alone: they finish with the address read at attempt start; if the attempt then fails transiently they become QUEUED and the next worker run (or the sender seeing no recipient) moves them to RECIPIENT_DELETED. Existing ATTENTION rows keep their original reason.
- Why this order: DataStore and Room cannot share one transaction. Deleting the recipient first is safe because every consumer already handles a missing recipient (worker step 4 / `RecipientMissing`), so a crash between the two calls loses nothing; the opposite order could leave RECIPIENT_DELETED items for a recipient that still exists. The effect is the same as the spec's "same transaction".
- `countForRecipient(recipientId)` for the delete dialog counts rows of any state of that recipient (WAITING or ATTENTION, ds 9.1).
- `RECIPIENT_DELETED` items are never resumable (PLAN-3) and can only be deleted. Re-adding a recipient does not restore the link (new UUID).
- Known accepted behavior: an ATTENTION item (e.g. AUTH) of a recipient deleted later keeps its reason; if it is resumed, the next send finds no recipient and it becomes RECIPIENT_DELETED then.

## 6. Worker-support calls (state changes only; the loop is outbox-worker)
- `expireOld(now): Int`: for each `WAITING / QUEUED` row with `now - retryStartMillis > RETRY_WINDOW_MS (24 h)` -> `moveToAttention(ids, RETRY_EXPIRED)`. Photos are kept. Returns the count. Only QUEUED rows expire; rows in other phases are never touched.
- `markAllQueuedAttention(reason = AUTH)`: used when SetupRequired holds at worker time (ds 7.4 step 3): all `WAITING / QUEUED` rows -> `moveToAttention(.., AUTH)`; single notification.
- `markRecipientMissing(ids)`: `moveToAttention(ids, RECIPIENT_DELETED)` for a batch whose recipient id is not in the list.

## 7. Manual delete (ds 7.8, 9.6)
`deleteManual(itemId)`: only allowed for rows that are not in `CANCEL_WINDOW / SENDING_FG / SENDING_BG` (UI disables the button; the repository ignores such calls to be safe). Delete row + file, log `DELETED_MANUAL`. If the deleted row was the last ATTENTION row the `attentionCount` collector cancels the notification (section 3).

## 8. Duplicates (ds 7.7, D14)
No dedupe code exists or is added. Ambiguous failures (timeout after DATA, `Exception reading response`) are retried; the family may receive the same photo twice. Document this in the class KDoc of `markQueued` / worker so nobody "fixes" it.

## 9. Tests
- Room/JVM with fakes: `moveToAttention` with 10 existing + 1 new deletes the oldest by `attentionSinceMillis`, logs `DELETED_OVERFLOW`, notifies once with the overflow flag; 12-at-once case; tie-breaking; ids already deleted are skipped; no notification when nothing moved.
- Cancel collector: count 1 -> 0 cancels; stays shown while count > 0.
- Recovery: seeded rows in each phase become QUEUED; orphan file and `.tmp` deleted; row without file deleted; scheduler called only when QUEUED exists; `recoveryDone` set even when an exception is thrown; `awaitRecovery()` blocks until done.
- `onRecipientDeleted`: QUEUED rows -> RECIPIENT_DELETED, in-flight phases untouched, other recipients untouched; `countForRecipient` counts both states.
- `expireOld`: 24 h boundary (exactly 24 h stays, +1 ms expires), photo and row kept, only QUEUED rows affected.
