# FamilyPhoto

スマホで写真を送ったりするのが苦手な高齢者向けの Android アプリです。撮った写真をボタン一つで家族にメール送信できます。

## 目次

1. 特徴
2. 画面の流れ
3. 隠し設定画面
4. 送信のしくみ
5. 動作環境
6. ビルド方法
7. 初期設定（Gmail）
8. セキュリティ上の注意
9. ディレクトリ構成
10. ドキュメント
11. ライセンス
12. Issue と Pull Request について

## 1. 特徴

- 起動するとすぐカメラ。大きなシャッターボタン一つで撮影します。
- 撮った写真を確認し、送り先の名前が書かれた大きなボタンを押すだけで送信します。
- 送信直後の 3 秒間は「取り消す」で中止できます。
- 圏外や送信失敗時は端末内の未送信箱に保存し、通信が戻ると自動で再送します。
- サーバーは不要です。アプリが SMTP（Gmail など）で直接メールを送ります。
- 文字は大きく固定（端末の文字サイズ設定に左右されません）。ボタンの大きさは 3 段階から選べます。
- 画面の文言は日本語です。

## 2. 画面の流れ

```
カメラ → 確認（「やり直す」か送り先ボタン） → 取り消し猶予（3秒） → 送信中... → 送りました！ → カメラ
                                                                    └ 失敗・圏外 → あとで送ります → カメラ（「未送信 N件」表示）
```

- 送り先は最大 6 件。4 件以上では写真を大きく保つためボタンを詰めて表示します。
- 未送信が 5 件たまるとシャッターを止め、ネットワークの確認を促します。
- 自動再送で送れなかったものは「要確認」として通知し、設定画面から確認できます。
- カメラ画面には通信状態（「撮影してすぐ送ります」／「現在圏外なので後で送ります」）を表示します。

## 3. 隠し設定画面

画面隅の小さな設定アイコンを **3 秒長押し** すると開きます（家族が設定する想定です）。

- 送り先の追加・編集・削除（表示名＋メールアドレス、最大 6 件）
- 送信者名（メール本文と差出人名に使用）
- SMTP 設定：送信元アドレス、アプリパスワード、ホスト（既定 `smtp.gmail.com`）、ポート（既定 465 / SSL）
- 親向けボタンの大きさ（標準／大／特大）
- テスト送信
- 未送信一覧と送信ログ

## 4. 送信のしくみ

- 撮影直後に長辺 1600 px・JPEG 品質 85 に縮小し、回転情報を画素に反映します。
- JavaMail（`com.sun.mail:android-mail`）で添付付きメールを組み立て、`Dispatchers.IO` 上で SMTP 送信します。
- 件名は「写真が届きました」、本文は「{送信者名}さんから写真が届きました。」と撮影日時（日本時間）です。
- 失敗した写真はアプリ専用領域の未送信箱（Room）に入り、WorkManager が送り先ごとにまとめて再送します。
- 写真はクラウドに保存しません。送信完了・やり直し・未送信箱からの削除で端末から消えます。

## 5. 動作環境

- Android 8.0（API 26）以上
- 縦画面固定（写真の向きは端末の持ち方に従います）
- 送信元として SMTP 送信できるメールアカウント（Gmail の 2 段階認証＋アプリパスワードを想定）

## 6. ビルド方法

使用技術：Kotlin / Jetpack Compose / CameraX / DataStore / Room / WorkManager / JavaMail

必要なもの：

- JDK 17 以上（Android Studio 同梱の JBR で可）
- Android SDK（compileSdk / targetSdk 35）
- Gradle 8.9（このリポジトリには Gradle Wrapper を含めていません）

```bash
# デバッグ版
gradle :app:assembleDebug

# リリース版（R8 有効）
gradle :app:assembleRelease

# テスト
gradle :app:testDebugUnitTest          # JVM テスト
gradle :app:connectedDebugAndroidTest  # 端末／エミュレーター上のテスト
```

Android Studio でプロジェクトを開いてビルドすることもできます。`local.properties`（SDK の場所）は各自の環境で用意してください。

> リリース版は Android のデバッグ鍵で署名する設定です。配布する場合は各自の署名鍵に差し替えてください。

## 7. 初期設定（Gmail）

1. 送信元にする Google アカウントで 2 段階認証を有効にします。
2. Google アカウントの「アプリパスワード」で 16 文字のパスワードを発行します。
3. アプリを起動し、設定アイコンを 3 秒長押しして設定画面を開きます。
4. 送信元アドレスとアプリパスワードを入力し、送り先を登録します。
5. 「テスト送信」で届くことを確認します。

必要な設定が揃うまで、親向け画面には「設定が必要です」と表示されます。

## 8. セキュリティ上の注意

- アプリパスワードは Android Keystore の鍵（AES-GCM）で暗号化して端末内に保存し、Android のバックアップ対象から外しています。
- 設定画面に PIN はありません。端末を紛失した場合は、Google アカウント側でアプリパスワードを取り消してください。
- 認証情報をリポジトリに含めないでください（`*.local.properties` と `.env` は `.gitignore` 済みです）。

## 9. ディレクトリ構成

```
app/      本体アプリ（パッケージ com.example.parentphoto）
  src/main/java/.../camera    撮影・縮小・向き
  src/main/java/.../mail      メール組み立てと SMTP 送信
  src/main/java/.../outbox    未送信箱と自動再送
  src/main/java/.../settings  設定の保存・検証・暗号化
  src/main/java/.../ui        画面（親向け画面と設定画面）
  src/test, src/androidTest   テスト
spike/    開発初期の SMTP 送信検証用アプリ（本体とは別プロジェクト）
docs/     仕様・設計判断・実装計画
```

## 10. ドキュメント

- [docs/spec.md](docs/spec.md) — 確定仕様
- [docs/detailed-spec.md](docs/detailed-spec.md) — 詳細仕様と受け入れ確認
- [docs/DECISIONS.md](docs/DECISIONS.md) — 設計判断の経緯
- [docs/plans/](docs/plans/) — 機能ごとの実装計画

ドキュメントは英語で書かれています。

## 11. ライセンス

[MIT License](LICENSE)

## 12. Issue と Pull Request について

このリポジトリは個人用アプリのソースを公開しているだけです。Issue と Pull Request は受け付けていません。送られても対応しないので、改変したい場合はフォークしてご自由にお使いください（MIT License）。

This repository is published as-is. Issues and pull requests are not accepted; feel free to fork it under the MIT License.
