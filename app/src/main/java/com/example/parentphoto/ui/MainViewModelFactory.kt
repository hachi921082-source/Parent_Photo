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
import com.example.parentphoto.mail.ConnectivityChecker
import com.example.parentphoto.mail.ItemId
import com.example.parentphoto.mail.MailResult
import com.example.parentphoto.mail.SmtpSender
import com.example.parentphoto.notify.Feedback
import com.example.parentphoto.notify.NotificationPermissionRequester
import com.example.parentphoto.outbox.OutboxRepository
import com.example.parentphoto.outbox.Phase
import com.example.parentphoto.outbox.WorkScheduler
import com.example.parentphoto.settings.Settings
import com.example.parentphoto.settings.SettingsRepository
import com.example.parentphoto.settings.SetupState
import com.example.parentphoto.util.Connectivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
            outbox = RepositoryOutbox(
                app.outboxRepository,
                app.smtpSender,
                app.workScheduler,
                ConnectivityChecker { Connectivity.isClearlyOffline(app) },
                app.appScope,
            ),
            capturer = ControllerCapturer(app.cameraController, app.previewFiles),
            cameraPermission = ContextCameraPermission(app),
            notificationPermission = NotificationPermissionRequester(app, app.settingsRepository),
            elapsedRealtime = SystemClock::elapsedRealtime,
            wallClock = System::currentTimeMillis,
            cameraLog = { Log.e(TAG_CAMERA, it) },
            feedback = Feedback(app, app.appScope),
            flowLog = { Log.w(TAG_FLOW, it) },
        )
        @Suppress("UNCHECKED_CAST")
        return vm as T
    }

    private companion object {
        const val TAG_CAMERA = "ParentPhoto/Camera"
        const val TAG_FLOW = "ParentPhoto/Flow"
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

/** OutboxRepository と前面送信(SmtpSender)のうち画面が使う部分 */
private class RepositoryOutbox(
    private val repo: OutboxRepository,
    private val sender: SmtpSender,
    private val scheduler: WorkScheduler,
    private val connectivity: ConnectivityChecker,
    // プロセスと同じ寿命のスコープ(画面が終わっても続ける処理用)
    private val appScope: CoroutineScope,
) : SendFlowOutbox {
    override val activeCount: Flow<Int> = repo.activeCount
    override val attentionCount: Flow<Int> = repo.attentionCount
    override val recoveryDone: Flow<Boolean> = repo.recoveryDone

    override fun setPreviewExists(exists: Boolean) = repo.setPreviewExists(exists)

    override suspend fun createItem(recipientId: String, capturedAtMillis: Long, previewFile: File): ItemId =
        repo.createItem(recipientId, capturedAtMillis, previewFile)

    override suspend fun deleteItem(itemId: ItemId) = repo.deleteItem(itemId)

    override suspend fun startForegroundSend(itemId: ItemId) = repo.setPhase(itemId, Phase.SENDING_FG)

    // 設定の読み出しとパスワードの復号を含むので IO で行う
    override suspend fun sendForeground(itemId: ItemId): MailResult =
        withContext(Dispatchers.IO) { sender.sendForeground(itemId) }

    // 回線が明らかに無ければ試行しない。あれば待ち時間(バックオフ)を解いてワーカーをすぐ動かす
    override fun retryNow(): Boolean {
        if (connectivity.isClearlyOffline()) return false
        scheduler.triggerNow()
        return true
    }

    override suspend fun markSent(itemId: ItemId) = repo.markSent(itemId)

    override suspend fun markQueued(itemId: ItemId, result: MailResult) = repo.markQueued(itemId, result)

    override fun requeueAbandoned(itemId: ItemId) {
        appScope.launch { repo.requeueAbandoned(itemId) }
    }
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
