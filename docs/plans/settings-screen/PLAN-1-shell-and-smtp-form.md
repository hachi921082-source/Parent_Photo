# settings-screen PLAN-1: shell and SMTP form

Source: ds 9 intro, 9.1 (sections 2-4, 6), 9.2, 9.3, 9.4, 9.8, 9.9. Package `ui/settings/`.

## Index
1. Shell
2. Form fields and state
3. Password field
4. Validation and messages
5. Save and dirty handling
6. Button size
7. Tests
8. Implementation notes

## 1. Shell
Scrollable single screen (`settings` route, see PLAN.md), top app bar "設定" with back arrow, Material3 `Scaffold` with a `SnackbarHost`. Sections in order: 送り先 (PLAN-2), 送信者名, メール設定 (SMTP), ボタンの大きさ, テスト送信 (PLAN-3), links 未送信一覧 and 送信ログ (PLAN-4). Follows the system font scale. All texts in `strings.xml`.

## 2. Form fields and state
- Fields (all single-line): "送信者名（例: お父さん）", "送信元メールアドレス", "アプリパスワード", "SMTPホスト", "SMTPポート". Initialized from `SettingsRepository`; host and port default to real prefilled text `smtp.gmail.com` / `465` (not hints).
- Keyboards: sender name = text; 送信元メールアドレス = email keyboard; アプリパスワード = password keyboard; SMTPホスト = text keyboard; SMTPポート = number keyboard. IME action: Next between fields in screen order (送信者名, アドレス, パスワード, ホスト), Done on the last (ポート). Autofill is off for the password field (`importantForAutofill = no`; in Compose, clear autofill semantics on that field).
- Maximum lengths: 送信者名 `SENDER_NAME_MAX = 20` characters (further input blocked), email 254 (`EMAIL_MAX`, input blocked), host `HOST_MAX = 253`.
- Form state (current values, dirty flag, per-field errors, scroll/focus requests) is ViewModel-held in `SettingsViewModel` (survives rotation and sub-screen navigation); `rememberSaveable` only for purely UI state.
- One "保存" button for sender name + SMTP. It is enabled whenever the form is dirty (regardless of validity) and disabled when clean. Pressing it validates (section 4).

## 3. Password field (ds 9.2)
- Not saved: empty masked field with show/hide toggle (content descriptions パスワードを表示 / パスワードを隠す); paste allowed; all whitespace (spaces, newlines) removed on change.
- Saved: disabled field showing `●●●●●●●●●●●●●●●●` plus "変更" button. Tapping 変更 clears to an empty editable masked field with a "キャンセル" text button beside it; キャンセル restores the saved-password state (if nothing else changed, the form is clean again). After 変更 the form counts as dirty; an empty field is invalid on 保存 ("16文字で入力してください"). Not tapping 変更 keeps the saved password. The saved value is never revealed or logged.

## 4. Validation and messages (on 保存; error text under the field; nothing saved on any error)
- 送信元メールアドレス: trimmed, valid email (`android.util.Patterns.EMAIL_ADDRESS`): "メールアドレスの形式が正しくありません".
- アプリパスワード: exactly 16 characters after whitespace removal: "16文字で入力してください"; required when none saved or after 変更.
- SMTPホスト: non-empty after trim: "SMTPホストを入力してください"; no whitespace inside: "SMTPホストの形式が正しくありません"; at most 253 characters (`HOST_MAX`).
- SMTPポート: integer 1-65535: "1〜65535の数字を入力してください".
- 送信者名: unvalidated, may be empty, trimmed, CR/LF removed on save.
- Use `Validators` from settings-storage (extend with host/port messages if missing; messages in `strings.xml`).
- On error: all errors are shown at once; the screen scrolls to and focuses the first field (screen order) that has an error (`BringIntoViewRequester` + `FocusRequester`).

## 5. Save and dirty handling
- 保存 with no error: `saveSmtp(...)`, then show the Material3 Snackbar "保存しました" (`SnackbarDuration.Short`, no action), then trigger resume (ds 9.4/7.5: ATTENTION items with reason AUTH, PERMANENT, RETRY_EXPIRED) through the `OutboxRepository` contract (no-op fake until outbox plans exist). The form becomes clean.
- Back (app-bar arrow or system back) with unsaved changes in 送信者名/SMTP fields: dialog "保存していない変更があります。破棄しますか？" with "破棄" (leave, discard) and "戻る" (stay); system back follows the same path (`BackHandler` while dirty). Back from a clean form leaves directly.

## 6. Button size
Radio group 標準 / 大 / 特大; saved immediately (not part of the dirty form); father-facing screens react via the settings flow.

## 7. Tests
Compose/JVM: each validation error text and max-length behavior; 保存 enabled when dirty (even if invalid) and disabled when clean; invalid press saves nothing, shows errors, scrolls to and focuses the first error; valid press saves, shows Snackbar "保存しました", calls resume hook; password flows (new, saved, 変更, 変更 then キャンセル restores, 変更 then empty is invalid, whitespace stripped); keyboard types and IME actions per field; dirty dialog both buttons and system back; settings text scales with font scale 2f.

## 8. Implementation notes
Code: `ui/settings/SmtpForm.kt` (form state, limits, validation, keyboards), `SettingsViewModel.kt`, `SettingsViewModelFactory.kt`, `SettingsScreen.kt` (main route), `SettingsNavHost.kt` (routes; PLAN-4 sub-screens are empty stubs). Seams: `TODO(PLAN-2)` / `TODO(PLAN-3)` in `SettingsScreen.kt`, `TODO(PLAN-4)` in `SettingsNavHost.kt`.
- Deviation (scoping): `SettingsViewModel` is Activity-scoped (`viewModel()` in `MainScreen`), not scoped to the nested graph entry. Closing settings (`SettingsNavHost` close path) calls `onClosed()`, which discards the form; the next open re-reads saved values (`onOpened()`, idempotent while loaded). Reason: the NavHost leaves composition when settings closes without popping its entries, so graph-scoped stores would not be cleared reliably. Same user-visible behavior (edits survive sub-screen navigation and configuration changes).
- Ports instead of plan names: `SettingsScreenStore` (wraps `SettingsRepository`, crypto on `Dispatchers.IO`) and `SetupResumer` (real: `OutboxRepository.resumeAfterSetupFix()`, no fake needed since it exists).
- Saved-password state = a decryptable password exists (`readPassword() != null`), so an undecryptable cipher (key lost, ds 8.2) shows the empty NEW field instead of the mask.
- `MainViewModel.reevaluate()` now skips while settings is open. Found on device: saving valid SMTP settings cleared SetupRequired and the camera permission dialog appeared over the settings screen. Precedence is still evaluated by `onSettingsClosed()` (ds 2.2 "after returning from Settings").
- Autofill: Compose 1.7 text fields are not exposed to the Android autofill framework unless registered explicitly, so the password field is excluded without extra semantics.
- Visual choices (not in ds): section headers `titleMedium`; 保存 is a full-width filled button below SMTPポート; 変更 and キャンセル are `TextButton`s right of the password field; the show/hide toggle is also shown in the CHANGING state; 未送信一覧 / 送信ログ are full-width outlined buttons at the bottom; back-arrow content description "戻る" (`cd_back`, not listed in ds 9.9); the dirty dialog has no title; a field's error text clears when that field is edited (re-validated on the next 保存); input over a max length is cut at the limit (paste keeps the first N characters); no transition animations between routes; recipients and test-send sections render nothing until PLAN-2/3.
- Save failure (DataStore/Keystore exception): logged by class name, input kept (form stays dirty), Snackbar "保存できませんでした" (`SnackbarDuration.Short`, no action) [CONFIRMED by the user, Q70].
- Q70 (user decision): back-arrow description "戻る", field error clears on edit, and the visual choices above are confirmed as built.
