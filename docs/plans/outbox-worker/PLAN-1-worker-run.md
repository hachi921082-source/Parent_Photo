# outbox-worker PLAN-1: worker run

Source: ds 7.4 (Run steps 1-6, safety paragraphs), 6.3, 7.7. Package `outbox/`.

## Index
1. Dependencies (from outbox-queue / mail)
2. `doWork` algorithm
3. Cancellation safety
4. Concurrency
5. Result mapping
6. Tests

## 1. Dependencies
- `OutboxRepository` worker-support calls (outbox-queue PLAN-1 section 7, PLAN-2 section 6): `awaitRecovery`, `expireOld`, `markAllQueuedAttention`, `markRecipientMissing`, `queuedSnapshot`, `claimBatch`, `releaseBatch`, `finishBatchSent`, `finishBatchTransient`, `finishBatchAttention`.
- `BatchPlanner` (outbox-queue PLAN-1 section 7), `SmtpSender.sendBatch(MailRequest)` (mail-composition-smtp), `SettingsRepository.settings` + `readPassword()` + `SetupState.isSetupRequired` (settings-storage), `Clock`.

## 2. `doWork` algorithm (ds 7.4 steps 1-6)
```
override suspend fun doWork(): Result {
  repo.awaitRecovery()                                   // never touch rows before recovery finished
  return OutboxWorkerGate.mutex.withLock { run() }
}

private suspend fun run(): Result {
  val failedThisRun = mutableSetOf<ItemId>()             // transient failures: not retried in this run
  val skippedThisRun = mutableSetOf<ItemId>()            // claim lost / changed under us
  var first = true
  while (true) {
    repo.expireOld(clock())                              // step 2 (also repeated each loop: cheap)
    if (setupRequired()) {                               // step 3
      repo.markAllQueuedAttention(AUTH); return Result.success()
    }
    val queued = repo.queuedSnapshot().filter { it.id !in failedThisRun && it.id !in skippedThisRun }
    if (queued.isEmpty()) break                          // step 1 (first loop) / re-query exit
    val batch = BatchPlanner.plan(queued).first()        // one mail per loop, then re-query (ds 7.4)
    val recipient = settings.recipients.find { it.id == batch.recipientId }
    if (recipient == null) { repo.markRecipientMissing(batch.ids); continue }          // step 4
    val claimed = repo.claimBatch(batch)                 // phase -> SENDING_BG, atomic
    if (claimed.isEmpty()) { skippedThisRun += batch.ids; continue }
    val result = try {
      smtp.sendBatch(MailRequest(recipient.email, claimed.map { MailPhoto(file(it.id), it.capturedAtMillis) }))
    } catch (e: CancellationException) {
      withContext(NonCancellable) { repo.releaseBatch(claimed.ids) }                   // safety, section 3
      throw e
    }
    when (result) {                                      // step 5
      Success -> repo.finishBatchSent(claimed.ids, recipient.id)
      is Failure -> when (result.kind) {
        TRANSIENT -> { repo.finishBatchTransient(claimed.ids, result.code); failedThisRun += claimed.ids }
        AUTH, PERMANENT -> repo.finishBatchAttention(claimed.ids, reasonFor(result.kind), result.code)
      }
      Offline, RecipientMissing -> { repo.releaseBatch(claimed.ids); failedThisRun += claimed.ids }   // cannot happen from sendBatch; defensive
    }
  }
  val stillQueued = repo.queuedSnapshot().any { it.id in failedThisRun }
  return if (stillQueued) Result.retry() else Result.success()                          // step 6
}
```
Notes:
- `reasonFor(AUTH) = AttentionReason.AUTH`, `reasonFor(PERMANENT) = PERMANENT`. A mail is atomic: all photos of the batch move together (ds 7.4).
- Settings (address, password, host, port) are read inside `SmtpSender` for each mail (not cached by the worker), so a settings save during the run is seen by the next mail. The recipient address is taken from the freshly read recipient list at each loop (recipient edits apply at the next send).
- `setupRequired()` = `SetupState.isSetupRequired(settings.first(), readPassword() != null)`; all remaining QUEUED rows -> 要確認 AUTH with one notification; return success (the family fixes settings, resume triggers restart the work).
- The worker never touches rows in `CANCEL_WINDOW` or `SENDING_FG`. `SENDING_BG` rows are only those it claimed itself (or left by a prior process; recovery has reset those).
- Re-query after every mail means items resumed during the run (by `triggerNow` writing QUEUED) are picked up in the same run.
- No log line for a cancelled run. Log lines (SENT / FAILED) are written by the `finishBatch*` calls, one per mail.
- Logcat tag `ParentPhoto/Worker`: run start/end, batch size, result kind and code. No addresses, no password.

## 3. Cancellation safety (ds 7.4 "onStopped / cancellation")
- Cancellation (`CancellationException`, including WorkManager REPLACE or constraint loss) can only arrive at suspension points: before the send, or while awaiting the send's `async` (mail-composition-smtp section 4). In both cases items already claimed go back to QUEUED via `releaseBatch` inside `NonCancellable`, with no log line. If cancellation arrives while waiting on `repo.claimBatch` / `finishBatch*` (Room suspend functions run to completion once started in a transaction), the next cancellation check happens after the transaction, so rows are never left half-updated.
- The abandoned blocking `Transport.send` thread is not interrupted. It ends by itself at the socket timeouts (about 30 s). If that send still succeeds the mail is delivered while the photo stays QUEUED, so a later run may send it again: the accepted ambiguous-duplicate case (ds 7.4/7.7, D14).
- If the process dies mid-send, rows stay `SENDING_BG`; startup recovery (outbox-queue PLAN-2 section 4) returns them to QUEUED and enqueues the worker.

## 4. Concurrency
- `OutboxWorkerGate.mutex` (one per process): the `outbox-send-next` follow-up and `outbox-send` never run their `run()` bodies at the same time. The second one waits, then re-queries and usually finds nothing to do.
- `claimBatch` is atomic: only rows still `WAITING / QUEUED` are claimed. Foreground sends use different phases and different rows, and are not affected.

## 5. Result mapping
| Situation | Result |
|---|---|
| No QUEUED rows at all | `Result.success()` |
| All mails sent / moved to 要確認 | `Result.success()` |
| At least one transient failure and its rows are still QUEUED | `Result.retry()` (WorkManager exponential backoff from 15 min) |
| SetupRequired | `Result.success()` after moving all to 要確認 (AUTH) |
| Cancellation | exception propagates (WorkManager treats the work as stopped) |

## 6. Tests
JVM (`TestListenableWorkerBuilder` or the algorithm extracted into a `OutboxRunner` class so it is testable without WorkManager; the worker is a thin wrapper), with fake repository / sender / clock:
- No items -> success; items for two recipients -> two mails in oldest-first order; 5 photos near 15 MB -> split mails each with ascending order.
- Transient failure -> items remain QUEUED, `retry`, not retried again within the same run; AUTH -> 要確認 AUTH + notify via repository; PERMANENT -> 要確認 PERMANENT; mixed results across recipients.
- Expired item (`now - retryStart > 24 h`) goes to RETRY_EXPIRED, is not sent; exactly 24 h is still sent.
- SetupRequired -> all remaining to AUTH, one notify, success.
- Missing recipient -> RECIPIENT_DELETED; edited recipient address used at send.
- Cancellation during send -> items QUEUED again, no log line, exception propagates.
- Item added (QUEUED) during the run is picked up by the re-query.
- Two concurrent runs: no item sent twice.
- Run waits for `awaitRecovery()`.
