# preview-many-recipients (GitHub issue #4)

Scope: Preview layout (ds 3.4) keeps the photo large with many recipients. Decided by the user: やり直す moves to the top; recipient buttons compress at 4+; photo floor = photo height with exactly 3 recipients; scroll must be discoverable. CancelWindow/Sending unchanged.

## Layout (H = height inside system-bar insets, b = button size setting 56/96/128)
Top to bottom: 16 dp top margin, やり直す (full width, outlined, height >= b), 16 dp gap, photo (weight 1, gear at its top-right), 16 dp gap, recipient list (or "送り先が登録されていません"), 16 dp bottom margin.

## Sizing rule (pure function `PreviewListSizing`, `ui/PreviewListLayout.kt`)
- `availableHeight = min(L3, H - (16 + b + 16) - 32 - 0.25 H)`, `L3 = 3b + 2*16` (list height of 3 recipients). L3 as the cap = the photo never gets smaller than its 3-recipient size. The `0.25 H` term is an absolute photo floor for small screens where even 3 recipients do not fit (replaces the old 30 % floor; only active when the 3-recipient photo would be < 25 % H).
- `layout(available, n, b)`: natural height `n*b + (n-1)*16` fits -> unchanged (0-3 recipients: photo takes all remaining height). Else shrink button height down to min(b, 56) with 16 dp spacing; else spacing 8 dp with height >= 56; else scroll: buttons 56 dp, spacing 8 dp, viewport = `k*(56+8) + 28` (largest k that fits) so the last visible button is half cut.
- Examples: b=96: 4 -> 68 dp @16; 5 -> 57.6 dp @8; 6 -> scrolls (56 dp @8). b=128: 6 -> 56 dp @16 (fits exactly). b=56: 4+ scrolls.
- Text wrapping to 2 lines may still grow a button; the list then scrolls (height is fixed when `scrollable`, else `heightIn(max)`).

## Files
- `ui/PreviewListLayout.kt` (new, pure), `ui/PreviewScreens.kt` (`PreviewScreen`), `ui/FatherComponents.kt` (`FatherFilledButton(minHeight)`).
- Tests: JVM `PreviewListSizingTest`; androidTest `FatherScreensTest` (retake on top, photo >= 3-recipient photo for 1/4/6 x 56/96/128, 96 dp + 16 dp spacing up to 3, compress at 4, half-cut at 6/LARGE, inset test).

## Implementation notes (look choices, not asked of the user)
- Scroll hint = half-cut button only (no scrollbar): the viewport is snapped to "k buttons + half" so the cut is always visible; at 6/LARGE the 5th button is half visible and the 6th is below.
- Compress order: height first (spacing stays 16), spacing 16 -> 8 only when height would go below 56.
- Top margin 16 dp above やり直す, 16 dp gap to the photo (the spec's general 16 dp rhythm).
- Photo does not jump (follow-up decided by the user): CancelWindow and Sending use the same frame as Preview (16 dp top margin, top row of height b, 16 dp gap, photo, 16 dp gap, bottom area of height = Preview list viewport height for the current recipient count, 16 dp bottom margin), so the photo rect is identical. CancelWindow puts 取り消す in the top row (where やり直す is); Sending leaves it empty. "n秒後に送ります" / "送信中..." is centered in the bottom area. `CancelWindowScreen`/`SendingScreen` take `buttonSize` and `recipientCount` (from `MainUiModel.recipients`); state machine unchanged. Test: photo rect equal across Preview/CancelWindow/Sending for 1, 3, 4, 6 recipients x 56/96/128.
- 128 dp at 5-6 recipients ends up with 56-70 dp buttons, i.e. nearly the standard size; intended by the "down to 56 dp minimum" decision.
