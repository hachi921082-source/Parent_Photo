# photo-send-flow PLAN-2: father-facing UI

Source: ds 3.1-3.4, 2.1, 2.3, 9.9 (content descriptions), 1 (strings.xml). Package `ui/` (screens: `CameraScreen`, `PreviewScreen`, `CancelWindowScreen`, `SendingScreen`, `SentScreen`, `QueuedNoticeScreen`, `LoadingScreen`, `SetupRequiredScreen`, `PermissionDeniedScreen`; `OutboxFull` and `Capturing` are variants of `CameraScreen`).

## Index
1. Common rules
2. Strings table (strings.xml)
3. Layouts per screen (ds 3.4)
4. Settings icon long press
5. Tests
6. Implementation notes

## 1. Common rules
- Theme: Material3 default light scheme from app-foundation (`ui/theme`), tokens as in ds 3.4 (background `#FFFBFE`, onBackground `#1C1B1F`, primary `#6750A4`, onPrimary `#FFFFFF`, secondary `#625B71`, outline `#79747E`, primaryContainer `#EADDFF`, onPrimaryContainer `#21005D`, scrim `#000000`; warning `#E65100`). Buttons rounded 12 dp, badges pill. No animation or transition anywhere in these screens (instant state switch, the countdown number changes without animation; use `AnimatedContent`/`Crossfade` nowhere, set `CircularProgressIndicator` default only).
- Font scale: every father-facing screen is wrapped in `FatherTheme { ... }` which applies `CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1f))`, so 28/36/20 sp render as-is regardless of system font scale (ds 3.2, Q51). The hidden settings screen is NOT wrapped (follows the system font scale).
- Text: buttons 28 sp (centered, `maxLines = 2`), messages 36 sp (centered, onBackground unless stated), badges 20 sp. Fixed sizes, unaffected by the button size setting.
- General layout: content horizontally centered, 16 dp margins, 16 dp spacing between elements.
- Button size levels (min height, full width): 標準 56 / 大 96 / 特大 128 dp from `button_size`, default 大. Applies to やり直す, recipient buttons, 取り消す, 設定を開く. Not to the shutter (72 dp circle), settings icon, hidden settings.
- Disabled buttons use Material3 default disabled colors.
- The word "要確認" is never shown to the father.
- All strings come from `res/values/strings.xml` via `stringResource`; no literals in Kotlin.

## 2. Strings table (all in `res/values/strings.xml`, Japanese)
| Resource name | Text | Where |
|---|---|---|
| `retry_button` | やり直す | Preview |
| `no_recipients` | 送り先が登録されていません | Preview, 0 recipients |
| `cancel_window_label` | `{n}秒後に送ります` (formatted string, n = 3, 2, 1) | CancelWindow |
| `cancel_button` | 取り消す | CancelWindow |
| `sending` | 送信中... | Sending |
| `sent` | 送りました！ | Sent |
| `queued_notice` | あとで送ります | QueuedNotice |
| `badge_unsent` | 未送信 %1$d件 | Camera/OutboxFull/Capturing badge |
| `badge_attention` | 送れていません %1$d件 | Camera/OutboxFull/Capturing warning badge |
| `outbox_full_guidance` | 送れていない写真が5枚あります。ネットワークを確認してください | OutboxFull |
| `setup_required` | 設定が必要です | SetupRequired |
| `permission_denied_guidance` | カメラの使用を許可してください | PermissionDenied |
| `open_settings_button` | 設定を開く | PermissionDenied |
| `capture_failure` | 撮影できませんでした。もう一度お試しください | Camera failure message |
| `snackbar_saved` | 保存しました | Settings Snackbar (used by settings-screen PLAN-1; defined here in the shared table) |
| `snackbar_waiting` | 順番待ちです。しばらくお待ちください | Settings Snackbar (settings-screen PLAN-4) |
| `cd_shutter` | 撮影 | content description |
| `cd_settings` | 設定 | content description |
| `cd_progress` | 処理中 | content description of progress indicators |
Notification texts and the channel name are owned by attention-notifications; settings texts by settings-screen plans.

## 3. Layouts per screen (ds 3.4)
- Loading: full-screen `background`, nothing else, no settings icon, no timeout.
- SetupRequired: `background`; `setup_required` (36 sp) centered vertically and horizontally; settings icon top-right (color not specified in ds for this screen: use the Preview icon color `#49454F`, see report note).
- PermissionDenied: `background`; `permission_denied_guidance` (36 sp) centered above; 設定を開く button (filled primary, father size level, full width, 16 dp margins) below it, opens `ACTION_APPLICATION_DETAILS_SETTINGS`; settings icon top-right (`#49454F`).
- Camera: full-screen `PreviewView`. Shutter: 72 dp circle, bottom-center, 32 dp above the bottom edge, white fill, `#1C1B1F` 4 dp ring, content description 撮影. Badges top-left, below the status bar inset, 16 dp margin: dark pill (`#000000` at 60 percent alpha, white 20 sp text, padding 12 dp horizontal / 6 dp vertical); "未送信 N件" (shown when N > 0) above, "送れていません M件" (shown when M > 0) below, 8 dp apart; the warning badge uses opaque `#E65100` with white text. Counts never abbreviated. Settings icon top-right, white at 70 percent alpha. Capture-failure message: centered box, `#000000` 60 percent alpha background, white 36 sp text, 16 dp padding, 16 dp rounded corners, visible while `captureFailureMessageVisible` (3000 ms, photo-send-flow PLAN-1).
- OutboxFull: same as Camera (feed live); shutter disabled (40 percent alpha, ignores taps); guidance (36 sp, white) bottom-center directly above the shutter (16 dp gap) in a `#000000` 60 percent alpha box with 16 dp padding.
- Capturing: Camera layout with the shutter visible but disabled (40 percent alpha); 32 dp white `CircularProgressIndicator` (content description 処理中) centered over the feed; badges as in Camera; no settings icon, no other buttons.
- Preview: `background`. Photo area at top, `ContentScale.Fit`, taking all remaining height but at least 30 percent of the screen height. Below it a `LazyColumn` of recipient buttons (filled primary, onPrimary text, full width, 16 dp spacing; label = display name, `maxLines = 2`, button grows in height; registration order; height limited to the space left after the photo minimum, scrolls), then やり直す pinned at bottom (outlined, `secondary` text, `outline` border, same size level, 16 dp margin). Settings icon top-right (`#49454F`) over the photo. Zero recipients: `no_recipients` (36 sp, centered) in place of the list.
- CancelWindow: Preview layout with the photo dimmed (scrim 50 percent); no recipient list and no やり直す; centered label `{n}秒後に送ります` (36 sp) below the photo; 取り消す pinned at bottom (filled primary, father size level, full width, 16 dp margin); no settings icon.
- Sending: same dimmed-photo layout; centered 送信中... (36 sp); no buttons, no settings icon.
- Sent: full-screen `primaryContainer`, centered 送りました！ (36 sp, `onPrimaryContainer`), no icon.
- QueuedNotice: full-screen `background`, centered あとで送ります (36 sp, onBackground), no icon.
- Settings icon visible only on Camera, OutboxFull, Preview, SetupRequired, PermissionDenied.

## 4. Settings icon long press
Gear icon 24 dp visual, 48 dp touch target, top-right, content description 設定. Tap does nothing visible. Hold 3000 ms (`LONG_PRESS_MS`) continuously opens Settings; movement beyond touch slop cancels. Implement via `pointerInput` with `awaitFirstDown` + timeout at 3000 ms and slop check. No PIN, no haptic, no visual feedback (decided, ds 9.8).

## 5. Tests
Compose UI tests (`createComposeRule`), one per screen listed in section 3 (Loading, SetupRequired, PermissionDenied, Camera, OutboxFull, Capturing, Preview, CancelWindow, Sending, Sent, QueuedNotice):
- exact strings shown; no 要確認 text anywhere;
- layout assertions (bounds): shutter 72 dp and 32 dp above bottom; badges top-left, 8 dp apart, warning badge present only when M > 0; failure message centered; OutboxFull guidance 16 dp above the shutter and shutter disabled; Preview photo area >= 30 percent of height, やり直す pinned at bottom, recipient list scrolls; CancelWindow/Sending have no recipient list, no やり直す, no settings icon; Sent/QueuedNotice contain only the message;
- colors (semantic checks where feasible, e.g. via screenshot pixel sampling): warning badge `#E65100`, Sent background `#EADDFF`;
- font scale: set `LocalDensity` with `fontScale = 2f` outside `FatherTheme`; assert text bounds equal those at `fontScale = 1f` (28/36/20 sp unchanged);
- button sizes follow the setting (56/96/128 dp); recipient name of 10 full-width characters wraps to 2 lines; 6 recipients scrollable with やり直す pinned;
- long press 2.9 s does nothing, 3.0 s opens; slop cancels;
- no animation: state switch is immediate under the test clock (no `AnimatedContent`).
Manual check on a real device for readability.


## 6. Implementation notes
- Files (`ui/`): `FatherComponents.kt` (text sizes, `OverlayMessage`, `FatherFilledButton`/`FatherOutlinedButton`, `SettingsIcon` long press, `Badges`, `PhotoView`, `ProgressMark`), `CameraScreen.kt` (Camera/OutboxFull/Capturing; `cameraFeed` slot = `CameraView` with `onBindFailure = onCameraBindFailed`), `PreviewScreens.kt` (Preview, CancelWindow, Sending), `SimpleScreens.kt` (Loading, SetupRequired, PermissionDenied, Sent, QueuedNotice); `MainScreen` wraps them in `FatherTheme` and keeps Camera/OutboxFull/Capturing on one call site so the camera stays bound while capturing. Gear icon = `res/drawable/ic_settings.xml` (no icons library).
- Line height: father texts use `lineHeight = 1.3.em` (the theme default 24 sp made 2-line 36 sp text overlap).
- Q65: `MainUiModel.outboxFullNoticeVisible` (3000 ms, `OUTBOX_FULL_NOTICE_MS`) shows `outbox_full_guidance` in the dark rounded box centered over Preview.
- Badges and the settings icon use a plain 16 dp / 4 dp margin from the content top (the app is not edge-to-edge, so the content already starts below the status bar).
- Preview list max height = 70 percent of the screen minus the やり直す area (button size + 48 dp), so the photo keeps at least 30 percent.
- Tests: `androidTest/ui/FatherScreensTest.kt` (Compose UI tests, run on emulator API 26).
