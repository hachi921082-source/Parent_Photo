# Decisions

<!-- Background, comparative analysis, and rejected alternatives for spec.md decisions. -->

## D1. Initial specification scope (2026-10-17)
- spec.md records only what the user's initial draft states explicitly.
- Gaps found in the draft (auth, size limits, failure handling, permissions, etc.) were not decided by the designer; they were listed in `docs/QA.md` and have since been answered by the user (see D2 onward).
- Draft left storage open ("DataStore or SharedPreferences"); resolved as DataStore (Q12c).

> 2026-10-17: GAS removed (see D9). D2, D3 and the Drive part of D5 are SUPERSEDED; kept for history.

## D2. Shared secret token (Q1) [SUPERSEDED by D9]
- Token in POST body, checked by GAS against a script property. Cheap, no UI impact.
- Token is entered in the hidden settings screen and stored in DataStore (Q11), so it lives on the device in plain app storage. Rotating it needs a GAS property change plus re-entry on the phone.

## D3. No destination allow-list (Q2) [SUPERSEDED by D9]
- Chosen: trust the app (token only). Rejected: GAS-side allow-list.
- Benefit: adding or changing a recipient needs only the phone settings, no GAS edit.
- Accepted risk: anyone holding the URL and token can make the owner's account send mail (with an attached image) to any address, i.e. an open mail relay limited by the token and the daily MailApp quota. If the token leaks, rotate it in GAS properties and re-enter it on the phone.
- Revisit with an allow-list if the token is ever shared widely or abuse is seen.

## D4. Hidden settings entry: 3 s long press without PIN (Q7)
- The settings expose only recipients, sender name, URL/token, and button size; no data is exposed that a PIN protects well.
- A PIN is one more thing for family members to forget; the father is unlikely to hit a 3 s long press on a small icon by accident.

## D5. Failure handling: keep image, manual retry (Q5, Q10) [manual-retry part still valid; Drive/mail-quota part SUPERSEDED by D9]
- No auto-retry: avoids hidden state and repeated sends the father cannot see.
- Mail failure keeps the Drive file (photo never lost). Consequence: resending after a mail error can create a duplicate Drive file; accepted.

## D6. No confirmation before send (Q13)
- Keeps the flow at one touch. Trade-off: a wrong tap sends to the wrong person; mitigated only by clear display names.

## D7. Button size is configurable, shutter is standard (Q17)
- The user overrode the proposed "maximum-size everything": the shutter stays standard size; other father-facing buttons are adjustable by the family in hidden settings because the right size depends on the father; text is fixed large.
- Levels (標準/大/特大) and default (大) are the designer's proposal, pending user confirmation.

## D8. Photo orientation follows the device; UI is portrait-locked (Q11)
- The UI never rotates, but CameraX targetRotation follows the sensor so landscape-held shots become landscape photos; the preview screen must display the photo with correct orientation inside the portrait UI.

## D9. Switch from GAS to direct SMTP from the app (2026-10-17)
- Decision (user): drop Google Apps Script entirely. The Android app sends mail directly via JavaMail over SMTP in the background (Dispatchers.IO). Screen layout, camera flow, and recipient management are unchanged.
- Rationale: user's decision; one component instead of two, no GAS deployment or script properties to maintain.
- Trade-offs accepted: (1) no Drive family shared album unless re-added (QA Q18); (2) the sender's app password is stored on the father's phone (QA Q19, Q28); (3) SMTP/JavaMail specifics (library, R8, ports) move into the app (QA Q20, Q30).
- Superseded: D2 (token), D3 (allow-list; the open-relay risk disappears, replaced by the credential-on-device risk), Drive part of D5.
- Unchanged: D1, D4, D6, D7, D8.

## D10. SMTP settings decisions (Q18-Q31, 2026-10-17)
- All answered with the recommended option. Non-obvious points:
- Q28 accepted risk: the app password lets anyone who opens hidden settings (3 s long press, no PIN) overwrite it, and the stored ciphertext is protected only by the Keystore. Mitigation: Keystore encryption (Q19), never re-displaying the saved value (Q26), excluded from backup, and revocable at any time in the Google account without exposing the main password.
- Q24: the "設定が必要です" display has no button; settings stay reachable only by the 3 s long press (consistent with Q7). Matches the designer's recommendation.
- Q18: dropping the album loses the family archive; Bcc to a shared address (Q18-C) remains a possible later addition.
- Q30: android-mail chosen over jakarta.mail for proven Android compatibility and fewer R8/ServiceLoader issues.

## D11. Outbox method replaces manual retry and no-confirmation (2026-10-17)
- Decision (user): a failed send is queued in an app-private outbox and auto-sent via WorkManager when the network returns; the app returns to the camera at once. Cap 5; batching per recipient; 3 s "取り消す" window after a recipient tap.
- Supersedes: Q5 manual retry screen (D5 manual-retry part) and Q13-A / D6 (no confirmation). Rationale: the father should not have to handle errors or retry; a short undo window guards against wrong taps while keeping one touch.
- Trade-offs: more moving parts (WorkManager, storage, expiry, duplicates); photos live on the phone until delivered; a queued photo cannot be undone by the father. Open details: QA Q32-Q44.

## D11. Outbox method replaces manual retry and no-confirmation (2026-10-17)
- Decision (user): a failed send is queued in an app-private outbox and auto-sent via WorkManager when the network returns; the app returns to the camera at once. Cap 5; batching per recipient; 3 s "取り消す" window after a recipient tap.
- Supersedes: Q5 manual retry screen (D5 manual-retry part) and Q13-A / D6 (no confirmation). Rationale: the father should not have to handle errors or retry; a short undo window guards against wrong taps while keeping one touch.
- Trade-offs: more moving parts (WorkManager, storage, expiry, duplicates); photos live on the phone until delivered; a queued photo cannot be undone by the father. Open details: QA Q32-Q44.

## D12. Outbox: no expiry (Q35, Q36, 2026-10-18)
- Decision (user): no automatic deletion. Network-failure retries stop after 24 h and the item becomes "要確認"; the photo stays in the outbox until delivered or deleted by the family ("今すぐ再送" / delete in the unsent list). Auth failures become "要確認" immediately. The designer had recommended 24 h expiry with deletion.
- Rationale: a photo is never lost silently.
- Trade-off (initial; amended below): stale "要確認" items keep counting toward the 5-item cap and block shooting until the family acts; mitigated by the Q44 notification and the "未送信 N件" badge. Photos stay on the phone indefinitely.
- All other Q32-Q44 answers follow the recommendations (see QA.md).

### D12 amendment (2026-10-18): separate caps
- Decision (user): the cap of 5 counts only active ("待機中") items; "要確認" items do not count and do not block shooting. "要確認" has its own cap of 10; the 11th "要確認" item deletes the oldest "要確認" item automatically (recorded in the send log; the notification says so). This overflow is the only automatic deletion.
- Reason: stale unhandled photos must not block the father from shooting.
- Trade-off (replaces the earlier one): at most 10 stale "要確認" photos are kept, and a photo that nobody handles can eventually be lost by overflow. Mitigated by the notification, the warning badge, and the unsent list.

## D13. Audit follow-ups (Q45-Q50, 2026-10-18)
- Q50: attachments are measured before Base64 encoding, which adds about 37 percent; 18 MB would give about 24.7 MB, too close to Gmail's 25 MB limit including headers, so the split is at 15 MB.
- Q47: the cap of 5 is strict (no 6th item) so the "shutter blocked at 5" rule is simple; the cost is that 今すぐ再送 may wait until a slot frees. A photo in Preview reserves a slot (designer's detail) so a promotion cannot make the photo's own send exceed the cap.
- Q46: Preview is never restored after a restart; the photo has not been sent or queued, so discarding it is safer than showing a stale photo to the father.
- Q49: sound is not forced in silent mode out of respect for the phone's setting; the vibration still confirms success.

## D14. Possible duplicate delivery on read failure (2026-10-19)
- Spike finding: on M06 (API 36), 2 of 3 sends ended with `Exception reading response`; one mail still arrived. The server can accept the mail and drop the connection before the reply, so the app cannot tell delivered from not delivered.
- Decision (user): treat it as a retryable network error (6.3) and accept that a retry may deliver the same photo twice.
- Reason: a duplicate is less harmful than a photo that never arrives; deduplication (fixed Message-ID) is not guaranteed on the receiving side.
- Trade-off: the family may occasionally receive the same photo twice.

## D15. UI audit: remove implementer discretion (Q51-Q58, 2026-10-19)
- Trigger: a self-review of detailed-spec and plans for vague wording and undefined UI. The plans were faithful to the spec; the gaps were in the spec itself (layout, colors, feedback, edge states).
- Principle: anything the father or the family can see must be written down with an exact value, string, or token, so developer-lite never decides it; Material3 default tokens were chosen to avoid a design round.
- Font scale (Q51): the father's phone may use a large system font; sp scaling would overflow the fixed 28/36 sp layouts, so father screens ignore the scale. The settings screen follows it for the administrator's comfort.
- Save button (Q53): "enabled when dirty and valid" made validation messages unreachable; chosen rule shows errors on press.
- Feedback (Q57): only two Snackbars to avoid noise; other actions are visible through list changes.
- Notification (Q56): BigText because the text is long; onlyAlertOnce so repeated 要確認 events do not ring again; not auto-cancelled because it is cleared only when the 要確認 count is 0.
- Rejected: following the system font scale (overflow risk), live validation (more complex, no benefit here).

### D15 amendment (Q59-Q62, 2026-10-19)
- Gaps found while syncing plans were closed: host length blocked silently, one icon color on all screens that show it, the dirty-form dialog treats outside tap and back as "stay" (never loses edits), and onResume never interrupts an in-progress photo flow (discarding or switching away from a captured photo or a send is worse than a stale permission state, which the normal exit re-checks).

## D16. Swipe-away during the undo window / foreground send may delay the send (Q74, 2026-10-21)
- Found on real device M06 (Android 16, FCNT arrows). Swiping the app from recents kills only the process (not a force-stop); WorkManager jobs survive. But the app's `requeueAbandoned` -> `triggerNow` (async `enqueueUniqueWork REPLACE`) is still in flight when the process dies (4-18 ms later), so the "send now" job is not registered. The photo is safe (row back to QUEUED); it is sent on the next app start (recovery uses `triggerNow`, Q64) or when an already-pending backoff job fires (about 13 min, up to about 5 h).
- Not caused by sleep/standby: standby bucket active, device idle state active, network constraint satisfied.
- Accepted (user, Q74): rare scenario (swipe within about 3 s + 60 s of a send), no data loss, next open sends at once.
- Rejected for now: pre-registering a delayed safety-net work (about 90 s, KEEP, network constraint) at item creation plus a worker rule that requeues stale CANCEL_WINDOW/SENDING_FG rows (more worker logic and a spec change); waiting on `Operation.result` in `onCleared` (4-18 ms window, not reliable); expedited work / foreground service (does not fix the registration race; persistent notification).
- Can be reopened if the father swipes the app away often.

## D17. Success vibration needs explicit attributes (Q75, 2026-10-21)
- Cause found on real device M06 (Android 16): `Vibrator.vibrate(effect)` without attributes is recorded as usage TOUCH. M06 has `haptic_feedback_enabled=0`, `haptic_feedback_intensity=0` and `keyboard_vibration_enabled=0`, so TOUCH, UNKNOWN and MEDIA vibrations were `ignored_for_settings` in every ringer mode; NOTIFICATION/RINGTONE were also ignored in SILENT; ALARM, ACCESSIBILITY and COMMUNICATION_REQUEST were delivered in all modes.
- Chosen: USAGE_ACCESSIBILITY (`VibrationAttributes`, API 33+) and USAGE_ALARM (`AudioAttributes`, API 26-32, measured on device), so the only haptic cue for the father works in normal, vibrate and silent modes whatever the touch-vibration setting is.
- The system halves the requested amplitude on M06 (`played` 0.52 for `original` 1.00) regardless of usage; with the weak motor a single 300 ms buzz is not perceptible, hence 600 ms at amplitude 255 (Q75).
- Rejected: leaving the usage unset (silently dropped), NOTIFICATION usage (dropped in silent mode), 3 x 300 ms pulses and 1000 ms (not chosen by the user).
- If another phone class feels the vibration too weak or too long, adjust `Feedback.kt` (`VIBRATE_MS`, `VIBRATE_AMPLITUDE`).

## D18. Badge tap (immediate resend) added, then removed (issue #1, 2026-10-22/23)
- Added: tapping "未送信 N件" checked connectivity and called `triggerNow()` (merge 338859e).
- Removed (user): WorkManager auto-resend already runs as soon as the network returns (observed on M06), and the father would never notice a tappable badge. Remaining value (skipping a backoff wait after a transient failure while online) is too small.
- Instead: make the badge text say what happens (offline "未送信 N件 ネット接続時再送します", online "未送信 N件 は自動で再送されます"), together with an online/offline text indicator: issue #3 (not scheduled).
