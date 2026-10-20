package com.example.parentphoto.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.parentphoto.mail.FailureKind
import com.example.parentphoto.mail.ItemId
import com.example.parentphoto.mail.MailCode
import com.example.parentphoto.mail.MailResult
import com.example.parentphoto.notify.NotificationPermissionRequester
import com.example.parentphoto.notify.SuccessFeedback
import com.example.parentphoto.outbox.OutboxFullException
import com.example.parentphoto.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 父親向け画面の状態機械(ds 2、photo-send-flow PLAN-1)。
 * 優先順位(ds 2.2)の評価、撮影・やり直す・設定の出入り、撮影失敗メッセージのタイマー、
 * プレビュー中の待機枠予約(ds 2.4)を受け持つ。すべて viewModelScope(メインスレッド)で動く。
 * 送り先タップ後は 取り消し猶予(3秒)→ 前面送信(最大60秒)→「送りました！」/「あとで送ります」(各2秒)
 * → Camera(または OutboxFull)と進む(ds 2.3、PLAN-3)。タイマーと送信はバックグラウンドでも続く。
 */
class MainViewModel(
    private val settingsSource: SendFlowSettings,
    private val outbox: SendFlowOutbox,
    private val capturer: PhotoCapturer,
    private val cameraPermission: CameraPermissionGate,
    // null なら通知権限は要求しない(テスト用)
    private val notificationPermission: NotificationPermissionRequester?,
    // タイマーの基準。本番は SystemClock.elapsedRealtime(端末のスリープ中も進む)
    private val elapsedRealtime: () -> Long,
    // 撮影時刻(メールの件名・本文に使う)。本番は System.currentTimeMillis
    private val wallClock: () -> Long,
    // 撮影失敗を Logcat(ParentPhoto/Camera)に残す
    private val cameraLog: (String) -> Unit,
    // 送信成功の振動と音(ds 10.2)
    private val feedback: SuccessFeedback = SuccessFeedback {},
    // 送信の流れの異常を Logcat に残す(項目idのみ)
    private val flowLog: (String) -> Unit = {},
) : ViewModel() {

    private val model = MutableStateFlow(MainUiModel())

    /** 画面に渡す状態 */
    val ui: StateFlow<MainUiModel> = model.asStateFlow()

    private val state: UiState get() = model.value.state

    // ---- 優先順位の入力 ----
    private var settingsLoaded = false
    private var recoveryDone = false
    private var setupRequired = false
    private var activeCount = 0

    // カメラ権限のシステムダイアログを出してから結果が返るまで true(その間は Loading のまま)
    private var cameraPermissionInFlight = false

    // 通知権限の判定はこの画面の寿命で1回だけ(永続フラグは NotificationPermissionRequester 側)
    private var notificationChecked = false

    // 撮影済みの写真。Preview 中と、設定から戻って SetupRequired になった間も保持する(ds 2.2)
    private var photo: UiState.Preview? = null

    private val failureMessageTimer = DeadlineTimer(viewModelScope, elapsedRealtime)
    private val outboxFullNoticeTimer = DeadlineTimer(viewModelScope, elapsedRealtime)

    // Sent / QueuedNotice の2秒
    private val resultScreenTimer = DeadlineTimer(viewModelScope, elapsedRealtime)

    // 取り消し猶予の期限(elapsedRealtime)と、秒数の表示を進めるジョブ
    private var undoDeadline = 0L
    private var undoJob: Job? = null

    // createItem の完了待ち。この間は Preview の操作を受け付けない(二度押しで2通作らない)
    private var creatingItem = false

    init {
        // コールドスタート時は前回の写真を復元しない(ds 2.2, Q46)
        capturer.deleteFiles()
        outbox.setPreviewExists(false)
        viewModelScope.launch {
            settingsSource.settings.collect { s ->
                setupRequired = settingsSource.isSetupRequired(s)
                settingsLoaded = true
                model.update { it.copy(recipients = s.recipients, buttonSize = s.buttonSize) }
                reevaluate()
            }
        }
        viewModelScope.launch {
            outbox.recoveryDone.collect {
                recoveryDone = it
                reevaluate()
            }
        }
        viewModelScope.launch {
            outbox.activeCount.collect { n ->
                activeCount = n
                model.update { it.copy(unsentCount = n) }
                // Camera と OutboxFull はここで自動的に切り替わる
                reevaluate()
            }
        }
        viewModelScope.launch {
            outbox.attentionCount.collect { m -> model.update { it.copy(attentionCount = m) } }
        }
    }

    // ---- Activity から ----

    /**
     * Activity の ON_RESUME ごとに呼ぶ。権限の再要求はせずに優先順位を評価し直す(ds 2.2, Q58, Q62)。
     * スリープ中に期限を過ぎたタイマーもここで処理する。
     */
    fun onResume() {
        failureMessageTimer.onResume()
        outboxFullNoticeTimer.onResume()
        resultScreenTimer.onResume()
        // スリープで delay が止まっていた場合に備え、残り時間を時計から計算し直す
        (state as? UiState.CancelWindow)?.let { runUndoCountdown(it.itemId) }
        reevaluate()
    }

    /** カメラ権限ダイアログを出した直後に呼ぶ(同じ要求を二度出さないため) */
    fun onCameraPermissionRequestLaunched() {
        model.update { it.copy(cameraPermissionRequest = false) }
    }

    fun onCameraPermissionResult(@Suppress("UNUSED_PARAMETER") granted: Boolean) {
        // 結果は isGranted() で読み直す(設定アプリで変わることもあるため)
        cameraPermissionInFlight = false
        reevaluate()
    }

    /** 通知権限ダイアログを出した直後に呼ぶ。結果は使わない(拒否ならバッジのみ、ds 10.1) */
    fun onNotificationPermissionRequestLaunched() {
        model.update { it.copy(notificationPermissionRequest = false) }
    }

    // ---- 画面の操作 ----

    /** シャッター。Camera で待機中が5件未満のときだけ受け付ける */
    fun onShutter() {
        if (state != UiState.Camera || activeCount >= Constants.ACTIVE_CAP) return
        val capturedAt = wallClock()
        setState(UiState.Capturing)
        viewModelScope.launch {
            val result = try {
                capturer.capture()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // OOM などで例外が漏れても撮影失敗として扱う
                Result.failure(e)
            }
            result.fold(
                onSuccess = { file -> onCaptured(capturedAt, file) },
                onFailure = { e -> onCaptureFailed(e) },
            )
        }
    }

    /** カメラの初期化(束ね込み)失敗。撮影失敗と同じメッセージを出す。再試行は次の resume(ds 4.2) */
    fun onCameraBindFailed(error: Throwable) {
        if (state != UiState.Camera && state != UiState.OutboxFull) return
        cameraLog("camera bind failed: ${error.javaClass.simpleName}")
        showCaptureFailure()
    }

    /** やり直す: 写真を消して Camera(または OutboxFull)へ */
    fun onRetake() {
        if (state !is UiState.Preview || creatingItem) return
        discardPhoto()
        setState(precedence())
    }

    /**
     * 送り先ボタン。待機中が5件以上なら項目を作らず、Preview と写真を残したまま
     * OutboxFull の案内を 3000 ms 重ねる(ds 2.4 の防御、Q65)。件数が減れば再び押せる。
     */
    fun onRecipientTapped(recipientId: String) {
        val p = state as? UiState.Preview ?: return
        if (creatingItem) return
        if (activeCount >= Constants.ACTIVE_CAP) {
            showOutboxFullNotice()
            return
        }
        creatingItem = true
        viewModelScope.launch {
            val itemId = try {
                outbox.createItem(recipientId, p.capturedAtMillis, p.file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutboxFullException) {
                // 画面側の確認をすり抜けた満杯(Q65)。写真は残す
                showOutboxFullNotice()
                null
            } catch (e: Exception) {
                // 写真のコピー失敗など。Preview のまま写真を残し、もう一度押せるようにする
                flowLog("createItem failed: ${e.javaClass.simpleName}")
                null
            } finally {
                creatingItem = false
            }
            if (itemId == null) return@launch
            // 項目そのものが枠を持つので、プレビューの予約を外す(outbox-queue PLAN-1 4)
            outbox.setPreviewExists(false)
            outboxFullNoticeTimer.cancel()
            model.update { it.copy(outboxFullNoticeVisible = false) }
            undoDeadline = elapsedRealtime() + Constants.UNDO_SECONDS * 1000L
            setState(UiState.CancelWindow(itemId, Constants.UNDO_SECONDS))
            runUndoCountdown(itemId)
        }
    }

    /** 取り消す: 項目を消して同じ写真の Preview に戻る(cache の写真は残す) */
    fun onCancelUndo() {
        val s = state as? UiState.CancelWindow ?: return
        undoJob?.cancel()
        undoJob = null
        // 取り消しで空いた枠を繰り上げに取られないよう、先に予約を戻す(outbox-queue PLAN-1 4)
        outbox.setPreviewExists(true)
        setState(photo ?: precedence())
        viewModelScope.launch {
            try {
                outbox.deleteItem(s.itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 消せなかった行は次回起動の復旧で送られる(取り消しの取りこぼしは受け入れる)
                flowLog("deleteItem failed id=${s.itemId}: ${e.javaClass.simpleName}")
            }
        }
    }

    /** 設定アイコンの3秒長押し。許された状態からだけ設定ルートを開く */
    fun onSettingsLongPress() {
        // 項目作成中(Preview のまま)は開かない。開くと作成後に設定の裏で猶予が進んでしまう
        if (model.value.settingsOpen || creatingItem || !state.allowsSettings) return
        model.update { it.copy(settingsOpen = true) }
    }

    /** 設定から戻る。設定が変わったかもしれないので優先順位を評価し直す(Preview の写真は保持) */
    fun onSettingsClosed() {
        if (!model.value.settingsOpen) return
        model.update { it.copy(settingsOpen = false) }
        val s = state
        if (s.isPrecedenceOwned || s is UiState.Preview) setState(precedence())
    }

    // 3, 2, 1 と表示し、期限で前面送信に進む。期限は elapsedRealtime なので再開時に呼び直してよい
    private fun runUndoCountdown(itemId: ItemId) {
        undoJob?.cancel()
        undoJob = viewModelScope.launch {
            while (true) {
                val remaining = undoDeadline - elapsedRealtime()
                if (remaining <= 0) break
                val seconds = ((remaining + 999) / 1000).toInt()
                setState(UiState.CancelWindow(itemId, seconds))
                // 次の秒の境目まで待つ
                delay(remaining - (seconds - 1) * 1000L)
            }
            undoJob = null
            startForegroundSend(itemId)
        }
    }

    private fun startForegroundSend(itemId: ItemId) {
        if ((state as? UiState.CancelWindow)?.itemId != itemId) return
        setState(UiState.Sending(itemId))
        viewModelScope.launch {
            guarded("setPhase") { outbox.startForegroundSend(itemId) }
            val result = try {
                withTimeoutOrNull(Constants.FG_SEND_TIMEOUT_MS) { outbox.sendForeground(itemId) }
                    ?: MailResult.Failure(FailureKind.TRANSIENT, MailCode.TIMEOUT, null, "foreground cap", null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MailResult.Failure(FailureKind.TRANSIENT, MailCode.OTHER, e.javaClass.name, null, null)
            }
            if (result == MailResult.Success) {
                guarded("markSent") { outbox.markSent(itemId) }
                setState(UiState.Sent)
                // 「送りました！」に入った時に1回だけ。再開時には鳴らし直さない
                runCatching { feedback.play() }
                resultScreenTimer.start(Constants.SENT_SCREEN_MS) { finishSendFlow() }
            } else {
                guarded("markQueued") { outbox.markQueued(itemId, result) }
                setState(UiState.QueuedNotice)
                resultScreenTimer.start(Constants.QUEUED_NOTICE_MS) { finishSendFlow() }
            }
        }
    }

    // Sent / QueuedNotice の2秒後: cache の写真を消して Camera(または OutboxFull)へ
    private fun finishSendFlow() {
        if (state != UiState.Sent && state != UiState.QueuedNotice) return
        discardPhoto()
        setState(precedence())
    }

    // 未送信キューの書き込み失敗で流れを止めない(行は次回起動の復旧で送られる)
    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            flowLog("$what failed: ${e.javaClass.simpleName}")
        }
    }

    override fun onCleared() {
        // 画面が終わると写真は捨てられるので、枠の予約も外す
        outbox.setPreviewExists(false)
        // 猶予中・送信中に閉じられたら(プロセスは残っている)、強制終了と同じく裏ですぐ送る(Q69)
        when (val s = state) {
            is UiState.CancelWindow -> outbox.requeueAbandoned(s.itemId)
            is UiState.Sending -> outbox.requeueAbandoned(s.itemId)
            else -> Unit
        }
    }

    // ---- 内部 ----

    private fun onCaptured(capturedAt: Long, file: File) {
        val p = UiState.Preview(capturedAt, file)
        photo = p
        model.update { it.copy(photoFile = file) }
        outbox.setPreviewExists(true)
        setState(p)
    }

    private fun onCaptureFailed(e: Throwable) {
        capturer.deleteFiles()
        cameraLog("capture failed: ${e.javaClass.simpleName}")
        showCaptureFailure()
        setState(precedence())
    }

    // 3000 ms ちょうど表示する。表示中にもう一度失敗したら 3000 ms をやり直す(ds 2.3, Q45, Q55)
    private fun showCaptureFailure() {
        model.update { it.copy(captureFailureMessageVisible = true) }
        failureMessageTimer.start(Constants.CAPTURE_FAILURE_MESSAGE_MS) {
            model.update { it.copy(captureFailureMessageVisible = false) }
        }
    }

    // 満杯の拒否(画面側の確認、または createItem の OutboxFullException)
    private fun showOutboxFullNotice() {
        model.update { it.copy(outboxFullNoticeVisible = true) }
        outboxFullNoticeTimer.start(Constants.OUTBOX_FULL_NOTICE_MS) {
            model.update { it.copy(outboxFullNoticeVisible = false) }
        }
    }

    private fun discardPhoto() {
        photo = null
        outboxFullNoticeTimer.cancel()
        model.update { it.copy(photoFile = null, outboxFullNoticeVisible = false) }
        capturer.deleteFiles()
        outbox.setPreviewExists(false)
    }

    /**
     * 優先順位で決まる状態のときだけ置き換える。撮影中・プレビュー・送信の流れは中断しない(Q62)。
     * 設定を開いている間は評価しない(設定の保存で SetupRequired が解けても、設定画面の上にカメラ権限の
     * ダイアログを出さない)。閉じたときに onSettingsClosed が評価し直す(ds 2.2)。
     */
    private fun reevaluate() {
        if (model.value.settingsOpen) return
        if (state.isPrecedenceOwned) setState(precedence())
    }

    /** ds 2.2 の優先順位。カメラ権限の要求は1コールドスタートに1回だけ */
    private fun precedence(): UiState {
        if (!settingsLoaded || !recoveryDone) return UiState.Loading
        if (setupRequired) return UiState.SetupRequired
        photo?.let { return it }
        if (!cameraPermission.isGranted()) {
            if (cameraPermissionInFlight) return UiState.Loading
            if (cameraPermission.shouldRequest()) {
                cameraPermission.markRequested()
                cameraPermissionInFlight = true
                model.update { it.copy(cameraPermissionRequest = true) }
                return UiState.Loading
            }
            return UiState.PermissionDenied
        }
        return if (activeCount >= Constants.ACTIVE_CAP) UiState.OutboxFull else UiState.Camera
    }

    private fun setState(next: UiState) {
        if (state != next) model.update { it.copy(state = next) }
        if (next == UiState.Camera || next == UiState.OutboxFull) maybeRequestNotificationPermission()
    }

    // カメラ権限が決まってカメラを初めて表示した時に1回だけ(ds 10.1)
    private fun maybeRequestNotificationPermission() {
        val requester = notificationPermission ?: return
        // 設定画面の上には出さない(閉じて Camera に戻った時に判定する)
        if (notificationChecked || model.value.settingsOpen) return
        notificationChecked = true
        viewModelScope.launch {
            requester.requestIfNeeded { model.update { it.copy(notificationPermissionRequest = true) } }
        }
    }
}

/**
 * 期限(経過時間の時計)で動く1本のタイマー。
 * delay はスリープ中に止まることがあるため、再開時に onResume() で期限を確かめ直す。
 * PLAN-3 の取り消し猶予・2秒画面でも使う。
 */
internal class DeadlineTimer(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private var job: Job? = null
    private var deadline = 0L
    private var action: (() -> Unit)? = null

    /** 動いていれば取り消してから、durationMs 後に action を1回だけ実行する */
    fun start(durationMs: Long, action: () -> Unit) {
        cancel()
        deadline = clock() + durationMs
        this.action = action
        arm(durationMs)
    }

    fun cancel() {
        job?.cancel()
        job = null
        action = null
    }

    /** 期限を過ぎていればすぐ実行し、過ぎていなければ残り時間で待ち直す */
    fun onResume() {
        if (action == null) return
        val remaining = deadline - clock()
        if (remaining <= 0) fire() else arm(remaining)
    }

    private fun arm(waitMs: Long) {
        job?.cancel()
        job = scope.launch {
            delay(waitMs)
            job = null
            fire()
        }
    }

    private fun fire() {
        val a = action ?: return
        action = null
        job?.cancel()
        job = null
        a()
    }
}
