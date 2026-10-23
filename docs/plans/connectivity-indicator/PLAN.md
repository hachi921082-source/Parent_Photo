# connectivity-indicator (GitHub issue #3)

Scope: Camera-screen pill showing online/offline text. Spec refs: ds 3.1 (strings), 3.4 (look), 6.2 (offline semantics). Badge wording stays "未送信 N件" (D18, badge tap feature removed; nothing of it remains).

## Design
- `Connectivity.onlineFlow(context)`: `callbackFlow` over `registerDefaultNetworkCallback`. Emits `!isClearlyOffline` once at start, `INTERNET` capability on `onCapabilitiesChanged`, re-check on `onLost`. `conflate().distinctUntilChanged()`. No manager / registration failure -> online (conservative).
- `ConnectivitySource { online: Flow<Boolean> }` in `MainPorts.kt`; `AlwaysOnline` default; real `ContextConnectivity` in `MainViewModelFactory`.
- `MainViewModel` collects into `MainUiModel.online` (default true), like `activeCount`. No effect on state transitions.
- `CameraScreen(online = ...)`; `ConnectivityIndicator` in `FatherComponents.kt`; strings `connectivity_online` / `connectivity_offline`; drawables `ic_signal`, `ic_signal_off`.

## Implementation notes (recommended look, not asked of the user)
- Placement: top-left, fixed 108 dp from top inset (16 + two 38 dp badge rows + 8 dp gaps x2), 16 dp from left. Fixed so badges (geometry tests untouched) never shift and the pill never jumps when badge counts change. Not near the shutter or the OutboxFull guidance. Portrait-locked, not rotated.
- Look: dark pill like badges, white 24 dp signal-bars icon (slashed = offline), 18 sp text (fits 360 dp in one line, wraps to 2 if narrower). Shown in Camera, OutboxFull, Capturing.

## Tests
- JVM: `MainViewModelTest` (online flows into the UI model; offline does not change state/OutboxFull).
- androidTest `FatherScreensTest`: both texts, switching, badges do not move, no overlap with shutter in OutboxFull/Capturing.
