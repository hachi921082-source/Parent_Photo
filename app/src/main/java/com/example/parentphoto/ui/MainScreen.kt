package com.example.parentphoto.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.parentphoto.camera.CameraController
import com.example.parentphoto.camera.CameraView
import com.example.parentphoto.ui.theme.FatherTheme

/**
 * 状態に応じて1画面を出す入れ物(ds 2.1)。設定は別ルートとして重ねずに差し替える。
 * 戻る操作: 写真を失いうる状態(Loading〜QueuedNotice、UiState.blocksBack)では無視し、
 * Camera / OutboxFull / SetupRequired / PermissionDenied は既定のまま(アプリを抜ける、ds 2.3)。
 * 父親向け画面は端末のフォント倍率を無視する(FatherTheme、Q51)。設定画面は従う。
 */
@Composable
fun MainScreen(model: MainUiModel, viewModel: MainViewModel, cameraController: CameraController) {
    if (model.settingsOpen) {
        // 設定から戻ると元の状態に戻り、優先順位を評価し直す
        BackHandler(enabled = true) { viewModel.onSettingsClosed() }
        // TODO(settings-screen): 隠し設定画面をここに置く
        Box(Modifier.fillMaxSize())
        return
    }

    // enabled = false のときは既定の戻る動作(Android 13 以降の予測型「戻る」も含む)
    BackHandler(enabled = model.state.blocksBack) { }

    val context = LocalContext.current
    FatherTheme {
        when (val s = model.state) {
            UiState.Loading -> LoadingScreen()
            UiState.SetupRequired -> SetupRequiredScreen(viewModel::onSettingsLongPress)
            UiState.PermissionDenied -> PermissionDeniedScreen(
                buttonSize = model.buttonSize,
                onOpenSettings = {
                    // アプリ情報の画面。戻ると ON_RESUME で権限を読み直す(ds 2.3)
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                },
                onSettingsLongPress = viewModel::onSettingsLongPress,
            )
            // 3つの状態で同じ位置に置き、撮影中もカメラの束ね込みを保つ
            UiState.Camera, UiState.OutboxFull, UiState.Capturing -> CameraScreen(
                state = s,
                unsentCount = model.unsentCount,
                attentionCount = model.attentionCount,
                captureFailureVisible = model.captureFailureMessageVisible,
                onShutter = viewModel::onShutter,
                onSettingsLongPress = viewModel::onSettingsLongPress,
                cameraFeed = {
                    CameraView(
                        controller = cameraController,
                        modifier = Modifier.fillMaxSize(),
                        onBindFailure = viewModel::onCameraBindFailed,
                    )
                },
            )
            is UiState.Preview -> PreviewScreen(
                photoFile = s.file,
                recipients = model.recipients,
                buttonSize = model.buttonSize,
                outboxFullNoticeVisible = model.outboxFullNoticeVisible,
                onRecipient = viewModel::onRecipientTapped,
                onRetake = viewModel::onRetake,
                onSettingsLongPress = viewModel::onSettingsLongPress,
            )
            // TODO(PLAN-3): 取り消すの操作(onCancel)を状態機械につなぐ
            is UiState.CancelWindow -> CancelWindowScreen(model.photoFile, s.secondsLeft, model.buttonSize) {}
            is UiState.Sending -> SendingScreen(model.photoFile)
            UiState.Sent -> SentScreen()
            UiState.QueuedNotice -> QueuedNoticeScreen()
        }
    }
}
