package com.example.parentphoto.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 状態に応じて1画面を出す入れ物(ds 2.1)。設定は別ルートとして重ねずに差し替える。
 * 戻る操作: 写真を失いうる状態(Loading〜QueuedNotice、UiState.blocksBack)では無視し、
 * Camera / OutboxFull / SetupRequired / PermissionDenied は既定のまま(アプリを抜ける、ds 2.3)。
 */
@Composable
fun MainScreen(model: MainUiModel, viewModel: MainViewModel) {
    if (model.settingsOpen) {
        // 設定から戻ると元の状態に戻り、優先順位を評価し直す
        BackHandler(enabled = true) { viewModel.onSettingsClosed() }
        // TODO(settings-screen): 隠し設定画面をここに置く
        Box(Modifier.fillMaxSize())
        return
    }

    // enabled = false のときは既定の戻る動作(Android 13 以降の予測型「戻る」も含む)
    BackHandler(enabled = model.state.blocksBack) { }

    when (model.state) {
        // TODO(PLAN-2): 各画面(LoadingScreen, SetupRequiredScreen, PermissionDeniedScreen,
        //  CameraScreen(OutboxFull / Capturing を含む), PreviewScreen, CancelWindowScreen,
        //  SendingScreen, SentScreen, QueuedNoticeScreen)と設定アイコンの長押しをここに置く
        else -> Box(Modifier.fillMaxSize())
    }
}
