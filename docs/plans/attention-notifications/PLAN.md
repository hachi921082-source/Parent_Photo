# Plan: attention-notifications

Source: ds 10.1, 3.1, 3.2 (badges), 2.4. Branch: `feature/attention-notifications`. Depends on: app-foundation (channel), settings-storage (`notif_permission_asked`). BLOCKED on pending plan outbox-queue for the trigger (item becomes 要確認, overflow flag) and counts; build against the `OutboxRepository` contract.

## Index
1. Scope
2. Components
3. Behavior
4. Blocked parts
5. Tests

## 1. Scope
Notification for items needing attention, POST_NOTIFICATIONS request, badge data. Badge rendering itself is in photo-send-flow PLAN-2.

## 2. Components (package `notify/`)
- `AttentionNotifier`: `show(overflowDeleted: Boolean)`, `cancel()`. Channel `attention` (created in app-foundation), id 1001 (replaced, not stacked), small icon `ic_stat_photo`. Texts: "写真が送れていません。家族に連絡してください" or with overflow "写真が送れていません。古い写真1枚を削除しました。家族に連絡してください". No technical detail.
- Tap intent: `PendingIntent.getActivity` (`FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`) to `MainActivity` with `FLAG_ACTIVITY_REORDER_TO_FRONT | FLAG_ACTIVITY_SINGLE_TOP`; brings the task to front without changing the current screen; no deep link.
- `NotificationPermissionRequester`: on API 33+, request `POST_NOTIFICATIONS` once (flag `notif_permission_asked`) the first time Camera is shown after camera permission is resolved. If denied only the badges are shown. API < 33: no request.

## 3. Behavior
- Fires whenever an item becomes 要確認 (auth, permanent, retry expired, recipient deleted); overflow wording when that transition deleted the oldest 要確認 item.
- Cancelled (`cancel(1001)`) when 要確認 count becomes 0, observed in `OutboxRepository`.
- No notification for success, QueuedNotice, or later successful delivery.
- Badges: "未送信 N件" from active count; "送れていません M件" from attention count (rendering in PLAN-2 of photo-send-flow).

## 4. Blocked parts
The call sites (repository hooks on 要確認 transition and count 0) are added by outbox-queue; this plan provides `AttentionNotifier` and the permission requester and tests them directly.

## 5. Tests
Instrumented: notification posts with both texts, replaced not stacked, tap intent flags, cancel; permission requested once and never again after the flag is set (API 33+ emulator).
