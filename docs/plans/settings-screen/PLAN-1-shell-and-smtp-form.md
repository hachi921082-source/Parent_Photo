# settings-screen PLAN-1: shell and SMTP form

Source: ds 9.1 (sections 2-4, 6), 9.2, 9.3, 9.4. Package `ui/settings/`.

## Index
1. Shell
2. Form fields and state
3. Password field
4. Validation
5. Save and dirty handling
6. Button size
7. Tests

## 1. Shell
Scrollable single screen, top app bar "設定" with back arrow. Sections in order: 送り先 (PLAN-2), 送信者名, メール設定 (SMTP), ボタンの大きさ, テスト送信 (PLAN-3), links 未送信一覧 and 送信ログ (PLAN-4).

## 2. Form fields and state
Fields: "送信者名（例: お父さん）", "送信元メールアドレス", "アプリパスワード", "SMTPホスト", "SMTPポート". Initialized from `SettingsRepository`. Form state (current values, dirty flag, per-field errors) in `SettingsViewModel`, retained across rotation-free recomposition via `rememberSaveable`/ViewModel. One "保存" button for sender name + SMTP; enabled when dirty and valid.

## 3. Password field (ds 9.2)
- Not saved: empty masked field with show/hide toggle; paste allowed; all whitespace removed on change.
- Saved: disabled field showing `●●●●●●●●●●●●●●●●` plus "変更" button; tapping clears to an empty editable masked field. Untouched keeps the saved password. The saved value is never revealed or logged.

## 4. Validation (on 保存; error text under the field; nothing saved on any error)
Address valid email; password exactly 16 chars after whitespace removal ("16文字で入力してください"), required when none saved; host non-empty after trim; port integer 1-65535; sender name unvalidated, trimmed, CR/LF removed. Use `Validators` from settings-storage.

## 5. Save and dirty handling
- 保存: `saveSmtp(...)` then trigger resume (ds 9.4/7.5: ATTENTION items with reason AUTH, PERMANENT, RETRY_EXPIRED) through the `OutboxRepository` contract (no-op fake until outbox plans exist).
- Back or app-bar arrow with unsaved changes: dialog "保存していない変更があります。破棄しますか？" buttons "破棄" (leave, discard) and "戻る" (stay). System back follows the same path.

## 6. Button size
Radio group 標準 / 大 / 特大; saved immediately; father-facing screens react via the settings flow.

## 7. Tests
Compose/JVM: each validation error; password flows (new, saved, change); dirty dialog both buttons; 保存 disabled when invalid or clean; save calls repository then resume hook.
