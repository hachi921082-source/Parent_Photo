# settings-screen PLAN-2: recipients

Source: ds 9.1 (section 1), 9.3, 9.9, 8.1, 7.5 (RECIPIENT_DELETED behavior, pending). Package `ui/settings/`.

## Index
1. List
2. Add/edit dialog
3. Delete
4. Blocked parts
5. Tests

## 1. List
Header "送り先 n/6". Rows in registration order: display name; the email on one line below the name (ellipsis if long); edit icon button (content description "編集") and delete icon button (content description "削除"). The "追加" button sits below the list, disabled at 6 recipients. Saved immediately to DataStore (no 保存 button needed, independent of the SMTP form dirty state).

## 2. Add/edit dialog
Dialog titles: "送り先を追加" (add) / "送り先を編集" (edit). Fields "表示名", "メールアドレス"; buttons "保存" / "キャンセル". "保存" is always enabled; it validates on press, shows errors under the fields, and the dialog stays open on error. Validation: name non-empty after trim ("表示名を入力してください"), at most 10 chars ("10文字以内で入力してください"); email trimmed, `Patterns.EMAIL_ADDRESS` ("メールアドレスの形式が正しくありません"), at most `EMAIL_MAX` = 254 (input blocked); no duplicate check. Keyboards: name = text (IME Next), email = email keyboard (IME Done). Edit keeps list position and id. Add generates UUID. Dismissal by outside tap or back discards without saving and without confirmation.

## 3. Delete
Confirm dialog, buttons "削除" / "キャンセル" (outside tap or back = キャンセル): no queued photos -> "この宛先を削除しますか？"; N queued (WAITING or ATTENTION) -> "この宛先を削除しますか？ 送れていない写真が{N}枚あります。削除すると、その写真は送られなくなります（未送信一覧に残ります）。". On delete: remove recipient, then call the `OutboxRepository` contract to move that recipient's items to 要確認 with reason RECIPIENT_DELETED (outbox plans).

## 4. Blocked parts (pending plans)
Queued-photo count per recipient and the RECIPIENT_DELETED transition need `OutboxRepository`; use a fake returning 0 and a no-op until then. Do not define the transition here.

## 5. Tests
Add up to 6 then 追加 disabled; dialog titles for add/edit; every validation message; 保存 always enabled and dialog stays open on error; outside tap/back discards; edit keeps position; delete dialog wording by count (fake) and キャンセル dismissal; content descriptions 編集 / 削除 present; recipient name of 10 characters accepted, 11 rejected.
