# Plan: app-foundation

Source: ds 1, 11.1-11.4, 10.1 (channel creation). Branch: `feature/app-foundation`. Depends on: none (reuses spike dependency/R8 findings in `smtp-send-spike/RESULT.md`).

## Index
1. Scope
2. Files
3. Build configuration
4. Manifest
5. Constants and utilities
6. Steps
7. Done when

## 1. Scope
Project scaffolding only: Gradle, manifest, dependencies, R8/packaging, Application class, constants, Tokyo time helper, theme, empty `MainActivity`. No feature logic.

## 2. Files (single module `app`, package `com.example.parentphoto`)
- `settings.gradle.kts`, root and `app/build.gradle.kts`, `gradle/libs.versions.toml`, `app/proguard-rules.pro`
- `ParentPhotoApp.kt` (Application): creates notification channel `attention` (name "送信できていない写真", importance DEFAULT, idempotent); lazy init hooks for Room/WorkManager (filled by pending plans).
- `ui/MainActivity.kt` (hosts Compose content, placeholder), `ui/theme/*`
- `util/Constants.kt`, `util/TokyoTime.kt`, `util/Connectivity.kt`
- Empty package dirs: `camera/`, `mail/`, `outbox/`, `settings/`, `notify/` get files in later plans.
- `res/drawable/ic_stat_photo.xml` (monochrome vector), launcher icon, `res/values/strings.xml` (app name only; father-facing strings stay in code/resources as defined per feature).
- `.gitignore`: build outputs, `*.local.properties`, `.env`.

## 3. Build configuration
- minSdk 26, targetSdk/compileSdk latest stable at implementation time, Kotlin, Compose BOM + Material3, KSP.
- Dependencies (ds 11.3): CameraX (core, camera2, lifecycle, view), DataStore Preferences, Room (runtime, ktx, ksp), WorkManager ktx, Coroutines, `android-mail`, `android-activation`.
- Release: `minifyEnabled true` with ds 11.4 keep rules and `pickFirsts` exactly as found necessary in the spike result. Output file name `ParentPhoto_YYYYMMDD-HHMMSS.apk` (date-time per global convention) via an `applicationVariants`/artifact-naming hook.

## 4. Manifest
- Permissions: `INTERNET`, `CAMERA`, `POST_NOTIFICATIONS`, `VIBRATE`, `ACCESS_NETWORK_STATE`; `uses-feature` camera required.
- `application`: `android:name=".ParentPhotoApp"`, `allowBackup="false"`, `enableOnBackInvokedCallback` set consistently with the empty BackHandlers (photo-send-flow PLAN-1).
- Activity: `screenOrientation="portrait"`, `exported=true` launcher, `windowSoftInputMode="adjustResize"`.

## 5. Constants and utilities
- `Constants`: `UNDO_SECONDS=3`, `SENT_SCREEN_MS=2000`, `QUEUED_NOTICE_MS=2000`, `FG_SEND_TIMEOUT_MS=60000`, SMTP connect 15000/read 30000/write 30000, `ACTIVE_CAP=5`, `ATTENTION_CAP=10`, `RECIPIENT_CAP=6`, `RETRY_WINDOW_MS=24h`, `BACKOFF_INITIAL=15min`, `BATCH_MAX_BYTES=15MB`, `SEND_LOG_MAX=50`, `LONG_PRESS_MS=3000`, `RECIPIENT_NAME_MAX=10`, plus `CAPTURE_FAILURE_MESSAGE_MS=3000` (confirmed decision).
- `TokyoTime`: formatters `yyyy/MM/dd HH:mm`, `yyyy/MM/dd HH:mm:ss`, `yyyyMMdd_HHmmss`, all with `ZoneId.of("Asia/Tokyo")`; input epoch millis.
- `Connectivity`: `isClearlyOffline(context)` using `ConnectivityManager` (no active network with internet capability); used by the offline shortcut (ds 6.2).

## 6. Steps
1. Gradle + manifest + empty activity builds (debug and R8 release).
2. Application class + channel.
3. Constants, TokyoTime (with a JVM unit test for formats incl. non-Tokyo default timezone), Connectivity.

## 7. Done when
Debug and release APK build; app launches to a blank Compose screen in portrait; channel exists; unit tests for TokyoTime pass.
