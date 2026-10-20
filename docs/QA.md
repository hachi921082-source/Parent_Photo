# Questions for the User

<!-- Answer record. Architecture changed 2026-10-17: GAS dropped, direct SMTP from the app (DECISIONS.md D9). Q1-Q17 re-audited: each carries a status. Q18-Q31 were new questions caused by the SMTP switch; all answered. -->

Status: Q1-Q17 audited (valid / changed / obsolete). Q18-Q31 RESOLVED (2026-10-17). Outbox method adopted later the same day (Q5/Q13 changed); Q32-Q44 RESOLVED (2026-10-18). Q45-Q50 (audit decisions) RESOLVED (2026-10-18).

## Index
- Q1 GAS authentication - OBSOLETE
- Q2 Destination allow-list - OBSOLETE
- Q3 Image resize - VALID
- Q4 Recipient count / scrolling - VALID
- Q5 Send failure behavior - CHANGED (superseded by outbox)
- Q6 Camera permission denied - VALID
- Q7 Hidden settings entry guard - VALID
- Q8 Drive folder configuration - OBSOLETE
- Q9 Email subject/body, file name - VALID (body wording adopted)
- Q10 Mail quota / mail failure - OBSOLETE
- Q11 Orientation, min API, GAS URL location - CHANGED
- Q12 Draft ambiguities - CHANGED (12a obsolete; 12b reconciled in Q33)
- Q13 Confirmation before sending - CHANGED (3 s undo window)
- Q14 Send-complete sound/vibration - VALID
- Q15 "Send to everyone" button - VALID
- Q16 Sender name in mail - VALID (extended by Q22)
- Q17 Font and button size - VALID (levels adopted)
- Carried-over adopted defaults
- Q18 Family shared album
- Q19 App password storage
- Q20 SMTP host/port
- Q21 Sender account and prerequisites
- Q22 From display name and Reply-To
- Q23 Attachment size and SMTP timeouts
- Q24 Unset-settings display
- Q25 Test send button
- Q26 Password input masking
- Q27 Failure display: auth vs network
- Q28 Security: credential on the phone
- Q29 File name, time, and body details
- Q30 JavaMail library and R8
- Q31 Single recipient per send
- Q32 Father-facing queued state (badge amended 2026-10-18)
- Q33 Undo window UI and button states
- Q34 First attempt: foreground or immediate queue
- Q35 Retry policy
- Q36 Retention and expiry - CHANGED 2026-10-18 (own cap 10 for 要確認)
- Q37 Duplicate-send risk
- Q38 Outbox full behavior - CHANGED 2026-10-18 (only active items count)
- Q39 Outbox view and send log in hidden settings - CHANGED 2026-10-18
- Q40 Batched mail subject/body
- Q41 Attachment file names and 25 MB split
- Q42 SMTP settings changed while items are queued
- Q43 Interplay: やり直す, deleted recipients, queued photos
- Q44 Notification on permanent failure
- Q45 Capture/resize/camera-init failure
- Q46 Stale preview photo at app start
- Q47 Strict active cap and promotion
- Q48 Recipient name length, wrapping, order
- Q49 Success sound in silent mode
- Q50 Batch split size 15 MB - CHANGED 2026-10-18

## Audit of earlier answers (Q1-Q17)

### Q1. GAS endpoint authentication [OBSOLETE]
- GAS removed; no token. (Was: A, shared secret token.)

### Q2. Arbitrary destination address [OBSOLETE]
- No GAS relay. (Was: B, trust the app.) The app sends only to registered recipients.

### Q3. Image resize [VALID]
- Answer: A. Long edge 1600 px, JPEG quality 85. Now also governs mail size (see Q23).

### Q4. Maximum recipients on screen [VALID]
- Answer: A. Scrollable list, cap 6.

### Q5. Send failure / offline behavior [CHANGED - superseded by the outbox method, see Q32-Q44]
- Original answer: A. "送れませんでした" with large retry button; image kept until success or "やり直す"; 60 s timeout; no auto-retry. Superseded: the retry-button screen and "image kept until success or やり直す" are replaced by the outbox (failed photo is queued, auto-sent later, app returns to camera). Codes only in Logcat still applies (Q27). 60 s timeout stays per attempt.

### Q6. Camera permission handling [VALID]
- Answer: A. Guidance screen with open-settings button.

### Q7. Hidden settings entry guard [VALID]
- Answer: A. 3 s long press, no PIN. Security implication of the new credential: see Q28.

### Q8. Drive folder configuration [OBSOLETE]
- No Drive. (Was: A.)

### Q9. Email subject/body and file name [VALID]
- Answer: A. Subject "写真が届きました"; file name `yyyyMMdd_HHmmss.jpg`; body "{sender}さんから写真が届きました。撮影日時: …" (adopted default). Timestamp now formatted in the app (Asia/Tokyo). Details: Q29.

### Q10. Mail quota / mail failure [OBSOLETE]
- No MailApp quota or Drive file. SMTP failures follow Q5; Gmail sending limits see Q21/Q23.

### Q11. Orientation, min API, settings location [CHANGED]
- Still valid: portrait-locked UI; photo orientation follows device; min API 26.
- Changed: GAS URL/token removed; replaced by SMTP settings (spec section 3; Q19, Q20).

### Q12. Draft ambiguities [CHANGED]
- 12a (Drive save only on send): OBSOLETE. Replaced: image is only held on the phone until success or "やり直す"; nothing is stored in the cloud.
- 12b All buttons disabled while sending: VALID.
- 12c Settings in DataStore: VALID for non-secret settings; the app password's storage is Q19.
- 12d Validate email format, reject empty names, no duplicate check: VALID. Also applies to the sender email address (assumed).

### Q13. Confirmation before sending [CHANGED]
- Original answer: A (none). Now: option C adopted. After tapping a recipient button there is a 3-second "取り消す" window before sending starts; otherwise one touch, no dialog. Details: Q33.

### Q14. Send-complete sound / vibration [VALID]
- Answer: B. Short vibration plus short sound.

### Q15. "Send to everyone" button [VALID]
- Answer: B. Not added.

### Q16. Sender name in mail [VALID]
- Answer: A. Set in hidden settings. Whether it is also the mail From display name: Q22.

### Q17. Font and button size [VALID]
- Shutter standard; other father-facing buttons configurable; text fixed large (buttons 28sp, messages 36sp); settings screen standard size. Levels 標準56/大96/特大128 dp, default 大: adopted.

## Carried-over adopted defaults (not open)
- Asia/Tokyo timestamps; success screen about 2 s.
- Codes only in Logcat. (The "送れませんでした" retry screen is superseded by the outbox; see Q32.)
- 0 recipients: "送り先が登録されていません".
- Settings icon shown on camera and preview screens; photo kept when returning from settings.
- Mail body "{sender}さんから写真が届きました。撮影日時: …".
- Dedicated "設定が必要です" display for unset required settings (trigger now SMTP settings; Q24).
- GAS URL https:// check: obsolete.

## New questions caused by the SMTP switch (all resolved) (caused by the SMTP switch)

### Q18. Family shared album (formerly Drive) [RESOLVED]
- Background: GAS saved every sent photo to a shared Drive folder. Without GAS the app no longer does this.
- Options:
  - A ★ Drop the album. Family receives photos only by mail (they can save them from mail).
  - B Add Drive upload in the app (needs Google sign-in/API setup; reintroduces complexity).
  - C Also Bcc a shared family address (a Google Group or one mailbox acts as the archive).
- Recommendation: A for simplicity; C if an archive is wanted.
- Answer: A. Album dropped; photos reach the family by mail only.
### Q19. App password storage method [RESOLVED]
- Background: the 16-char app password is a credential. EncryptedSharedPreferences (Jetpack Security) is deprecated.
- Options:
  - A ★ Encrypt with an Android Keystore key (AES-GCM, e.g. via Tink) and store the ciphertext in DataStore.
  - B Use EncryptedSharedPreferences anyway (works, but deprecated, no further fixes).
  - C Plain DataStore (simplest; readable by root or backups).
- Also: exclude the secret from Android backup (allowBackup / data extraction rules) in A/B.
- Recommendation: A.
- Answer: A. Android Keystore key (AES-GCM, e.g. Tink) encrypts the password; ciphertext in DataStore; secret excluded from backup.
### Q20. SMTP host and port; editable or fixed [RESOLVED]
- Options:
  - A ★ Defaults smtp.gmail.com, port 465 (SSL); both editable in settings (allows other providers).
  - B Default port 587 (STARTTLS); both editable.
  - C Fixed to Gmail (smtp.gmail.com:465), host/port not shown.
- Recommendation: A (465 implicit SSL is simplest; STARTTLS needs one more setting). Validate port as a number 1-65535.
- Answer: A. Defaults smtp.gmail.com / 465 (SSL); host and port editable; port validated 1-65535.
### Q21. Sender account and prerequisites [RESOLVED]
- Background: Gmail needs 2-step verification on, then a 16-char app password. Each sent mail also appears in the sender account's Sent folder. Gmail daily send limits apply (about 500/day for personal accounts; ample for this use).
- Options:
  - A ★ Sender is the father's own Gmail (father@gmail.com); family sets up 2-step verification and the app password once, and the father's Sent folder collects the history.
  - B A dedicated family Gmail account used only by this app (father's own mailbox stays clean; recipients see a family address).
  - C Another provider (needs host/port per Q20=A).
- Recommendation: A if the father already has Gmail; otherwise B.
- Answer: A. Sender is the father's own Gmail; family sets up 2-step verification and the app password once.
### Q22. From display name and Reply-To [RESOLVED]
- Options:
  - A ★ From = "{sender name} <sender address>" using the sender name setting (Q16); no Reply-To (replies go to the sender account).
  - B Same as A plus Reply-To set to a configurable address.
  - C From shows the address only.
- Recommendation: A.
- Answer: A. From = "{sender name} <sender address>"; no Reply-To.
### Q23. Attachment size and SMTP timeouts [RESOLVED]
- Background: with 1600 px / JPEG 85 a photo is typically 0.3-1.5 MB, far below Gmail's 25 MB limit. Mail adds about 33 percent Base64 overhead.
- Options:
  - A ★ Keep Q3 resize; connect timeout 15 s, read/write timeout 30 s, overall cap 60 s (whole send cancelled at 60 s).
  - B Same, but shorter per-step timeouts (10 s / 20 s).
  - C Same as A and also reject images over 10 MB after resize (not expected to occur).
- Recommendation: A.
- Answer: A. Keep Q3 resize; connect timeout 15 s, read/write 30 s, overall cap 60 s.
### Q24. "設定が必要です" display when SMTP is unset [RESOLVED]
- Background: the user already chose a dedicated display for unset configuration (formerly URL/token).
- Options:
  - A Trigger: sender address, app password, host, or port is empty/invalid. The display has a button that opens settings directly (bypasses the 3 s long press).
  - B ★ Same trigger, no button; the administrator uses the normal 3 s long-press icon (consistent with Q7).
  - C Also require at least one recipient (0 recipients keeps "送り先が登録されていません", so not recommended).
- Recommendation: B.
- Answer: B. "設定が必要です" shown when sender address, app password, host, or port is empty/invalid; no button; settings are opened only by the 3 s long press on the settings icon.
### Q25. Test send button in hidden settings [RESOLVED]
- Options:
  - A ★ Add "テスト送信": sends a text-only mail (no photo) to a chosen recipient and shows success or the error reason in Japanese (for the administrator only).
  - B Add a button that only tests the SMTP login (no mail sent).
  - C No test button.
- Recommendation: A (the app password is hard to verify otherwise).
- Answer: A. Add "テスト送信": text-only mail to a chosen recipient; result or error reason shown in Japanese (administrator only).
### Q26. Password input masking and showing [RESOLVED]
- Options:
  - A ★ Masked by default with a show/hide toggle; once saved, the field shows only dots and "変更" (the saved value is never displayed again).
  - B Masked with show/hide toggle, saved value can be revealed.
  - C Always visible plain text.
- Also: allow paste, ignore spaces when pasting (Google shows the password in 4-char groups).
- Recommendation: A.
- Answer: A. Masked with show/hide toggle; saved value never shown again (only dots and "変更"); paste allowed, spaces ignored.
### Q27. Failure display: authentication vs network [RESOLVED]
- Background: Q5 says one "送れませんでした" screen for all causes; codes only in Logcat. Authentication failure (wrong/revoked app password) cannot be fixed by retrying.
- Options:
  - A ★ Keep the existing rule: same screen, same retry button, codes only in Logcat. The administrator diagnoses with test send (Q25) or Logcat.
  - B Same screen, plus a small text "管理者に連絡してください" only for authentication errors.
  - C Different messages per cause (conflicts with the earlier decision).
- Recommendation: A (matches the earlier decision).
- Answer: A. Codes only in Logcat. (The "same screen with retry button" part is superseded by the outbox; auth vs network handling is now Q35.)
### Q28. Security: credential on the phone [RESOLVED]
- Background: the app password allows sending mail as the sender account. Anyone who can use the phone and knows the 3 s long press on the settings icon can see settings (or, per Q26, only overwrite it). A leaked password can be revoked at any time in the Google account, which does not expose the main password.
- Options:
  - A ★ Accept the risk; no PIN (Q7 stands); use Q19 storage and Q26 masking; document that the app password should be revoked if the phone is lost.
  - B Add a PIN to the hidden settings (reverses Q7).
  - C Require device lock (screen lock) to open settings.
- Recommendation: A.
- Answer: A. Accept the risk; no PIN (Q7 stands); Q19 storage and Q26 masking; revoke the app password if the phone is lost.
### Q29. Mail file name, time, and body details [RESOLVED]
- Background: no GAS; the app formats everything. Confirm remaining small details.
- Options:
  - A ★ File name `yyyyMMdd_HHmmss.jpg` (Asia/Tokyo capture time); body: "{sender}さんから写真が届きました。" + line break + "撮影日時: yyyy/MM/dd HH:mm"; plain text, UTF-8; if the sender name is empty, fall back to "家族".
  - B Same, body with seconds (HH:mm:ss).
  - C Add the recipient's display name in the greeting.
- Recommendation: A.
- Answer: A. File name yyyyMMdd_HHmmss.jpg (Asia/Tokyo); body "{sender}さんから写真が届きました。" + line break + "撮影日時: yyyy/MM/dd HH:mm"; plain text UTF-8; empty sender name falls back to "家族".
### Q30. JavaMail library and R8 [RESOLVED]
- Options:
  - A ★ `com.sun.mail:android-mail` + `com.sun.mail:android-activation` (the long-standing Android build of JavaMail 1.6.x; works on API 26+; minimal R8 keep rules for javax.mail/activation/mailcap).
  - B `jakarta.mail` + angus (newer, Jakarta namespace; larger, more R8/ServiceLoader rules, Android support less tested).
  - C Own minimal SMTP client (not recommended).
- Note: release build needs R8 keep rules or minify disabled; verify with a real release build.
- Recommendation: A.
- Answer: A. com.sun.mail:android-mail + android-activation; minimal R8 keep rules; verify with a release build.
### Q31. Single recipient per send [RESOLVED]
- Options:
  - A ★ One recipient per send (as now); no Cc/Bcc to others.
  - B Allow a configurable always-Bcc address (overlaps Q18-C).
- Recommendation: A.
- Answer: A. One recipient per send; no Cc/Bcc.

## New questions caused by the outbox method (all resolved)

Adopted by the user (not open): on send failure the resized photo is saved in an app-private outbox (with recipient and capture time) and auto-sent when the network is available (WorkManager, network-connected constraint); the app returns to the camera immediately. Outbox cap 5 items; when full, shooting is blocked with guidance. On retry, queued photos for the same recipient go out in one mail with multiple attachments (split to respect the ~25 MB Gmail limit). 3-second "取り消す" window after tapping a recipient.

### Q32. Father-facing queued state [RESOLVED]
- Background: after a failure the father is back at the camera. He should know the photo is not yet delivered, without a scary error.
- Options:
  - A ★ Brief screen "あとで送ります" (about 2 s, no sound/vibration), then the camera with a corner badge "未送信 N件" while items remain. When a queued item is later delivered: silent; the badge decreases.
  - B Same as A, plus short vibration and sound when a queued item is delivered later.
  - C No badge; only the brief "あとで送ります" screen.
- Recommendation: A.
- Answer: A. "あとで送ります" (about 2 s, silent), then camera with corner badge "未送信 N件"; later delivery is silent and the badge decreases.
- CHANGED (2026-10-18; wording CONFIRMED by the user): badge "未送信 N件" counts active ("待機中") items only; "要確認" items get a separate badge "送れていません M件" in a distinct (warning) color, shown only while M > 0. The word "要確認" is administrator-facing (hidden settings) and not shown to the father.
### Q33. Undo window UI and button states [RESOLVED]
- Background: replaces Q13-A; must reconcile with Q12b (all buttons disabled while sending).
- Options:
  - A ★ After a recipient tap: all other buttons disabled; a large "取り消す" button (same size level as other father buttons) with text "3秒後に送ります" and a visible 3-2-1 countdown. Tapping it returns to the preview screen with the photo kept. After 3 s, "送信中..." starts and all buttons are disabled (Q12b). The photo is written to the outbox store at the start of the window so it survives an app kill.
  - B Same, but cancelling discards the photo and returns to the camera.
  - C Same as A, but no countdown number (label only).
- Recommendation: A (undo of a wrong tap should allow choosing the right person with the same photo).
- Answer: A. After a recipient tap other buttons are disabled; large "取り消す" button with "3秒後に送ります" and a 3-2-1 countdown; cancelling returns to the preview with the photo kept. After 3 s "送信中..." starts with all buttons disabled. The photo is written to the outbox store at the start of the window.
### Q34. First attempt: foreground or immediate queue [RESOLVED]
- Background: when offline, waiting up to 60 s on "送信中..." is poor.
- Options:
  - A ★ First attempt runs in the foreground with "送信中..." (60 s cap, Q23). If it fails (any cause), the photo is queued and the app returns to the camera. If the device clearly has no connected network, skip the attempt and queue immediately.
  - B Always queue first; WorkManager sends immediately when online (father never sees "送信中..." or "送りました！").
  - C Foreground attempt always, no offline shortcut.
- Recommendation: A (keeps the confirming "送りました！" feedback on the normal path).
- Answer: A. First attempt runs in the foreground with "送信中..." (60 s cap); on failure the photo is queued and the app returns to the camera; if clearly no network is connected, queue immediately.
### Q35. Retry policy [RESOLVED]
- Options:
  - A ★ Network-type failures (no network, timeout, connection refused) are retried by WorkManager when the network is connected, with exponential backoff starting at about 15 min, for up to 24 h; then the item becomes "要確認" and automatic retries stop (the photo is kept). Authentication failure (wrong/revoked app password) and other permanent errors become "要確認" immediately and are retried after SMTP settings are saved, a test send succeeds, or the family taps "今すぐ再送" (Q39, Q42).
  - B Retry every cause for up to 24 h.
  - C Retry network failures indefinitely.
- Recommendation: A.
- Answer: A. Network-type failures are retried by WorkManager (network-connected constraint, exponential backoff from about 15 min) for up to 24 h; after that the item becomes "要確認" and automatic retries stop. Authentication failures and other permanent errors become "要確認" immediately. "要確認" items resume after SMTP settings are saved, a test send succeeds, or the family taps "今すぐ再送".
### Q36. Retention and expiry [RESOLVED - CHANGED 2026-10-18]
- Options:
  - A Network-failure items older than 24 h are deleted and logged as "期限切れ"; "要確認" items kept up to 7 days. (Recommended by the designer; not chosen.)
  - B ★(user's choice) Keep everything until sent or manually deleted; no expiry. After 24 h of failed retries the item becomes "要確認" (no more automatic retries) but stays in the outbox.
  - C Expire everything after 7 days.
- Recommendation: A (user chose B).
- Answer: B (differs from the recommendation). No expiry and no automatic deletion: a photo stays in the outbox until it is delivered or the family deletes it manually. After 24 h of failed network retries it becomes "要確認" (Q35) but is NOT deleted by time. The family can "今すぐ再送" or delete it from the unsent list (Q39). There is no "期限切れ" state.
- CHANGED (2026-10-18): "要確認" items do NOT count toward the 5-item cap and do not block shooting; "要確認" has its own cap of 10. When an 11th item becomes "要確認", the oldest "要確認" item is deleted automatically and recorded in the send log as deleted. This is the only automatic deletion (overflow beyond 10).
### Q37. Duplicate-send risk after ambiguous failures [RESOLVED]
- Background: a timeout after the data was sent may mean the mail already went out; retrying then duplicates it.
- Options:
  - A ★ Accept: retry anyway; a duplicate mail is better than a lost photo. No dedupe.
  - B Never retry ambiguous failures; mark "要確認".
  - C Retry once, then mark "要確認".
- Recommendation: A.
- Answer: A. Accept duplicates; retry anyway; no dedupe.
### Q38. Outbox full behavior [RESOLVED - CHANGED 2026-10-18]
- Background: cap 5 items; shooting is blocked when full.
- Options:
  - A ★ Camera screen shows "送れていない写真が5枚あります。ネットワークを確認してください" and the shutter is disabled; unblocks automatically when items are delivered; the family can delete items (Q39). An item in the undo window or foreground sending counts as one of the 5.
  - B Same, with the shutter hidden entirely.
  - C Auto-delete the oldest item to allow shooting (risks silent photo loss; not recommended).
- Recommendation: A.
- Answer: A. Camera screen shows "送れていない写真が5枚あります。ネットワークを確認してください" and the shutter is disabled. Items in the undo window, foreground sending, or waiting for retry ("待機中") count toward the 5.
- CHANGED (2026-10-18): "要確認" items are not counted and never block shooting; shooting is blocked only when 5 active ("待機中") items exist. Guidance text unchanged.
### Q39. Outbox view and send log in hidden settings [RESOLVED - CHANGED 2026-10-18]
- Options:
  - A ★ Add an "未送信一覧" screen: per item thumbnail, recipient name, capture time, state (待機中 / 要確認), with delete and "今すぐ再送" buttons; plus a send log of the last 50 entries (time, recipient, result, short error code), no image kept after delivery.
  - B Outbox list only, no log.
  - C Neither (count shown only).
- Recommendation: A.
- Answer: A. "未送信一覧" screen in hidden settings: thumbnail, recipient name, capture time, state (待機中 / 要確認), "削除" and "今すぐ再送" buttons; plus a send log of the last 50 entries (time, recipient, result, short error code); no image kept after delivery.
- CHANGED (2026-10-18; behavior CONFIRMED by the user): the list shows active items and "要確認" items (max 10) separately; the send log also records "要確認" overflow deletions (result "削除（上限超過）"). "今すぐ再送" on a "要確認" item moves it back to "待機中" (if the active cap of 5 is full, it waits until a slot frees).
### Q40. Batched mail subject/body [RESOLVED]
- Options:
  - A ★ Single photo: unchanged (Q9/Q29). Several photos for one recipient: subject "写真が届きました（N枚）"; body "{sender}さんから写真が届きました。" + line break + one line per photo "撮影日時: yyyy/MM/dd HH:mm" in capture order.
  - B Same subject as single, body lists times.
  - C Send queued photos as separate mails (contradicts the adopted batching).
- Recommendation: A.
- Answer: A. Single photo unchanged. Several photos for one recipient: subject "写真が届きました（N枚）"; body "{sender}さんから写真が届きました。" + line break + one line per photo "撮影日時: yyyy/MM/dd HH:mm" in capture order.
### Q41. Attachment file names and 25 MB split [RESOLVED]
- Options:
  - A ★ Each attachment named by its own capture time `yyyyMMdd_HHmmss.jpg`; same-second duplicates get `_2`, `_3`. Split into several mails when attachments would exceed about 18 MB total (Base64 overhead keeps it under Gmail's 25 MB); with 5 photos at 1600 px this practically never happens. Each split mail uses the Q40 format with its own count.
  - B Same, split limit 10 MB.
  - C No split (rely on the cap of 5).
- Recommendation: A.
- Answer: A. Each attachment named by its own capture time yyyyMMdd_HHmmss.jpg; same-second duplicates get _2, _3. Split into several mails when attachments exceed about 18 MB total (CHANGED 2026-10-18 by Q50 to about 15 MB); each split mail uses the Q40 format with its own count.
### Q42. SMTP settings changed while items are queued [RESOLVED]
- Options:
  - A ★ Queued items store photo, recipient id and capture time only; sender account, password, host and port are read at send time, so new settings apply to queued items. Saving SMTP settings or a successful test send immediately retries "要確認" items.
  - B Snapshot the sender settings into each item at queue time.
  - C Clear the outbox when SMTP settings change.
- Recommendation: A.
- Answer: A. Queued items store photo, recipient id and capture time only; sender account, password, host and port are read at send time, so new settings apply to queued items. Saving SMTP settings or a successful test send retries "要確認" items.
### Q43. Interplay: やり直す, deleted recipients, queued photos [RESOLVED]
- Background: a queued photo can no longer be undone by the father; a recipient may be edited or deleted while an item waits.
- Options:
  - A ★ "やり直す" applies only to the photo on the preview screen (and, via Q33, the undo window). The father cannot touch queued items; only the family can delete them (Q39). If the recipient's address is edited, the new address is used; if the recipient is deleted, the item becomes "要確認" and is not sent.
  - B Snapshot the recipient address at queue time; later edits/deletes do not affect it.
  - C Deleting a recipient also deletes its queued items (after confirmation).
- Recommendation: A.
- Answer: A. "やり直す" applies only to the photo on the preview screen (and the undo window). The father cannot touch queued items; only the family can delete them. Recipient address edited: new address used. Recipient deleted: item becomes "要確認" and is not sent.
### Q44. Notification on permanent failure [RESOLVED - CHANGED 2026-10-18]
- Background: with the app in the background, a "要確認" item would otherwise go unnoticed.
- Options:
  - A ★ Android notification on the father's phone only when an item becomes "要確認": "写真が送れていません。家族に連絡してください" (no technical detail). Needs POST_NOTIFICATIONS on API 33+ (asked once; if denied, only the badge is shown).
  - B No notification; badge and hidden settings only.
  - C Notification on every retry failure (noisy; not recommended).
- Recommendation: A.
- Answer: A. Android notification on the father's phone only when an item becomes "要確認": "写真が送れていません。家族に連絡してください" (no technical detail). POST_NOTIFICATIONS requested once on API 33+; if denied, only the badge is shown.
- CHANGED (2026-10-18; wording CONFIRMED by the user): when a "要確認" overflow deletes the oldest photo, the notification text is "写真が送れていません。古い写真1枚を削除しました。家族に連絡してください".

## Audit decisions (Q45-Q50, all CONFIRMED by the user, 2026-10-18)

### Q45. Capture / resize / camera-init failure [RESOLVED]
- Options: A ★ return to the camera with "撮影できませんでした。もう一度お試しください" and log to Logcat; B silent return; C error screen with a button.
- Answer: A. CONFIRMED by the user.

### Q46. Stale preview photo at app start [RESOLVED]
- Options: A ★ delete `cache/preview.jpg` at every cold start and never restore Preview; B restore Preview.
- Answer: A. CONFIRMED by the user.

### Q47. Strict active cap and promotion of 要確認 [RESOLVED]
- Options: A ★ active count never exceeds 5; 今すぐ再送 waits (resendPending) and OutboxRepository observes the active-count Flow and promotes oldest-first; B allow a 6th item temporarily.
- Answer: A. CONFIRMED by the user. (Designer's detail, to be confirmed: a photo shown in Preview reserves one slot, so promotions cannot take the last slot while a photo is waiting to be sent.)

### Q48. Recipient display name length, wrapping, order [RESOLVED]
- Options: A ★ max 10 characters, wraps to at most 2 lines, fixed registration order; B longer names with ellipsis; C reorderable.
- Answer: A. CONFIRMED by the user.

### Q49. Success sound in silent mode [RESOLVED]
- Options: A ★ vibration only (sound not forced); B force sound.
- Answer: A. CONFIRMED by the user.

### Q50. Batch split size [RESOLVED]
- Background: Base64 makes 18 MB of photos about 24.7 MB, too close to Gmail's 25 MB.
- Options: A ★ split at about 15 MB total; B keep 18 MB.
- Answer: A. CONFIRMED by the user. Supersedes the 18 MB in Q41.

## UI audit decisions (Q51-Q58, all CONFIRMED by the user, 2026-10-19)
Background: a self-review found places where the implementer would have had to choose user-visible behavior. The user decided each item below.

### Q51. System font scale on father-facing screens [RESOLVED]
- Options: A ignore system font scale (fontScale = 1f), sizes stay 28/36 sp; B follow system scale; C cap at 1.3.
- Answer: A. CONFIRMED by the user. The hidden settings screen follows the system scale.

### Q52. Father-facing screen layouts and colors [RESOLVED]
- Options: A Preview-based screens keep the photo dimmed, centered 36 sp label, 取り消す pinned bottom; Sent/QueuedNotice plain full-screen with centered 36 sp text; photo min 30 percent height, 16 dp spacing; recipient buttons filled primary, やり直す outlined; Material3 default light colors with explicit hex tokens; B plain white for all; C mock from the user.
- Answer: A. CONFIRMED by the user. Defined in detailed-spec 3.4.

### Q53. 保存 button enabled rule [RESOLVED]
- Options: A enabled whenever dirty, validate on press; B live validation with disabled button; C live validation with errors after edit.
- Answer: A (also for the recipient dialog). CONFIRMED by the user. Removes the contradiction between "enabled when dirty and valid" and "validate on 保存".

### Q54. App name [RESOLVED]
- Answer: FamilyPhoto (launcher label, app_name, release file name FamilyPhoto_YYYYMMDD-HHMMSS.apk). Package stays com.example.parentphoto. CONFIRMED by the user.

### Q55. Camera / OutboxFull / Capturing layout and badges [RESOLVED]
- Answer: badges as dark 60 percent alpha pills with white 20 sp text (final), 未送信 above 送れていません, 8 dp apart, warning badge #E65100 with white text; OutboxFull guidance bottom-center above the disabled shutter (36 sp, dark box), camera feed stays live; Capturing keeps a disabled shutter plus a 32 dp indicator centered; a second capture failure restarts the 3000 ms timer. CONFIRMED by the user.

### Q56. Notification behavior [RESOLVED]
- Answer: no title, BigTextStyle, onlyAlertOnce = true, autoCancel = false, text reflects the latest event. CONFIRMED by the user.

### Q57. Settings feedback [RESOLVED]
- Answer: Material3 Snackbar (Short, no action) for "順番待ちです。しばらくお待ちください" and "保存しました" only; no other success feedback. CONFIRMED by the user.

### Q58. Remaining UI details [RESOLVED]
- Answer: all recommended options adopted: password 変更 with キャンセル and empty-after-変更 invalid; test-send first recipient preselected and hint priority (no recipients, invalid saved settings, dirty); result dialog only OK; unsent list group order, sorting, headers, row layout; send log mapping table and one entry per mail; sub-screen back and dialog dismissal rules; precedence re-evaluated on every onResume; field keyboards, max lengths, host/port/address messages, first error focused, autofill off; accessibility descriptions; no animation; exact values (3000 ms failure message, 96 dp thumbnail, tone 200 ms); all UI strings in res/values/strings.xml. CONFIRMED by the user.

### Q59. SMTP host over 253 characters [RESOLVED]
- Answer: input blocked at 253 like the other fields; no error text. CONFIRMED by the user.

### Q60. Settings icon color on SetupRequired and PermissionDenied [RESOLVED]
- Answer: #49454F, same as Preview. CONFIRMED by the user.

### Q61. Dirty-form confirm dialog dismissal [RESOLVED]
- Answer: dialog "保存していない変更があります。破棄しますか？" has buttons 破棄 / 戻る only; outside tap or back = 戻る (stay). CONFIRMED by the user.

### Q62. onResume precedence re-evaluation scope [RESOLVED]
- Answer: replaces only SetupRequired, PermissionDenied, Camera, OutboxFull; Capturing, Preview, CancelWindow, Sending, Sent, QueuedNotice are never interrupted (a revoked permission mid-Preview does not discard the photo). CONFIRMED by the user.

### Q63. sendForeground check order: missing recipient vs offline [RESOLVED]
- Answer: recipient check first. A deleted recipient returns RecipientMissing (straight to 要確認) even when offline; no queueing. CONFIRMED by the user.

### Q64. Retry delay after a kill during a background send [RESOLVED]
- Context: a kill during the worker's send leaves WorkManager's ~15 min backoff in place; recovery's KEEP enqueue does not shorten it.
- Options: A accept as spec (recommended by the agent); B `triggerNow` only when recovery requeued rows; C `triggerNow` on every startup.
- Answer: C. Startup recovery calls `triggerNow` (REPLACE if not RUNNING, else follow-up `outbox-send-next`) instead of `enqueueDefault` whenever any QUEUED row exists after recovery, so every app open resets the backoff and retries immediately. Accepted trade-off: more frequent retries. CONFIRMED by the user.

### Q65. Recipient tap in Preview while active count is 5 (race) [RESOLVED]
- Context: ds 2.4 says "reject with OutboxFull notice" but not what happens to the photo. Rare race only (Preview reserves a slot).
- Answer: keep Preview and the photo; overlay the OutboxFull notice text for 3000 ms; recipient buttons work again once the count drops. No item is created. CONFIRMED by the user.

### Q66. Background while the camera permission dialog is shown [RESOLVED]
- Answer: blank Loading screen behind the system dialog (no camera frame). CONFIRMED by the user.
