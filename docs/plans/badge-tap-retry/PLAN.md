# badge-tap-retry

Source: GitHub issue #1. Branch `feature/badge-tap-retry`. ds sections: 3.4, 6.2 (and 3.1 strings).

## Behavior
- Tap on the "未送信 N件" badge (Camera / OutboxFull only; ignored in every other state).
- `MainViewModel.onUnsentBadgeTapped()` -> `SendFlowOutbox.retryNow(): Boolean`.
- `retryNow()` (real impl in `RepositoryOutbox`, MainViewModelFactory.kt): `ConnectivityChecker.isClearlyOffline()` -> return false (no send); else `WorkScheduler.triggerNow()` and return true.
- false -> `MainUiModel.offlineNoticeVisible = true` for 3000 ms (`Constants.OFFLINE_NOTICE_MS`, `DeadlineTimer`, same style as the capture failure message; repeat tap restarts the 3000 ms). true -> nothing shown.
- "送れていません M件" badge: not tappable, unchanged.

## UI
- `Badges(..., onUnsentTap)`: only the unsent badge gets `clickable(role = Button)`. Pill size/position unchanged. Touch area: Compose's default minimum touch target (48 dp) expands the hit area; the expansion stays inside the 16 dp outer padding and the 8 dp gap.
- `CameraScreen(offlineNoticeVisible, onUnsentBadgeTap)`: notice via existing `OverlayMessage`, centered (same place and style as the capture failure message).

## Files
ui/MainPorts.kt, ui/MainViewModelFactory.kt, ui/MainViewModel.kt, ui/UiState.kt, ui/MainScreen.kt, ui/CameraScreen.kt, ui/FatherComponents.kt, util/Constants.kt, res/values/strings.xml.

## Tests
- JVM (MainViewModelTest): online tap -> retryNow called, no notice; offline tap -> notice exactly 3000 ms; tap in Preview ignored. `FakeOutbox.retryNow` added.
- androidTest (FatherScreensTest): only the unsent badge is clickable; notice shown when `offline = true`. Compile-only (no device).

## Implementation notes
- Wording: "ネットにつながっていません" (string `offline_notice`), 3000 ms, centered overlay.
- Touch target relies on Compose's default 48 dp minimum touch target, not on layout growth, so existing geometry tests (8 dp gap, 16 dp margins) stay valid. Not verified on a device.
- Online-but-no-internet passes the check; the send then fails and items stay queued (no harm).
