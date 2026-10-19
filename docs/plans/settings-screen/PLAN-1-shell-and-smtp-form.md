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
