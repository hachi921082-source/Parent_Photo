# Plan: settings-storage

Source: ds 8, 9.3 (validators), 5 (sender-name sanitizing). Branch: `feature/settings-storage`. Depends on: app-foundation.

## Index
1. Scope
2. Data model
3. Components
4. Behavior rules
5. Tests
6. Steps

## 1. Scope
Data layer only (no screens): DataStore settings, recipients, password encryption, SetupRequired evaluation, validators.

## 2. Data model
DataStore Preferences `settings` (keys per ds 8.1): `sender_name`="", `smtp_user`="", `smtp_host`="smtp.gmail.com", `smtp_port`=465, `password_cipher`="", `button_size`=`LARGE` (`STANDARD`/`LARGE`/`XLARGE` -> 56/96/128 dp), `recipients_json`="[]", `notif_permission_asked`=false.
`Recipient(id: String (UUID, never reused), name: String, email: String)`; max 6; order preserved.

## 3. Components (package `settings/`)
- `SettingsRepository`: `settings: Flow<Settings>` (first emission signals Loading end), `saveSmtp(senderName, user, host, port, newPasswordOrNull)`, `setButtonSize`, `addRecipient`, `updateRecipient` (keeps position), `deleteRecipient`, `markNotifPermissionAsked`. Recipient writes are saved immediately; `saveSmtp` is one atomic edit. Decrypted password exposed via `suspend fun readPassword(): String?` (null if unset or undecryptable); never part of the `Settings` flow and never logged.
- `PasswordCrypto`: Android Keystore AES-256 key alias `parent_photo_smtp_pw`, `AES/GCM/NoPadding`, random 12-byte IV per encryption, stored as Base64(IV + ciphertext+tag); decrypt failure returns null (treated as unset). Uses `javax.crypto` + `AndroidKeyStore` (no Tink).
- `Recipient` JSON (de)serialization with `org.json` (no extra dependency).
- `Validators`: email via `android.util.Patterns.EMAIL_ADDRESS` after trim; recipient name non-empty after trim and <= 10 chars; password after whitespace removal exactly 16 chars; host non-blank after trim; port integer 1-65535; sender name trimmed and CR/LF removed. Error strings per ds 9.3 (Japanese) live here as constants so the settings UI reuses them.
- `SetupState.isSetupRequired(settings, passwordPresent)`: true if `smtp_user` invalid, password unset/undecryptable, host blank, or port outside 1-65535. Sender name and recipients not considered.

## 4. Behavior rules
- Password whitespace (spaces, newlines) stripped before validate/encrypt.
- Saving without a new password keeps the existing cipher.
- Recipient cap enforced in the repository (reject the 7th).
- Backup: manifest `allowBackup=false` (done in app-foundation).

## 5. Tests
- JVM: validators, recipient JSON round trip, SetupState matrix, sender-name sanitizing.
- Instrumented (device): PasswordCrypto round trip, decrypt of corrupted data returns null, repository persistence across process restart.

## 6. Steps
1. Models + validators + SetupState with unit tests.
2. PasswordCrypto + instrumented test.
3. SettingsRepository + instrumented test.

Not in this plan: screens (settings-screen), per-recipient queued-photo counts for delete confirmation (needs OutboxRepository; see settings-screen PLAN-2).
