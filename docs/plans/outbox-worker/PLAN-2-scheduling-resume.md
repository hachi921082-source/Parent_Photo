# outbox-worker PLAN-2: scheduling and resume triggers

Source: ds 7.4 (Worker paragraph, Enqueue when, Triggers), 7.5 (resume triggers), 7.6 (enqueue after recovery), 9.4, 9.5. Package `outbox/`.

## Index
1. WorkManager configuration
2. `OutboxScheduler`
3. Trigger wiring (who calls what)
4. Write-before-enqueue rule
5. Tests and device verification

## 1. WorkManager configuration
- Dependency `androidx.work:work-runtime-ktx` (app-foundation). Default initializer is kept.
- Request: `OneTimeWorkRequestBuilder<OutboxWorker>()`, constraints `NetworkType.CONNECTED`, `setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_INITIAL = 15 min)` (WorkManager caps the delay at about 5 h), no initial delay, no expedited work, tag `outbox`.
- Unique names: `outbox-send` and `outbox-send-next` (constants in `Constants`).

## 2. `OutboxScheduler : WorkScheduler`
```kotlin
class OutboxScheduler(private val wm: WorkManager) : WorkScheduler {
    override fun enqueueDefault() {                    // KEEP: do not disturb a running or backing-off work
        wm.enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request())
    }
    override fun triggerNow() {                        // ds 7.4 Triggers
        scope.launch(Dispatchers.IO) {
            val running = wm.getWorkInfosForUniqueWork(NAME).await().any { it.state == WorkInfo.State.RUNNING }
            if (running) wm.enqueueUniqueWork(NEXT, ExistingWorkPolicy.KEEP, request())   // never REPLACE a RUNNING work
            else         wm.enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request()) // absent / ENQUEUED / BLOCKED: reset backoff
        }
    }
}
```
- `scope` = application scope (SupervisorJob). Both functions are fire-and-forget and idempotent.
- `REPLACE` on a work that became RUNNING between the check and the call cancels it; the worker's cancellation path (PLAN-1 section 3) restores the claimed rows to QUEUED, and the replacement run picks them up. Acceptable, and the case never loses a photo.
- `enqueueDefault` with a finished `outbox-send` (SUCCEEDED/FAILED/CANCELLED) enqueues a new one (KEEP only blocks while a non-finished work exists).
- Unique-work state `RUNNING` of `outbox-send-next` is not inspected (the mutex in PLAN-1 section 4 makes overlap harmless).

## 3. Trigger wiring (who calls what; all rows from ds 7.4 "Enqueue when")
| Event | Caller | Scheduler call |
|---|---|---|
| Item becomes QUEUED after failed/offline foreground send | `OutboxRepository.markQueued` | `enqueueDefault()` |
| Startup recovery found QUEUED items | `OutboxRepository` recovery | `enqueueDefault()` |
| SMTP settings saved (resumes AUTH / PERMANENT / RETRY_EXPIRED items) | settings-screen -> `resumeAfterSetupFix()` | `triggerNow()` via `promote()` when something was promoted; `enqueueDefault()` otherwise if QUEUED exist |
| Successful test send | settings-screen PLAN-3 -> `resumeAfterSetupFix()` | same as above |
| 今すぐ再送 on a 要確認 item | `resendNow` | `triggerNow()` (when promoted) |
| 今すぐ再送 on a 待機中/QUEUED item | `resendNow` | `triggerNow()` (also restarts the 24 h window) |
| Auto-promotion of a `resendPending` item | observer in outbox-queue PLAN-3 | `triggerNow()` |
`ParentPhotoApp` container: `scheduler = OutboxScheduler(WorkManager.getInstance(this))`; this replaces the fake `WorkScheduler` that outbox-queue used.

## 4. Write-before-enqueue rule (ds 7.4 Triggers)
Every caller above first commits the Room state change (reset `retryStartMillis`, clear attention fields, phase QUEUED) and only then calls the scheduler, so a worker started immediately sees the new state. The scheduler functions never touch Room.

## 5. Tests and device verification
JVM / `WorkManagerTestInitHelper` (androidx.work:work-testing):
- `enqueueDefault` twice -> one work (KEEP). `triggerNow` with no work -> work exists; with work ENQUEUED (backoff) -> replaced and delay reset; with work RUNNING (test driver) -> not replaced, `outbox-send-next` enqueued.
- Constraints on the request: CONNECTED; backoff policy EXPONENTIAL, 15 min.
- Worker returns `retry` -> WorkManager reschedules (test driver `setInitialDelayMet`/`setAllConstraintsMet`).

Device checks (done in the acceptance run, listed here so the plan is verifiable):
1. Airplane mode: foreground send fails -> QueuedNotice; badge "未送信 1件"; turn network on -> photo arrives, badge disappears (worker log `ParentPhoto/Worker`).
2. Kill the app during the undo window and during Sending -> restart: photo is sent by the worker (recovery).
3. Wrong app password in settings -> test send reports AUTH; queued photo goes to 要確認 on the worker's next run with a notification; correct the password and save -> the photo is resumed and sent.
4. Rapid taps of 今すぐ再送 do not start two simultaneous sends (mutex) and do not cancel a running send.
5. 24 h expiry: use a debug build with `RETRY_WINDOW_MS` overridable through the DI container (test clock or reduced window), verify RETRY_EXPIRED and notification; do not rely on waiting 24 h.
6. Release (R8) APK: the worker path sends a multi-photo mail (same keep rules as the foreground path).
