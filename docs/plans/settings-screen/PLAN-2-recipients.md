# settings-screen PLAN-2: recipients

Source: ds 9.1 (section 1), 9.3, 8.1, 7.5 (RECIPIENT_DELETED behavior, pending). Package `ui/settings/`.

## Index
1. List
2. Add/edit dialog
3. Delete
4. Blocked parts
5. Tests

## 1. List
Header "送り先 n/6". Rows in registration order: display name, email, edit icon, delete icon. "追加" button disabled at 6. Saved immediately to DataStore (no 保存 button needed, independent of the SMTP form dirty state).

## 2. Add/edit dialog
Fields "表示名", "メールアドレス"; buttons "保存" / "キャンセル". Validation: name non-empty after trim ("表示名を入力してください"), at most 10 chars ("10文字以内で入力してください"); email trimmed, `Patterns.EMAIL_ADDRESS` ("メールアドレスの形式が正しくありません"); no duplicate check. Edit keeps list position and id. Add generates UUID.

## 3. Delete
Confirm dialog, buttons "削除" / "キャンセル": no queued photos -> "この宛先を削除しますか？"; N queued (WAITING or ATTENTION) -> "この宛先を削除しますか？ 送れていない写真が{N}枚あります。削除すると、その写真は送られなくなります（未送信一覧に残ります）。". On delete: remove recipient, then call the `OutboxRepository` contract to move that recipient's items to 要確認 with reason RECIPIENT_DELETED (outbox plans).

## 4. Blocked parts (pending plans)
Queued-photo count per recipient and the RECIPIENT_DELETED transition need `OutboxRepository`; use a fake returning 0 and a no-op until then. Do not define the transition here.

## 5. Tests
Add up to 6 then 追加 disabled; every validation message; edit keeps position; delete dialog wording by count (fake); recipient name of 10 characters accepted, 11 rejected.
