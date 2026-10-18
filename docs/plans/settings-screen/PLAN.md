# Plan: settings-screen (index)

Source: ds 9, 3.3, 8, 12. Branch: `feature/settings-screen`. Depends on: app-foundation, settings-storage. Parts BLOCKED on pending plans: recipient-delete queued counts, test send, unsent list, send log, resume-on-save.

## Sub-plans
- `PLAN-1-shell-and-smtp-form.md`: route, app bar, sections layout, sender name + SMTP form, password field, validation, dirty confirm, button size.
- `PLAN-2-recipients.md`: recipient list, add/edit dialog, delete confirm.
- `PLAN-3-test-send.md`: test send section and dialogs.
- `PLAN-4-unsent-list-and-log.md`: 未送信一覧 and 送信ログ screens.

## Common rules
Standard Material3 sizes (not father-facing sizes). Japanese labels per ds 9.1 (accepted `[ASSUMPTION]` wording). Single `SettingsViewModel`. Entry via the 3 s long press (photo-send-flow PLAN-2). Back returns to the previous main state and re-evaluates precedence.

## Order
PLAN-1 -> PLAN-2 -> PLAN-3 and PLAN-4 (PLAN-3/4 completed after the pending plans; use fakes until then).
