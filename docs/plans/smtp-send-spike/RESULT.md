# smtp-send-spike RESULT

Status: PARTIAL. MIME serialization verified on both devices; actual SMTP send NOT yet run (waiting for `spike.local.properties`).

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
| Real SMTP send | pending | pending |

## 3. Required fixes
- Packaging: duplicate `META-INF/LICENSE.md` between android-mail and android-activation fails `mergeDebugJavaResource`. Fix: `packaging { resources { excludes += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md") } }` (ds 11.4 lists only mailcap `pickFirsts`; LICENSE.md is an addition, ds 11.4 should be updated).
- R8: the keep rules from ds 11.4 are required (without them multipart/mixed DCH is missing). Full set in `spike/app/proguard-rules.pro`.

## 4. Pending
- Real send with Gmail (needs `spike.local.properties`), inbox check of subject `[SPIKE] 写真テスト ...` with 1 and 2 attachments, then the same on M06.
