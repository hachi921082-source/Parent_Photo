# Plan: outbox-queue

Source: ds 7.1-7.3, 7.5 (state transitions and promotion), 7.6-7.8, 2.4, 6.4 (send log table), 10.1 (call sites), 9.6/9.7 (data for the lists). Branch: `feature/outbox-queue`. Depends on: app-foundation (Application hook, Constants), settings-storage (`SettingsRepository`, recipients), mail-composition-smtp (types `MailResult`, `SendSource`, `SendLogWriter`, `SendLogResult`), attention-notifications (`AttentionNotifier`; may be a fake until built). Consumed by: photo-send-flow, settings-screen, outbox-worker.

This is an index. Detail lives in sub-plans (read only what is needed):
- `PLAN-1-storage-counting.md`: Room schema, DAOs, files, counting, item creation, foreground-path calls, send log table, batch planner.
- `PLAN-2-recovery-overflow.md`: attention transitions, 要確認 cap and overflow, notification call sites, startup recovery gate, recipient deletion.
- `PLAN-3-resume-promotion.md`: resume rules, active-slot promotion, `resendNow`, scheduler seam.

## Index
1. Scope
2. Public contract: `OutboxRepository`
3. Seams to other features
4. Cross-cutting rules
5. Order and done-when

## 1. Scope
All persistent outbox state and every state transition except the WorkManager run itself (outbox-worker). One owner of all writes: `OutboxRepository`. UI and worker never touch the DAO. Package `outbox/`.

## 2. Public contract: `OutboxRepository`
Names already used by photo-send-flow (PLAN-1, PLAN-3), settings-screen (PLAN-2, 3, 4) and attention-notifications. Final signatures:
```kotlin
class OutboxRepository(/* dao, db, files, settings, scheduler, notifier, sendLog, clock, appScope */) {
    // observation
    val activeCount: Flow<Int>                 // state=WAITING (ds 2.4/7.2); never includes 要確認
    val attentionCount: Flow<Int>
    val items: Flow<List<OutboxItemView>>      // unsent list: WAITING by capturedAt asc, then ATTENTION by attentionSince asc
    val sendLog: Flow<List<SendLogEntry>>      // newest first, max 50
    val recoveryDone: StateFlow<Boolean>       // false until startup recovery finished (PLAN-2)
    suspend fun awaitRecovery()
    fun setPreviewExists(exists: Boolean)      // published by MainViewModel (photo-send-flow PLAN-1 section 7)

    // father-facing flow (photo-send-flow PLAN-3)
    suspend fun createItem(recipientId: String, capturedAtMillis: Long, previewFile: File): ItemId   // throws OutboxFullException at active >= 5
    suspend fun deleteItem(itemId: ItemId)               // undo: row + file, no log line
    suspend fun setPhase(itemId: ItemId, phase: Phase)   // CANCEL_WINDOW -> SENDING_FG
    suspend fun markSent(itemId: ItemId)                 // row + file deleted, log SENT
    suspend fun markQueued(itemId: ItemId, result: MailResult)   // classification applied here (PLAN-1 section 4)

    // hidden settings (settings-screen)
    suspend fun countForRecipient(recipientId: String): Int      // WAITING + ATTENTION items, for the delete dialog
    suspend fun onRecipientDeleted(recipientId: String)          // call AFTER SettingsRepository.deleteRecipient (PLAN-2 section 5)
    suspend fun resendNow(itemId: ItemId): ResendOutcome          // Started | Waiting | NotAllowed (PLAN-3)
    suspend fun resumeAfterSetupFix()                             // SMTP save or test success (PLAN-3)
    suspend fun deleteManual(itemId: ItemId)                      // 削除, log DELETED_MANUAL (PLAN-2)
}
```
Also implemented by this feature: `SendSource` and `SendLogWriter` (mail-composition-smtp section 2.3), and the worker-support calls listed in PLAN-1 section 7 / PLAN-2 / PLAN-3 (`queuedSnapshot`, `claimBatch`, `releaseBatch`, `finishBatchSent`, `finishBatchTransient`, `finishBatchAttention`, `expireOld`, `markAllQueuedAttention`, `markRecipientMissing`), which only outbox-worker uses.

`OutboxItemView(id, recipientId, recipientName: String?, capturedAtMillis, state, phase, reason, file: File)`; `recipientName == null` means deleted recipient ("（削除された宛先）"). `SendLogEntry(id, timeMillis, recipientName: String?, result: SendLogResult, code: String?)`. `Phase { CANCEL_WINDOW, SENDING_FG, SENDING_BG, QUEUED }`, `ItemState { WAITING, ATTENTION }`, `AttentionReason { AUTH, PERMANENT, RETRY_EXPIRED, RECIPIENT_DELETED }`, `ResendOutcome { Started, Waiting, NotAllowed }`.

## 3. Seams to other features
- `WorkScheduler` (interface owned here, implemented by outbox-worker `OutboxScheduler`): `fun enqueueDefault()` (unique work `outbox-send`, KEEP) and `fun triggerNow()` (REPLACE-if-not-RUNNING else follow-up work; ds 7.4). A fake is used until outbox-worker is built; the worker plan replaces it in `ParentPhotoApp`.
- `AttentionNotifier` (attention-notifications): `show(overflowDeleted: Boolean)`, `cancel()`. Call sites are in this feature (PLAN-2 section 3).
- Application hook: `ParentPhotoApp.onCreate` builds the `OutboxRepository` singleton (manual DI, lazy), starts the application-scope observers (PLAN-2 section 3, PLAN-3 section 4) and starts startup recovery (PLAN-2 section 4).

## 4. Cross-cutting rules
- All Room work on IO via suspend DAO methods; every multi-row change in one `withTransaction`.
- File deletion happens after the row transaction commits, best effort; leftovers (orphans) are removed by startup recovery.
- Time source: injected `Clock` (`() -> Long` epoch millis) for tests.
- Logcat tag `ParentPhoto/Outbox` for transitions (item id and ids only; no addresses, no password).
- Photos stay in `filesDir/outbox/{id}.jpg`; no backup (manifest `allowBackup=false`); Room DB `outbox.db`, `exportSchema = true`, no `fallbackToDestructiveMigration` (would lose unsent photo metadata); version 1 at first release.
- Duplicates: ambiguous failures are retried and never deduplicated (ds 7.7, D14): no check, no fixed Message-ID.

## 5. Order and done-when
Order: PLAN-1 -> PLAN-2 -> PLAN-3. Done when: instrumented Room tests and JVM tests of each sub-plan pass; with `OutboxRepository` plugged in place of the fakes, photo-send-flow PLAN-3 runs end to end on a device (enqueue, foreground success, foreground failure, kill during the undo window) with a fake `WorkScheduler`; the worker integration is verified under outbox-worker.
