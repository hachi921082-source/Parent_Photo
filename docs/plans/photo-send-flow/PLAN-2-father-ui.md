# photo-send-flow PLAN-2: father-facing UI

Source: ds 3.1-3.3, 2.1, 2.3. Package `ui/` (screens: `CameraScreen`, `PreviewScreen`, `CancelWindowScreen`, `SendingScreen`, `SentScreen`, `QueuedNoticeScreen`, `SetupRequiredScreen`, `PermissionDeniedScreen`, `LoadingScreen`).

## Index
1. Common rules
2. Screens
3. Settings icon long press
4. Tests

## 1. Common rules
- Text: buttons 28 sp, messages 36 sp (fixed `sp`, unaffected by the button size setting). Badges 20 sp.
- Button size levels (min height, full width): 標準 56 / 大 96 / 特大 128 dp from `button_size`, default 大. Applies to やり直す, recipient buttons, 取り消す, 設定を開く. Not to the shutter (72 dp circular, content description 撮影), settings icon, or hidden settings.
- The word "要確認" is never shown to the father.
- Strings (exact): やり直す; 送り先が登録されていません; `{n}秒後に送ります` (n=3,2,1); 取り消す; 送信中...; 送りました！; あとで送ります; 未送信 N件; 送れていません M件; 送れていない写真が5枚あります。ネットワークを確認してください; 設定が必要です; カメラの使用を許可してください; 設定を開く; 撮影できませんでした。もう一度お試しください. Keep them in one `Strings.kt`/resource file.

## 2. Screens
- Loading: blank, no buttons, no settings icon.
- Camera: full-screen PreviewView, one shutter, badges top-left, settings icon top-right. Badge "未送信 N件" (neutral) shown when active N > 0; "送れていません M件" (warning color, orange/red) when attention M > 0. Failure message (36 sp, centered) overlays for 3 s.
- OutboxFull: Camera variant, shutter disabled, guidance text, badges, settings icon.
- Capturing: shutter disabled, small progress indicator, no other buttons, no settings icon.
- Preview: photo with `ContentScale.Fit`; recipient `LazyColumn` (max 6, registration order, label = name, `maxLines=2`, button grows in height) above やり直す pinned at bottom; settings icon top-right; 0 recipients -> text instead of list.
- CancelWindow: countdown label + 取り消す; other buttons disabled; no settings icon.
- Sending: "送信中..." all buttons disabled, no settings icon. Sent / QueuedNotice: message only.
- SetupRequired: text only, settings icon visible. PermissionDenied: guidance + 設定を開く (`ACTION_APPLICATION_DETAILS_SETTINGS`), settings icon visible.
- Settings icon visible only on Camera, OutboxFull, Preview, SetupRequired, PermissionDenied.

## 3. Settings icon long press
Gear icon 24 dp visual, 48 dp touch target, top-right. Tap does nothing visible. Hold 3000 ms (`LONG_PRESS_MS`) continuously opens Settings; movement beyond touch slop cancels. Implement via `pointerInput` with `awaitFirstDown` + timeout at 3000 ms and slop check. No PIN, no haptic.

## 4. Tests
Compose UI tests: each screen shows exact strings; button sizes follow setting; long press 2.9 s does nothing, 3.0 s opens; slop cancels; recipient name of 10 full-width characters wraps to 2 lines; 6 recipients scrollable with やり直す pinned. Manual check on a real device for readability.
