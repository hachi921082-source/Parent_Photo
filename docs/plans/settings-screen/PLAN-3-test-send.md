# settings-screen PLAN-3: test send

Source: ds 9.5, 5 (test mail), 6.3, 9.4. Package `ui/settings/`. BLOCKED on pending plan mail-composition-smtp (sender, classification) for real behavior; UI and ViewModel logic can be built against a fake `SmtpSender`.

## Index
1. Section UI
2. Flow
3. Result texts
4. Tests

## 1. Section UI
- Recipient dropdown (from the recipient list). Selection is transient ViewModel / `rememberSaveable` state, NOT persisted in DataStore. The first recipient is preselected; if the selected recipient is deleted meanwhile, the first remaining one is selected.
- "テスト送信" button, with a hint line below it: 12 sp, gray `#49454F` (follows the system font scale like the rest of settings).
- Disabled states and hint priority (first matching wins): (1) no recipients: button disabled, hint "送り先を登録してください"; (2) otherwise saved SMTP settings invalid/unset (ds 8.3 SetupRequired condition): disabled, hint "先にメール設定を保存してください"; (3) otherwise SMTP form dirty: disabled, hint "先に保存してください". Otherwise enabled, no hint.

## 2. Flow
Send text-only test mail (subject "テスト送信", body "これはテスト送信です。", no attachment) with saved settings on `Dispatchers.IO`, same timeouts, 60 s cap. Modal progress dialog "送信中..." (cannot be dismissed, back ignored, test button disabled meanwhile; progress indicator content description 処理中). Then a result dialog with an "OK" button; it is dismissed only by OK (outside tap and back ignored). Success logs TEST_OK and triggers resume (ds 7.5) via the `OutboxRepository` contract; no extra text is shown when resume was triggered. Failure logs TEST_FAILED with the code. Log entries show the chosen recipient name.

## 3. Result texts (strings.xml)
- Success: 送信できました
- AUTH: ログインできませんでした。送信元アドレスとアプリパスワードを確認してください
- NETWORK/TIMEOUT: ネットワークに接続できませんでした
- REJECTED: 宛先アドレスが受け付けられませんでした
- Other: 送信に失敗しました（{code}） where `{code}` is the log code string (OTHER).

## 4. Tests
Fake sender returning each result: dialog text mapping; progress dialog blocks back and outside tap; result dialog dismissed only by OK; first recipient preselected, reselected after deleting the selected one; button disabled states and hint priority (including overlaps, e.g. no recipients and dirty form shows hint 1); 60 s cap with virtual time. Real send verified in the acceptance run.
