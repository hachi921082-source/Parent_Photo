# outbox-queue PLAN-1: storage, counting, item creation

Source: ds 7.1-7.3, 2.4, 6.4 (send log table), 7.4 (batch size), 5 (batching). Package `outbox/`.

## Index
1. Room schema
2. DAO queries
3. Files
4. Counting
5. Item creation and foreground-path calls
6. Send log
7. Batch planner and worker-support calls
8. Tests

## 1. Room schema
Database `outbox.db`, class `OutboxDatabase` (version 1, `exportSchema = true`, schema JSON checked in under `app/schemas/`), KSP compiler.

`OutboxItemEntity` table `outbox_item`:
| Column | Type | Notes |
|---|---|---|
| `id` | TEXT PK | UUID string; also the file name |
| `recipientId` | TEXT | recipient UUID (address is never stored) |
| `capturedAtMillis` | INTEGER | |
| `retryStartMillis` | INTEGER | start of 24 h window |
| `attentionSinceMillis` | INTEGER NULL | set when ATTENTION |
| `state` | TEXT | `WAITING` / `ATTENTION` |
| `phase` | TEXT | `CANCEL_WINDOW` / `SENDING_FG` / `SENDING_BG` / `QUEUED`; meaningful only for WAITING, stored as `QUEUED` for ATTENTION rows |
| `attentionReason` | TEXT NULL | `AUTH` / `PERMANENT` / `RETRY_EXPIRED` / `RECIPIENT_DELETED` |
| `resendPending` | INTEGER (bool) | only ever true for ATTENTION rows |
| `fileSize` | INTEGER | `file.length()` at creation; used by batching |
Indexes: `state`, `recipientId`. Enums are stored by name through explicit `@TypeConverter`s.

`SendLogEntity` table `send_log`: `id` INTEGER PK autoGenerate, `timeMillis`, `recipientName` TEXT NULL (snapshot at write time; null = recipient no longer exists), `result` TEXT (`SendLogResult` name), `code` TEXT NULL.

## 2. DAO queries (`OutboxDao`, `SendLogDao`)
- `activeCount(): Flow<Int>` = `COUNT(*) WHERE state='WAITING'`; `attentionCount(): Flow<Int>` = `state='ATTENTION'`.
- `observeAll(): Flow<List<OutboxItemEntity>>` ordered `state ASC (WAITING first), CASE WHEN state='WAITING' THEN capturedAtMillis ELSE attentionSinceMillis END, id`. The repository joins recipient names from `SettingsRepository.settings` (combine) to build `OutboxItemView`.
- Single-row ops: `get(id)`, `insert`, `delete`, `updatePhase(id, phase)`.
- `queuedItems(): List<OutboxItemEntity>` = `state='WAITING' AND phase='QUEUED'`.
- `pendingResend(): List` = `state='ATTENTION' AND resendPending=1 ORDER BY attentionSinceMillis, capturedAtMillis, id`.
- `countForRecipient(recipientId)` = rows (any state) of the recipient.
- Send log: `insert`, `observeNewest(): Flow<List<SendLogEntity>>` (ORDER BY id DESC, LIMIT 50), `trim()` = `DELETE FROM send_log WHERE id NOT IN (SELECT id FROM send_log ORDER BY id DESC LIMIT 50)`.

## 3. Files
- `OutboxFiles(filesDir)`: `dir` = `filesDir/outbox/` (created lazily), `file(id)` = `{id}.jpg`, `tmpFile(id)` = `{id}.jpg.tmp`, `listFiles()`, `delete(id)`.
- Files are never in shared storage and are not media-scanned. Paths may contain non-ASCII only in the user-controlled parent (none of ours); names are UUIDs.

## 4. Counting (ds 2.4, 7.2)
- `activeCount` / `attentionCount` come straight from the Room flows, `distinctUntilChanged()`.
- `OutboxFullException` guard: `createItem` runs a transaction that counts WAITING rows and aborts if the count is >= `ACTIVE_CAP` (5), so no 6th active item can ever exist, even if the ViewModel guard fails. 要確認 items are never counted.
- `setPreviewExists(Boolean)` stores a `StateFlow<Boolean>` read only by the promoter (PLAN-3). Publisher rule (clarification for photo-send-flow PLAN-1 section 7): true while the state is Preview; on 取り消す the ViewModel sets it to true BEFORE calling `deleteItem` (so the slot freed by the cancel cannot be taken by a promotion); after `createItem` it sets false (the item itself now holds the slot).

## 5. Item creation and foreground-path calls
- `createItem(recipientId, capturedAtMillis, previewFile)`:
  1. new UUID; copy `previewFile` to `tmpFile(id)`, then atomically rename to `file(id)` (the photo survives an app kill, ds 7.3);
  2. transaction: count check (section 4), insert row `WAITING / CANCEL_WINDOW`, `retryStartMillis = now`, `fileSize = file.length()`, `resendPending = false`;
  3. on any failure after step 1, delete the file and rethrow.
  The preview file is left untouched.
- `deleteItem(itemId)` (undo): delete row, then file; no log line. `deleteManual(itemId)`: same plus log `DELETED_MANUAL` (recipient name from settings, null if recipient gone); PLAN-2 holds the shared "delete row + file" helper.
- `setPhase(itemId, phase)`: updates only a WAITING row (no-op if the row is gone or ATTENTION). Used for `SENDING_FG`.
- `markSent(itemId)`: delete row + file, log `SENT` (code null). The caller (ViewModel) then enters Sent.
- `markQueued(itemId, result)`: dispatch on `result`:
  | result | effect |
  |---|---|
  | `Offline` | phase QUEUED, log `QUEUED_OFFLINE`, `scheduler.enqueueDefault()` |
  | `Failure(TRANSIENT, code)` | phase QUEUED, log `FAILED(code)`, `scheduler.enqueueDefault()` |
  | `Failure(AUTH)` | PLAN-2 `moveToAttention([id], AUTH)`, log `FAILED(AUTH)` |
  | `Failure(PERMANENT, code)` | PLAN-2 `moveToAttention([id], PERMANENT)`, log `FAILED(code)` |
  | `RecipientMissing` | PLAN-2 `moveToAttention([id], RECIPIENT_DELETED)`, no FAILED line |
  | `Success` | `IllegalArgumentException` (caller bug) |
  Room write first, then scheduler / notifier. The foreground failure never touches `retryStartMillis` (the 24 h window starts at creation).
- `SendSource.loadForeground(itemId)` (mail-composition-smtp 2.3): returns `SendableItem(recipientId, file(id), capturedAtMillis)` or null when the row is gone.

## 6. Send log
- `SendLogWriter.record(result, recipientName, code)`: insert + `trim()` in one transaction. Called by the repository (SENT, FAILED, QUEUED_OFFLINE, DELETED_*, RESEND_REQUESTED), the worker, and `SmtpSender.sendTest` (TEST_*).
- Recipient name for outbox events: looked up from `settings.first().recipients` by id; null when missing.
- `sendLog` flow maps entities to `SendLogEntry`. Newest first, 50 rows.

## 7. Batch planner and worker-support calls (used only by outbox-worker)
- `BatchPlanner.plan(items: List<OutboxItemEntity>, maxBytes = BATCH_MAX_BYTES): List<Batch>` (pure): group by `recipientId`; inside a group sort by `capturedAtMillis`, then `id`; fill a batch until adding the next item would make the sum of `fileSize` exceed 15 MB, then start a new batch (an item that alone exceeds 15 MB still forms a batch of one). Return batches ordered by the oldest capture time of their first item. Each `Batch(recipientId, items)`.
- `claimBatch(batch)`: single transaction that sets phase `SENDING_BG` only for rows still `WAITING / QUEUED`; returns the rows actually claimed (empty = someone else changed them). The atomic claim prevents two workers from sending the same item.
- `releaseBatch(ids)`: phase back to QUEUED (cancellation, `onStopped`; no log).
- `finishBatchSent(ids, recipientId)`: delete rows + files, log one `SENT` for the mail.
- `finishBatchTransient(ids, code)`: phase QUEUED, log one `FAILED(code)`.
- `finishBatchAttention(ids, reason, code)`: PLAN-2 `moveToAttention`, log one `FAILED(code)`.
- `queuedSnapshot()`: `queuedItems()`.
(Expiry and SetupRequired handling calls are in PLAN-2 section 6.)

## 8. Tests
- Room instrumented (or Robolectric in-memory): counting flows with mixed states; `createItem` at 4 ok / at 5 throws and leaves no file; transaction rollback leaves no row; send log trims to 50; ordering of `observeAll`; `claimBatch` returns only still-QUEUED rows (second claim empty).
- JVM: `BatchPlanner` (grouping per recipient, ascending order, 15 MB boundary: exactly 15 MB stays, +1 byte splits, oversize single item, multiple recipients ordering); `markQueued` dispatch table against fakes (scheduler calls, log lines, notifier not called for TRANSIENT/Offline).
- Non-ASCII recipient names in log entries round-trip.
