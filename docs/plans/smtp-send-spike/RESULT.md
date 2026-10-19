# smtp-send-spike RESULT

Status: PASS. API 26 emulator + M06 (API 36); Gmail smtp.gmail.com:465 SSL; R8 release (minified). sense4 (API 29) not tested.

## Index
1. Environment
2. Findings so far
3. Required fixes
4. Pending

## 1. Environment
- Library: `com.sun.mail:android-mail:1.6.7`, `android-activation:1.6.7`
- Build: AGP 8.7.3, Kotlin 2.0.21, Gradle 8.9, JBR from Android Studio; release type `isMinifyEnabled = true`, debug key.
- Devices: emulator `spike26` (Android 8.0, API 26, google_apis x86_64, headless, WHPX), M06 `M06-SERIAL` (Android 16, API 36).

## 2. Findings so far
| Step | API 26 emulator | M06 API 36 |
|---|---|---|
| R8 release without keep rules, `msg.writeTo` | FAIL `no object DCH for MIME type multipart/mixed` | FAIL (same) |
| R8 release with ds 11.4 keep rules, `msg.writeTo` (768 KB, 2 JPEG attachments) | OK | OK |
| Real SMTP send, R8 release, 1 attachment (~283 KB) | OK (19:36 JST) | OK (19:36) |
| Real SMTP send, 2 same-name attachments | OK (19:36, 19:37) | OK 1 of 3 tries (19:36 FAIL, 19:36 OK, 19:37 FAIL) |

## 3. Required fixes
- Packaging: duplicate `META-INF/LICENSE.md` between android-mail and android-activation fails `mergeDebugJavaResource`. Fix: `packaging { resources { excludes += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md") } }` (ds 11.4 lists only mailcap `pickFirsts`; LICENSE.md is an addition, ds 11.4 should be updated).
- R8: the keep rules from ds 11.4 are required (without them multipart/mixed DCH is missing). Full set in `spike/app/proguard-rules.pro`.

## 4. Inbox verification (user, 2026-10-19)
All mails arrived; JPEGs open; both same-name attachments present in 2-attachment mails.
- API 26: 193618 (1 att), 193623 (2 att), 193716 (2 att)
- API 36: 193633 (1 att), 193642 (2 att), 193654 (2 att)

## 5. False-failure observation (accepted risk)
M06 reported `MessagingException: Exception reading response` in 2 runs (193642 and 193716-era run at 19:37) but mails 193642 and 193654 were delivered: the server likely dropped the connection after accepting DATA. A failure result can therefore be a false failure; retrying may produce duplicate mails. User decision: retry and accept possible duplicates (to be recorded in DECISIONS.md).

## 6. Required spec changes (for main Claude)
- ds 11.4: add packaging excludes `META-INF/LICENSE.md` and `META-INF/NOTICE.md` (duplicate between android-mail and android-activation fails the build).
- ds 11.4: reference the verified keep rules file `spike/app/proguard-rules.pro` (keep rules are mandatory; without them `no object DCH for MIME type multipart/mixed`).
- ds 6.3/7: note possible false failure on "Exception reading response" and accepted duplicates.
