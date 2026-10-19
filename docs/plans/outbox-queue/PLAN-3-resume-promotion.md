# outbox-queue PLAN-3: resume rules and active-slot promotion

Source: ds 7.5 (resume triggers, active cap), 7.4 (trigger write order), 2.4 (reservation), 9.4, 9.5, 9.6. Package `outbox/`.

## Index
1. Rules
2. `promote()`
3. Entry points
4. Observer
5. Scheduler seam
6. Tests

## 1. Rules
- Resumable 要確認 reasons for bulk resume (SMTP save, successful test): `AUTH`, `PERMANENT`, `RETRY_EXPIRED`. `RECIPIENT_DELETED` is never resumable (bulk or single).
- 今すぐ再送 on a single ATTENTION item: allowed for every reason except `RECIPIENT_DELETED`.
- Resume = ATTENTION -> WAITING / QUEUED, `retryStartMillis = now` (restarts the 24 h window), `attentionSinceMillis = null`, `attentionReason = null`, `resendPending = false`; then the worker is triggered. Room write first, then scheduler (ds 7.4).
- Active cap: a resume never creates a 6th active item. The slot test is `activeCount + (previewExists ? 1 : 0) < ACTIVE_CAP` (ds 2.4; `previewExists` from `setPreviewExists`, PLAN-1 section 4). Without a free slot the item stays ATTENTION with `resendPending = true` and is promoted later by the observer (section 4), oldest first by `attentionSinceMillis` (ties: `capturedAtMillis`, `id`).
- 要確認 items with `resendPending = true` still count as 要確認 (badge and notification count), not as active.

## 2. `promote()`
Single entry that does all promotions; serialized by an in-process `Mutex`.
```
suspend fun promote(): Int =
  mutex.withLock {
    var promoted = 0
    db.withTransaction {
      loop:
        active = count(state=WAITING); reservation = previewExists ? 1 : 0
        if (active + reservation >= ACTIVE_CAP) break
        next = pendingResend().firstOrNull() ?: break
        set next: WAITING, QUEUED, retryStartMillis=now, attentionSince=null, reason=null, resendPending=false
        promoted++
    }
    if (promoted > 0) scheduler.triggerNow()
    promoted
  }
```
The slot test and the update share one transaction, so a concurrent `createItem` (also transactional) cannot push active over 5. Reservation is read at transaction start from the `StateFlow` snapshot.

## 3. Entry points
- `resendNow(itemId): ResendOutcome`:
  1. Load the row; missing -> `NotAllowed`.
  2. Always write the log line `RESEND_REQUESTED` (recipient name) for a permitted tap (not for `NotAllowed`).
  3. ATTENTION and reason != RECIPIENT_DELETED: set `resendPending = true`, `promote()`; if this item was promoted -> `Started`, else `Waiting` (the UI shows the Material3 `Snackbar`, `SnackbarDuration.Short`, no action button, "順番待ちです。しばらくお待ちください" in the hidden settings; the repository only returns the outcome). For `Started` the UI shows nothing: no other resend feedback, the row simply becomes 待機中 (ds 7.5, Q57).
  4. WAITING and phase QUEUED: `retryStartMillis = now` (restarts the 24 h window), then `scheduler.triggerNow()` -> `Started`.
  5. WAITING in `CANCEL_WINDOW`, `SENDING_FG`, `SENDING_BG`, or ATTENTION with RECIPIENT_DELETED -> `NotAllowed` (no log line; UI buttons are already disabled).
- `resumeAfterSetupFix()`: called by settings-screen PLAN-1 after a successful SMTP save and by PLAN-3 after a successful test send. Sets `resendPending = true` on all ATTENTION rows with reasons AUTH / PERMANENT / RETRY_EXPIRED (one UPDATE), then `promote()`. No `RESEND_REQUESTED` line (that line is only for manual taps). If no row qualifies this is a no-op; it also calls `scheduler.enqueueDefault()` when any `WAITING / QUEUED` row exists (a stalled backoff is not reset by this call; only explicit `triggerNow` paths reset it).
- Both are `suspend` and run on IO.

## 4. Observer (ds 7.5: "`OutboxRepository` observes the active-count Flow and the Preview-reservation flag")
Application-scope collector started with the other observers (PLAN-2 section 3):
```
combine(activeCount, previewExists, pendingResendCount).distinctUntilChanged()
    .collect { (active, preview, pending) -> if (pending > 0 && active + (if (preview) 1 else 0) < ACTIVE_CAP) promote() }
```
`pendingResendCount` is a Room Flow `COUNT(*) WHERE state='ATTENTION' AND resendPending=1`. Results: when a slot is freed (a photo sent, deleted, cancelled, or the Preview left via やり直す / settings) the oldest pending item is promoted automatically. While the shutter is blocked at 5 active items and the family has pending items, a freed slot is therefore refilled at once; this is the specified behavior.

## 5. Scheduler seam
`WorkScheduler` (PLAN `outbox-queue/PLAN.md` section 3). `triggerNow()` = ds 7.4 trigger rule: if the unique work `outbox-send` is not RUNNING, enqueue with REPLACE (resets backoff); if RUNNING never replace, enqueue the follow-up `outbox-send-next` (KEEP). `enqueueDefault()` = KEEP. Implemented in outbox-worker PLAN-2. In tests a recording fake.

## 6. Tests
JVM/Room with fakes (virtual clock):
- Slot rule matrix: active 4 + preview false -> immediate; active 4 + preview true -> pending; active 5 -> pending; slot freed -> pending item promoted without any call.
- Oldest-first promotion across several pending items; one slot frees one item only.
- `resendNow`: each reason, RECIPIENT_DELETED -> `NotAllowed`, phase CANCEL_WINDOW -> `NotAllowed`, QUEUED item restarts window (+ `triggerNow`), log line written only for permitted taps.
- `resumeAfterSetupFix`: only AUTH / PERMANENT / RETRY_EXPIRED rows, none for RECIPIENT_DELETED; attention count unchanged for pending ones; notification cancelled when the last ATTENTION row is promoted.
- Order: DB state is already updated when `scheduler.triggerNow()` runs.
- Race: `createItem` and `promote()` concurrently never exceed 5 active.
