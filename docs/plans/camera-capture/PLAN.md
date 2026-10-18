# Plan: camera-capture

Source: ds 4, 2.2 (permission), 2.3 (Capturing transitions), 3.2 (shutter). Branch: `feature/camera-capture`. Depends on: app-foundation. UI wiring into the state machine is in photo-send-flow.

## Index
1. Scope
2. Components
3. Behavior
4. Errors
5. Tests
6. Steps

## 1. Scope
Camera binding, shutter capture, resize to `cache/preview.jpg`, orientation handling, camera permission helper. Exposes results/errors to `MainViewModel`; does not own screen states.

## 2. Components (package `camera/`)
- `CameraController` (composable-friendly): binds `ProcessCameraProvider`, `Preview` (PreviewView, FILL_CENTER) and `ImageCapture` (CAPTURE_MODE_MINIMIZE_LATENCY), back camera, lifecycle-bound while the state is Camera/OutboxFull. Exposes `suspend fun capture(): Result<CaptureResult>`.
- `OrientationTracker`: `OrientationEventListener` mapped to `Surface.ROTATION_*`, continuously sets `imageCapture.targetRotation` (activity stays portrait-locked).
- `ImageProcessor.resize(rawFile, previewFile)`: decode with EXIF orientation baked into pixels, scale so long edge <= 1600 px (no upscale, keep aspect), JPEG quality 85, write `cache/preview.jpg`, delete raw. Runs on `Dispatchers.Default`/IO. Use sampled decode (`inSampleSize`) first to limit memory.
- `PreviewFiles`: paths `cache/capture_raw.jpg`, `cache/preview.jpg`; `deleteAll()` used at cold start (ds 2.3) and by やり直す/Sent/QueuedNotice.
- `CameraPermission` helper: check, request-once-per-cold-start flag (in-memory, not persisted), re-check on resume without a new dialog.

## 3. Behavior
- On shutter: caller records `capturedAtMillis = System.currentTimeMillis()` then `capture()` takes picture to `capture_raw.jpg`, then `ImageProcessor.resize`. The same preview bytes are later copied into the outbox (no second resize).
- Camera permission: requested once per cold start the first time the camera would be shown; denied or "don't ask again" -> PermissionDenied (state owned by photo-send-flow). Opening system app settings and returning re-checks only.
- Camera init/bind failure at startup: reported as the same error as capture failure; binding retried on next resume.

## 4. Errors
`ImageCaptureException`, decode/resize exception, `OutOfMemoryError`, provider/bind failure: delete partial files, log to Logcat tag `ParentPhoto/Camera` (no photo content), return `Result.failure`. The 3 s message "撮影できませんでした。もう一度お試しください" and the state change are in photo-send-flow PLAN-1.

## 5. Tests
- JVM/instrumented: `ImageProcessor` with rotated EXIF samples (orientation 1, 3, 6, 8), large (4000 px) and small (800 px, no upscale) inputs; output long edge and orientation asserted.
- Device manual: portrait and landscape-held shots produce correct orientation; permission deny/allow paths.

## 6. Steps
1. `ImageProcessor` + `PreviewFiles` with tests.
2. `CameraController` + `OrientationTracker` in a throwaway test composable.
3. `CameraPermission` helper.
