# Plan: outbox-worker

Source: ds 7.4, 7.5 (trigger rules), 7.7, 6.1 (timeouts), 6.3, 12 ("6 Worker safety"). Branch: `feature/outbox-worker`. Depends on: outbox-queue (all state changes go through `OutboxRepository` worker-support calls, `WorkScheduler` interface, recovery gate), mail-composition-smtp (`SmtpSender.sendBatch`, `MailResult`), settings-storage (`SetupState`), app-foundation (Constants, WorkManager dependency, Application).

Index of the sub-plans (read only what is needed):
- `PLAN-1-worker-run.md`: `OutboxWorker.doWork` algorithm, cancellation safety, concurrency, result mapping.
- `PLAN-2-scheduling-resume.md`: `OutboxScheduler` (`enqueueDefault`, `triggerNow`), WorkManager configuration, trigger wiring and write-before-enqueue rule, verification plan.

## Index
1. Scope
2. Components
3. Decisions fixed here
4. Known limitations
5. Order and done-when

## 1. Scope
Everything WorkManager: the background send loop and its scheduling. All Room state changes are delegated to `OutboxRepository` (outbox-queue); the worker holds no DB logic of its own. Package `outbox/`.

## 2. Components
- `OutboxWorker : CoroutineWorker` (PLAN-1).
- `OutboxScheduler : WorkScheduler` (PLAN-2): replaces the fake scheduler of outbox-queue in `ParentPhotoApp`'s container.
- `OutboxWorkerGate`: process-wide `Mutex` serializing worker runs (PLAN-1 section 4).
- Dependencies reach the worker through the Application container (`(applicationContext as ParentPhotoApp).container`); no custom `WorkerFactory` and no extra DI library.

## 3. Decisions fixed here (implementation level, no spec change)
- Unique work names `outbox-send` (default) and `outbox-send-next` (follow-up while `outbox-send` is RUNNING). Both use the same constraints and backoff.
- Even if both workers are alive at once (follow-up starts while the first still runs), items are never sent twice by the worker itself: a process-wide mutex serializes runs and `claimBatch` is an atomic QUEUED -> SENDING_BG update.
- The worker waits for `OutboxRepository.awaitRecovery()` before touching any row (a process started by WorkManager must not reset `SENDING_BG` rows it is about to own).
- The 60 s per-mail cap and 15/30/30 s socket timeouts are inside `SmtpSender` (mail-composition-smtp section 4).
- Accepted duplicates (ds 7.7, D14): cancellation or a timeout after DATA may deliver a mail while the items stay QUEUED; no dedupe.

## 4. Known limitations (from the spec as written, not decided here)
- The 24 h expiry (ds 7.4 step 2) is evaluated only when a worker run starts. If the device has no network for more than 24 h, the constraint `CONNECTED` keeps the work from running, so items move to 要確認 (and the notification is posted) only after connectivity returns. When it returns, expired items are moved to 要確認 without a last send attempt (step 2 precedes sending). Reported to the user as a note; no change planned.
- WorkManager may delay runs under Doze / battery optimization; the 15 min initial backoff is a lower bound, not a schedule.

## 5. Order and done-when
Order: PLAN-1 -> PLAN-2. Done when: JVM tests of both sub-plans pass; with a real `OutboxScheduler` a foreground failure produces a worker run that sends the queued photo after the network returns; cancellation leaves items QUEUED; the kill-during-send recovery path works on a device (device checks listed in PLAN-2 section 5).
