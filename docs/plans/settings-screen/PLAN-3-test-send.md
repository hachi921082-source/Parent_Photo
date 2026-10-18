# settings-screen PLAN-3: test send

Source: ds 9.5, 5 (test mail), 6.3, 9.4. Package `ui/settings/`. BLOCKED on pending plan mail-composition-smtp (sender, classification) for real behavior; UI and ViewModel logic can be built against a fake `SmtpSender`.

## Index
1. Section UI
2. Flow
3. Result texts
4. Tests

## 1. Section UI
Recipient dropdown (from recipient list; selection is transient ViewModel / `rememberSaveable` state, NOT persisted) and "テスト送信" button. Disabled with hint "送り先を登録してください" when no recipients; disabled with hint "先に保存してください" while the SMTP form is dirty.

## 2. Flow
Send text-only test mail (subject "テスト送信", body "これはテスト送信です。", no attachment) with saved settings on `Dispatchers.IO`, same timeouts, 60 s cap. Modal progress dialog "送信中..." (no cancel, back ignored, test button disabled). Then result dialog with "OK". Success logs TEST_OK and triggers resume (ds 7.5) via the `OutboxRepository` contract; failure logs TEST_FAILED with code.

## 3. Result texts
- Success: 送信できました
- AUTH: ログインできませんでした。送信元アドレスとアプリパスワードを確認してください
- NETWORK/TIMEOUT: ネットワークに接続できませんでした
- REJECTED: 宛先アドレスが受け付けられませんでした
- Other: 送信に失敗しました（{code}）

## 4. Tests
Fake sender returning each result: dialog text mapping; progress dialog blocks back; button disabled states and hints; 60 s cap with virtual time. Real send verified in the acceptance run.
