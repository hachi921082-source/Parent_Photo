# Plan: settings-screen (index)

Source: ds 9 (incl. 9.8, 9.9), 3.3, 8, 12. Branch: `feature/settings-screen`. Depends on: app-foundation, settings-storage. Parts BLOCKED on pending plans: recipient-delete queued counts, test send, unsent list, send log, resume-on-save.

## Sub-plans
- `PLAN-1-shell-and-smtp-form.md`: route, app bar, sections layout, sender name + SMTP form, password field, validation, dirty confirm, button size.
- `PLAN-2-recipients.md`: recipient list, add/edit dialog, delete confirm.
- `PLAN-3-test-send.md`: test send section and dialogs.
- `PLAN-4-unsent-list-and-log.md`: 未送信一覧 and 送信ログ screens.

## Common rules
Standard Material3 sizes (not father-facing sizes). Settings screens follow the system font scale (no `fontScale = 1f` override, ds 3.2). All strings in `res/values/strings.xml`; content descriptions per ds 9.9 (settings icon 設定, edit 編集, delete 削除, password toggle パスワードを表示 / パスワードを隠す, progress indicators 処理中). Japanese labels per ds 9.1 (accepted `[ASSUMPTION]` wording). Single `SettingsViewModel`. Entry via the 3 s long press (photo-send-flow PLAN-2). Back returns to the previous main state and re-evaluates precedence.

## Routes and dismissal rules (ds 9 intro, Q58)
- Routes (navigation-compose, a nested `settings` graph; `SettingsViewModel` is scoped to the graph's entry so the form state, including unsaved edits, survives sub-screen navigation): `settings` (the scrollable main screen, app bar "設定" with back arrow), `settings/unsent` (app bar title 未送信一覧, back arrow), `settings/log` (app bar title 送信ログ, back arrow). Back or the app-bar arrow from a sub-screen returns to `settings` unchanged. Each of the three destinations hosts its own `SnackbarHost`.
- Dialogs are modal: the recipient add/edit dialog is dismissed by outside tap or back WITHOUT saving and without confirmation; confirm dialogs (recipient delete, unsent-item delete) dismiss as キャンセル (outside tap, back); the dirty-form dialog has no キャンセル button, its outside tap/back dismissal is treated as 戻る (stay); the test-send progress dialog cannot be dismissed (`dismissOnBackPress = false`, `dismissOnClickOutside = false`); the test-send result dialog is dismissed only by its OK button.

## Order
PLAN-1 -> PLAN-2 -> PLAN-3 and PLAN-4 (PLAN-3/4 completed after the pending plans; use fakes until then).
