# smtp-send-spike RESULT

Status: send OK from R8 release on both devices; inbox check by user pending. One intermittent M06 failure noted.

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

## 4. Pending / observations
- Intermittent on M06: `javax.mail.MessagingException: Exception reading response` (no class-loading error; server dropped the connection during/after DATA, likely Gmail throttling of rapid repeated sends or the 2-attachment size). Not seen on emulator. Whether those mails were delivered is to be checked in the inbox.
- Duplicates in inbox are expected (several test sends).
- User inbox check: subjects `[SPIKE] 写真テスト <yyyyMMdd_HHmmss> (release API26|API36 添付1|2)`; JPEGs open, same-name attachments handled.
