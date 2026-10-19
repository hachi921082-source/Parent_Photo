# Plan: mail-composition-smtp

Source: ds 5, 6.1-6.4, 7.7 (duplicates), 11.4 (R8/packaging), 12 (4 Send). Branch: `feature/mail-composition-smtp`. Depends on: app-foundation (Constants, TokyoTime, Connectivity, R8/packaging), settings-storage (`SettingsRepository`, `readPassword()`, `Recipient`). Spike result (PASS): `docs/plans/smtp-send-spike/RESULT.md`, verified call sequence in `spike/app/src/main/java/com/example/parentphoto/spike/MainActivity.kt`, verified keep rules `spike/app/proguard-rules.pro`.

## Index
1. Scope and package
2. Types (contract)
3. MailComposer
4. SessionFactory and SmtpSender
5. ErrorClassifier
6. Recording (Logcat and send log)
7. Spike findings carried into this plan
8. Tests
9. Steps and done-when

## 1. Scope and package
Package `mail/`. Pure mail logic: build the MIME message, open the SMTP session, send, classify the outcome, record. No Room, no WorkManager, no UI. The outbox layer (outbox-queue / outbox-worker) calls this plan; this plan depends on the outbox only through two small interfaces it owns (section 2.3), implemented by outbox-queue.

## 2. Types (contract; fixed here, used by photo-send-flow PLAN-3, settings-screen PLAN-3, outbox-queue, outbox-worker)

### 2.1 Results
```kotlin
enum class MailCode { NETWORK, TIMEOUT, AUTH, REJECTED, OTHER }   // ds 6.3 / 6.4 codes
enum class FailureKind { TRANSIENT, AUTH, PERMANENT }             // ds 6.3 classes

sealed interface MailResult {
    data object Success : MailResult
    data object Offline : MailResult                              // foreground only, ds 6.2; no attempt made
    data object RecipientMissing : MailResult                     // recipient id not found at attempt start
    data class Failure(
        val kind: FailureKind, val code: MailCode,
        val exceptionClass: String?, val message: String?, val smtpReply: Int?
    ) : MailResult
}
```
Mapping table (ds 6.3): TRANSIENT <- NETWORK, TIMEOUT, OTHER (unknown exception); AUTH <- AUTH; PERMANENT <- REJECTED, OTHER (non-auth 5xx). `RecipientMissing` is produced only by `sendForeground` and the outbox worker path (the repository turns it into 要確認 / `RECIPIENT_DELETED`, not a mail failure; no FAILED log line).

### 2.2 Request model and sender
```kotlin
data class MailPhoto(val file: File, val capturedAtMillis: Long)
data class MailRequest(val toAddress: String, val photos: List<MailPhoto>)   // photos ascending by capture time, size >= 1

class SmtpSender(settings: SettingsRepository, connectivity: ConnectivityChecker, composer: MailComposer,
                 source: SendSource, sendLog: SendLogWriter, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    suspend fun sendForeground(itemId: ItemId): MailResult      // photo-send-flow PLAN-3 contract (offline shortcut inside)
    suspend fun sendBatch(request: MailRequest): MailResult     // worker path: no offline shortcut (WorkManager constraint covers it)
    suspend fun sendTest(recipient: Recipient): MailResult      // settings-screen PLAN-3: text-only; writes TEST_OK / TEST_FAILED
}
```
- `sendForeground` and `sendBatch` do NOT write SENT / FAILED / QUEUED_OFFLINE send-log lines. The caller (`OutboxRepository` for foreground, worker for batches) writes them because only it knows the recipient name and the item outcome. They write Logcat (section 6) themselves inside the sender.
- `sendTest` writes `TEST_OK` / `TEST_FAILED(code)` itself (recipient display name from the argument). It does not trigger the resume; settings-screen PLAN-3 calls `OutboxRepository.resumeAfterSetupFix()` on success (outbox-queue PLAN-3).

### 2.3 Interfaces implemented elsewhere
```kotlin
interface SendSource {                          // implemented by outbox-queue (PLAN-1); lets sendForeground avoid depending on Room
    suspend fun loadForeground(itemId: ItemId): SendableItem?   // null if the row is gone
}
data class SendableItem(val recipientId: String, val file: File, val capturedAtMillis: Long)

interface SendLogWriter {                       // implemented by outbox-queue (PLAN-1, Room send_log table)
    suspend fun record(result: SendLogResult, recipientName: String?, code: String?)
}
enum class SendLogResult { SENT, FAILED, QUEUED_OFFLINE, DELETED_MANUAL, DELETED_OVERFLOW, RESEND_REQUESTED, TEST_OK, TEST_FAILED }
```
`recipientName == null` is rendered "（削除された宛先）" by the UI. The recipient address for `sendForeground` is read from `SettingsRepository.settings` (recipient list) by `recipientId` at attempt start; missing -> `RecipientMissing`.

## 3. MailComposer (ds 5)
`MailComposer.compose(session, settings, request): MimeMessage` and `composeTest(session, settings, toAddress): MimeMessage`. Pure given inputs (no I/O except `FileDataSource` lazy reads) so it is JVM-testable.
- `Session` argument is used only to construct the `MimeMessage`; composition does not connect.
- From: `InternetAddress(senderAddress, senderNameSanitized, "UTF-8")`. Sanitizing = strip `\r` / `\n`, trim. Empty after sanitizing -> address only. No "家族" fallback in From.
- To: recipient address only (single, no display name). No Cc/Bcc, no Reply-To. `Date` = now (`sentDate = Date()`).
- Subject via `setSubject(s, "UTF-8")`: N == 1 "写真が届きました"; N >= 2 "写真が届きました（N枚）" (full-width parentheses).
- Body part: `MimeBodyPart.setText(text, "UTF-8")` (text/plain; charset=UTF-8), lines joined by `\n`. `{sender}` = sanitized sender name, or "家族" when empty. Line 1 `{sender}さんから写真が届きました。`, then one line `撮影日時: yyyy/MM/dd HH:mm` per photo in attachment order (`TokyoTime`, Asia/Tokyo).
- Attachments: one `MimeBodyPart` per photo, `dataHandler = DataHandler(FileDataSource(file))` with the content type forced to `image/jpeg` (use a small `JpegFileDataSource` override of `getContentType()`), `setHeader("Content-Transfer-Encoding", "base64")`, `fileName` = `yyyyMMdd_HHmmss.jpg` from capture time (Asia/Tokyo, ASCII only, so no `MimeUtility` encoding needed). Same-name collisions inside one mail get `_2`, `_3` before the extension in order of appearance.
- Container: `MimeMultipart("mixed")` with the text part first, attachments after.
- Test mail: subject "テスト送信", body "これはテスト送信です。", sender/To rules identical, no attachment (single `text/plain` message, no multipart).
- Never log or include the password. Call `msg.saveChanges()` implicitly via `Transport.send`.

## 4. SessionFactory and SmtpSender (ds 6.1, 6.2, 6.4)
- Settings read at every send: `settings.first()` for address/host/port, `readPassword()` (decrypted at send time, never cached). If setup invalid at call time (null password etc.), return `Failure(AUTH, AUTH, ...)` without connecting (the worker also checks SetupRequired itself, outbox-worker PLAN-1).
- `SessionFactory.create(host, port, user, password): Session` using `Session.getInstance(props, Authenticator)` (not `getDefaultInstance`; no shared state across sends). Properties: `mail.smtp.host`, `mail.smtp.port`, `mail.smtp.auth=true`, `mail.smtp.connectiontimeout=15000`, `mail.smtp.timeout=30000`, `mail.smtp.writetimeout=30000`, `mail.smtp.ssl.checkserveridentity=true`. Port 465: `mail.smtp.ssl.enable=true`. Other ports: `mail.smtp.starttls.enable=true` and `mail.smtp.starttls.required=true` (cleartext never used). Constants come from app-foundation `Constants`.
- Offline shortcut (foreground only): `Connectivity.isClearlyOffline(context)`; true -> return `MailResult.Offline` before touching the session. Not `VALIDATED`-gated (ds 6.2).
- Send execution: `withContext(ioDispatcher)`; the whole attempt (compose + `Transport.send(message)`) runs under a 60 s cap (`FG_SEND_TIMEOUT_MS`): launch the blocking call in `async` on the IO dispatcher and `withTimeout(60_000)` on `await()`. `Transport.send` is blocking and not interruptible; on timeout the awaiting caller returns `Failure(TRANSIENT, TIMEOUT)` and the abandoned IO thread ends by itself at the socket timeouts (at most about 30 s later). If that abandoned call still succeeds, a duplicate mail results; accepted (ds 7.7, D14). The same cap applies to `sendBatch` and `sendTest`. Callers may add `withTimeoutOrNull(FG_SEND_TIMEOUT_MS)` as a backstop; `null` there is treated as `Failure(TRANSIENT, TIMEOUT)`.
- Coroutine cancellation (worker stopped): propagate `CancellationException`, never convert it to a `Failure`.
- `sendBatch` / `sendForeground` compose inside the same try block as the send, so a composition exception (for example a missing photo file) is classified by section 5 like any other.

## 5. ErrorClassifier (ds 6.3)
`ErrorClassifier.classify(t: Throwable): MailResult.Failure` (pure, JVM-testable). Walk the cause chain (`getCause()` and `MessagingException.getNextException()`, depth-limited to 10, cycle-safe) and decide by the first definitive match, in this priority:
1. `AuthenticationFailedException` anywhere in the chain -> AUTH / code AUTH.
2. SMTP reply code (`SMTPSendFailedException.returnCode`, also parsed from `SendFailedException`/`MessagingException` message text like `535 5.7.8` when no typed exception is available): 530, 534, 535 -> AUTH; other 4xx -> TRANSIENT / NETWORK; 550, 553 and `AddressException` -> PERMANENT / REJECTED; other 5xx -> PERMANENT / OTHER.
3. Our own timeout, `SocketTimeoutException` -> TRANSIENT / TIMEOUT.
4. `UnknownHostException`, `ConnectException`, `SocketException`, `SSLException`, `MailConnectException`, any other `IOException`, and `MessagingException` with message starting `Exception reading response` -> TRANSIENT / NETWORK. The "reading response" case may be a false failure (the server accepted the mail and dropped the connection); a retry may deliver a duplicate. Accepted (D14); no special handling and no dedupe.
5. Anything else -> TRANSIENT / OTHER (retry until 24 h, then 要確認) [ds 6.3 assumption].
Record `exceptionClass`, `message` and `smtpReply` in the `Failure` for Logcat. The father sees no cause (UI is the same QueuedNotice for every failure).

## 6. Recording (ds 6.4)
- `MailLog` wrapper over `android.util.Log`, tag `ParentPhoto/Mail`: on every failure log class (`FailureKind`), code, exception class and message, SMTP reply code; on success log "sent N photos" only. Never log the password, host credentials, or the message body.
- Send log lines (Room) are written through `SendLogWriter` (section 2.3) by: `sendTest` (TEST_OK / TEST_FAILED with code), `OutboxRepository` for foreground results (SENT / FAILED / QUEUED_OFFLINE), the worker for batches (SENT / FAILED), outbox-queue for DELETED_*, RESEND_REQUESTED. The 50-row trim is the table's concern (outbox-queue PLAN-1).
- Display strings for send-log results belong to settings-screen PLAN-4; codes stored as the enum names above.

## 7. Spike findings carried into this plan
- Verified call sequence: `Session.getInstance(props, Authenticator)` -> `MimeMessage` -> `MimeMultipart("mixed")` -> `MimeBodyPart` with `DataHandler(ByteArrayDataSource(bytes, "image/jpeg"))` -> `Transport.send`. This plan uses `FileDataSource` (same `DataHandler` mechanism; verify in the release smoke test, section 9).
- R8: keep rules from `spike/app/proguard-rules.pro` are mandatory (`javax.mail.**`, `com.sun.mail.**`, `javax.activation.**`, `com.sun.activation.**` kept; `-dontwarn` for `java.awt.**`, `java.beans.**`, `javax.security.sasl.**`, `javax.naming.**`, `org.apache.harmony.**`). Without them `writeTo` fails with `no object DCH for MIME type multipart/mixed`. Owned by app-foundation's `app/proguard-rules.pro`; this plan only re-verifies.
- Packaging: `resources.excludes += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")` plus mailcap `pickFirsts` from ds 11.4 (owned by app-foundation).
- Libraries: `android-mail:1.6.7` and `android-activation:1.6.7` (as in the spike). 2-attachment mails with the same attachment name were received correctly (the `_2` rule is still applied by this plan).
- False failure "Exception reading response" and accepted duplicates: D14, section 5 item 4. Not retested on API 29 (sense4) in the spike; covered by the acceptance run.

## 8. Tests
- JVM (`testImplementation` of the same mail artifacts): `MailComposer` output via `msg.writeTo(ByteArrayOutputStream)`: subject 1 vs N photos (full-width parentheses), From with and without sender name (CR/LF stripped, Japanese name RFC 2047 encoded, "家族" only in body), To without display name, body lines per photo in ascending order, Asia/Tokyo formatting with a non-Tokyo default zone, attachment names and `_2`/`_3` collisions, `image/jpeg` + base64 per attachment, test mail has no attachment.
- `ErrorClassifier` table tests: every row of ds 6.3 including nested causes, `Exception reading response`, 4xx vs 5xx, 530/534/535, `AddressException`, unknown exception.
- `SmtpSender` with a fake `SessionFactory` / transport seam: offline shortcut returns `Offline` without composing; 60 s cap with virtual time yields TIMEOUT; cancellation propagates; settings read each call (change between calls is seen); `sendTest` writes TEST_OK / TEST_FAILED; `RecipientMissing` when the recipient id is absent.
- Device (release/R8 APK, per ds 11.4): send a 1-photo and a 2-photo mail on an API 26-level emulator and an Android 14/15 device; verify attachments open. Port 587 STARTTLS path verified once if a server is available (otherwise noted as unverified).

## 9. Steps and done-when
1. Types, `ErrorClassifier` + tests.
2. `MailComposer` + tests.
3. `SessionFactory`, `SmtpSender` (fakes for seams) + tests.
4. Release build smoke test (R8) on both device classes.
Done when: the JVM tests pass, an R8 release APK sends a real 1- and 2-photo mail on both device classes (all attachments readable), and no password/body appears in Logcat.
