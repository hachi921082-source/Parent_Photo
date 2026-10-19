package com.example.parentphoto.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.parentphoto.ParentPhotoApp
import com.example.parentphoto.camera.CameraController
import com.example.parentphoto.camera.CameraPermission
import com.example.parentphoto.camera.PreviewFiles
import com.example.parentphoto.notify.NotificationPermissionRequester
import com.example.parentphoto.outbox.OutboxRepository
import com.example.parentphoto.settings.Settings
import com.example.parentphoto.settings.SettingsRepository
import com.example.parentphoto.settings.SetupState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MainViewModel を本物の部品(ParentPhotoApp の共有インスタンス)で組み立てる。
 * 設定・未送信キュー・カメラは新しく作らず、アプリに1つだけあるものを使う。
 */
class MainViewModelFactory(private val app: ParentPhotoApp) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(MainViewModel::class.java)) { "unknown ViewModel: $modelClass" }
        val vm = MainViewModel(
            settingsSource = RepositorySettings(app.settingsRepository),
            outbox = RepositoryOutbox(app.outboxRepository),
            capturer = ControllerCapturer(app.cameraController, app.previewFiles),
            cameraPermission = ContextCameraPermission(app),
            notificationPermission = NotificationPermissionRequester(app, app.settingsRepository),
            elapsedRealtime = SystemClock::elapsedRealtime,
            wallClock = System::currentTimeMillis,
            cameraLog = { Log.e(TAG_CAMERA, it) },
        )
        @Suppress("UNCHECKED_CAST")
        return vm as T
    }

    private companion object {
        const val TAG_CAMERA = "ParentPhoto/Camera"
    }
}

/** SettingsRepository を画面用の窓口にする。パスワードの復号は IO で行う */
private class RepositorySettings(private val repo: SettingsRepository) : SendFlowSettings {
    override val settings: Flow<Settings> = repo.settings

    override suspend fun isSetupRequired(settings: Settings): Boolean {
        val passwordReadable = withContext(Dispatchers.IO) { repo.readPassword() != null }
        return SetupState.isSetupRequired(settings, passwordReadable)
    }
}

/** OutboxRepository のうち画面が使う部分 */
private class RepositoryOutbox(private val repo: OutboxRepository) : SendFlowOutbox {
    override val activeCount: Flow<Int> = repo.activeCount
    override val attentionCount: Flow<Int> = repo.attentionCount
    override val recoveryDone: Flow<Boolean> = repo.recoveryDone

    override fun setPreviewExists(exists: Boolean) = repo.setPreviewExists(exists)
}

/** CameraController で撮影する。束ね込みは CameraView(画面側)が行う */
private class ControllerCapturer(
    private val controller: CameraController,
    private val files: PreviewFiles,
) : PhotoCapturer {
    override suspend fun capture(): Result<File> = controller.capture().map { it.previewFile }

    override fun deleteFiles() = files.deleteAll()
}

private class ContextCameraPermission(context: Context) : CameraPermissionGate {
    private val appContext = context.applicationContext

    override fun isGranted(): Boolean = CameraPermission.isGranted(appContext)
    override fun shouldRequest(): Boolean = CameraPermission.shouldRequest(appContext)
    override fun markRequested() = CameraPermission.markRequested()
}
