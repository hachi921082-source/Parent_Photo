package com.example.parentphoto.ui

import com.example.parentphoto.mail.FailureKind
import com.example.parentphoto.mail.ItemId
import com.example.parentphoto.mail.MailCode
import com.example.parentphoto.mail.MailResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.example.parentphoto.notify.NotificationPermissionRequester
import com.example.parentphoto.outbox.OutboxFullException
import com.example.parentphoto.settings.ButtonSize
import com.example.parentphoto.settings.Recipient
import com.example.parentphoto.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    // ---- 偽物 ----

    private class FakeSettings : SendFlowSettings {
        val flow = MutableSharedFlow<Settings>(replay = 1)
        var required = false
        override val settings: Flow<Settings> = flow
        override suspend fun isSetupRequired(settings: Settings) = required
    }

    private class FakeOutbox : SendFlowOutbox {
        override val activeCount = MutableStateFlow(0)
        override val attentionCount = MutableStateFlow(0)
        override val recoveryDone = MutableStateFlow(false)
        val previewExistsHistory = mutableListOf<Boolean>()
        val previewExists get() = previewExistsHistory.lastOrNull() ?: false

        // 呼び出し順(予約と削除の前後を確かめる)
        val events = mutableListOf<String>()
        var createGate: CompletableDeferred<Unit>? = null
        var createError: Exception? = null
        var created = 0
        var lastCreate: Triple<String, Long, File>? = null
        var sendGate = CompletableDeferred<MailResult>()
        val queued = mutableListOf<Pair<String, MailResult>>()

        override fun setPreviewExists(exists: Boolean) {
            previewExistsHistory += exists
            events += "preview=$exists"
        }

        override suspend fun createItem(recipientId: String, capturedAtMillis: Long, previewFile: File): ItemId {
            createGate?.await()
            createError?.let { throw it }
            created++
            lastCreate = Triple(recipientId, capturedAtMillis, previewFile)
            events += "create"
            return "item$created"
        }

        override suspend fun deleteItem(itemId: ItemId) {
            events += "delete:$itemId"
        }

        override suspend fun startForegroundSend(itemId: ItemId) {
            events += "sendingFg:$itemId"
        }

        override suspend fun sendForeground(itemId: ItemId): MailResult {
            events += "send:$itemId"
            return sendGate.await()
        }

        override suspend fun markSent(itemId: ItemId) {
            events += "sent:$itemId"
        }

        override suspend fun markQueued(itemId: ItemId, result: MailResult) {
            queued += itemId to result
            events += "queued:$itemId"
        }

        val abandoned = mutableListOf<String>()
        override fun requeueAbandoned(itemId: ItemId) {
            abandoned += itemId
        }
    }

    private class FakeConnectivity : ConnectivitySource {
        override val online = MutableStateFlow(true)
    }

    private class FakeCapturer : PhotoCapturer {
        val file = File("preview.jpg")
        var next = CompletableDeferred<Result<File>>()
        var deleteCount = 0
        var captureCount = 0
        override suspend fun capture(): Result<File> {
            captureCount++
            return next.await()
        }
        override fun deleteFiles() {
            deleteCount++
        }
        fun succeed() = next.complete(Result.success(file))
        fun fail() = next.complete(Result.failure(IllegalStateException("x")))
    }

    private class FakePermission : CameraPermissionGate {
        var granted = true
        var requested = false
        var requestCount = 0
        override fun isGranted() = granted
        override fun shouldRequest() = !granted && !requested
        override fun markRequested() {
            requested = true
            requestCount++
        }
    }

    private val dispatcher = StandardTestDispatcher()
    private val settings = FakeSettings()
    private val outbox = FakeOutbox()
    private val capturer = FakeCapturer()
    private val connectivity = FakeConnectivity()
    private val permission = FakePermission()
    private val logs = mutableListOf<String>()

    // 端末のスリープで delay が止まった分を表す(経過時間の時計だけ進める)
    private var sleptMs = 0L
    private var notifAsked = false
    private var notifLaunched = 0
    private var feedbackCount = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.newVm(withNotif: Boolean = false): MainViewModel = MainViewModel(
        settingsSource = settings,
        outbox = outbox,
        capturer = capturer,
        cameraPermission = permission,
        notificationPermission = if (withNotif) {
            NotificationPermissionRequester(
                isAsked = { notifAsked },
                markAsked = { notifAsked = true },
                isGranted = { false },
                sdkInt = 33,
            )
        } else {
            null
        },
        elapsedRealtime = { testScheduler.currentTime + sleptMs },
        wallClock = { 1_700_000_000_000L + testScheduler.currentTime },
        cameraLog = { logs += it },
        feedback = { feedbackCount++ },
        flowLog = { logs += it },
        connectivity = connectivity,
    )

    /** 設定読み込みと復旧が済んだ状態にする */
    private fun TestScope.ready(vm: MainViewModel, s: Settings = Settings()): MainViewModel {
        settings.flow.tryEmit(s)
        outbox.recoveryDone.value = true
        runCurrent()
        return vm
    }

    private fun MainViewModel.state() = ui.value.state

    private fun TestScope.toPreview(vm: MainViewModel) {
        vm.onShutter()
        runCurrent()
        capturer.succeed()
        runCurrent()
    }

    // ---- 起動と優先順位 ----

    @Test
    fun 設定と復旧の両方が済むまでLoadingで起動時に写真を消す() = runTest(dispatcher) {
        val vm = newVm()
        runCurrent()
        assertEquals(1, capturer.deleteCount)
        assertEquals(listOf(false), outbox.previewExistsHistory)
        assertEquals(UiState.Loading, vm.state())

        settings.flow.tryEmit(Settings())
        runCurrent()
        assertEquals(UiState.Loading, vm.state())

        outbox.recoveryDone.value = true
        runCurrent()
        assertEquals(UiState.Camera, vm.state())
    }

    @Test
    fun 復旧が先でも設定の最初の発行までLoading() = runTest(dispatcher) {
        val vm = newVm()
        outbox.recoveryDone.value = true
        runCurrent()
        assertEquals(UiState.Loading, vm.state())
        settings.flow.tryEmit(Settings())
        runCurrent()
        assertEquals(UiState.Camera, vm.state())
    }

    @Test
    fun SetupRequiredは権限より先で権限を要求しない() = runTest(dispatcher) {
        settings.required = true
        permission.granted = false
        val vm = ready(newVm())
        assertEquals(UiState.SetupRequired, vm.state())
        assertFalse(vm.ui.value.cameraPermissionRequest)
        assertEquals(0, permission.requestCount)
    }

    @Test
    fun 権限未許可なら1回だけ要求し拒否でPermissionDenied() = runTest(dispatcher) {
        permission.granted = false
        val vm = ready(newVm())
        assertEquals(UiState.Loading, vm.state())
        assertTrue(vm.ui.value.cameraPermissionRequest)
        vm.onCameraPermissionRequestLaunched()
        assertFalse(vm.ui.value.cameraPermissionRequest)

        // ダイアログ中に件数が変わってもまだ PermissionDenied にしない
        outbox.activeCount.value = 1
        runCurrent()
        assertEquals(UiState.Loading, vm.state())

        vm.onCameraPermissionResult(false)
        assertEquals(UiState.PermissionDenied, vm.state())
        vm.onResume()
        assertEquals(UiState.PermissionDenied, vm.state())
        assertEquals(1, permission.requestCount)
        assertFalse(vm.ui.value.cameraPermissionRequest)
    }

    @Test
    fun 権限が許可されればCamera() = runTest(dispatcher) {
        permission.granted = false
        val vm = ready(newVm())
        vm.onCameraPermissionRequestLaunched()
        permission.granted = true
        vm.onCameraPermissionResult(true)
        assertEquals(UiState.Camera, vm.state())
    }

    @Test
    fun 待機中5件でOutboxFullになり減ればCameraに戻る() = runTest(dispatcher) {
        outbox.activeCount.value = 5
        val vm = ready(newVm())
        assertEquals(UiState.OutboxFull, vm.state())
        assertEquals(5, vm.ui.value.unsentCount)
        outbox.activeCount.value = 4
        runCurrent()
        assertEquals(UiState.Camera, vm.state())
        outbox.activeCount.value = 5
        runCurrent()
        assertEquals(UiState.OutboxFull, vm.state())
    }

    @Test
    fun バッジの件数と送り先とボタンの大きさを流す() = runTest(dispatcher) {
        val r = listOf(Recipient("1", "太郎", "a@example.com"))
        val vm = ready(newVm(), Settings(recipients = r, buttonSize = ButtonSize.XLARGE))
        outbox.activeCount.value = 2
        outbox.attentionCount.value = 3
        runCurrent()
        with(vm.ui.value) {
            assertEquals(2, unsentCount)
            assertEquals(3, attentionCount)
            assertEquals(r, recipients)
            assertEquals(ButtonSize.XLARGE, buttonSize)
        }
    }

    @Test
    fun 回線の状態を流す_既定はオンライン() = runTest(dispatcher) {
        val vm = ready(newVm())
        assertTrue(vm.ui.value.online)
        connectivity.online.value = false
        runCurrent()
        assertFalse(vm.ui.value.online)
        connectivity.online.value = true
        runCurrent()
        assertTrue(vm.ui.value.online)
    }

    @Test
    fun 圏外でも状態遷移とシャッターは変わらない() = runTest(dispatcher) {
        connectivity.online.value = false
        val vm = ready(newVm())
        assertEquals(UiState.Camera, vm.state())
        outbox.activeCount.value = 5
        runCurrent()
        assertEquals(UiState.OutboxFull, vm.state())
    }

    // ---- 再開時の評価 ----

    @Test
    fun 再開時にバックグラウンドで外された権限はPermissionDeniedで再要求しない() = runTest(dispatcher) {
        val vm = ready(newVm())
        assertEquals(UiState.Camera, vm.state())
        // 起動時に許可済みでも、この起動で要求済みとして扱われるのは CameraPermission と同じ
        permission.requested = true
        permission.granted = false
        vm.onResume()
        assertEquals(UiState.PermissionDenied, vm.state())
        assertFalse(vm.ui.value.cameraPermissionRequest)

        // システム設定で許可して戻る
        permission.granted = true
        vm.onResume()
        assertEquals(UiState.Camera, vm.state())
    }

    @Test
    fun 再開時の評価はPreviewを中断しない() = runTest(dispatcher) {
        val vm = ready(newVm())
        toPreview(vm)
        assertTrue(vm.state() is UiState.Preview)
        permission.requested = true
        permission.granted = false
        outbox.activeCount.value = 5
        runCurrent()
        vm.onResume()
        assertTrue(vm.state() is UiState.Preview)
    }

    @Test
    fun 再開時の評価は撮影中を中断しない() = runTest(dispatcher) {
        val vm = ready(newVm())
        vm.onShutter()
        runCurrent()
        assertEquals(UiState.Capturing, vm.state())
        settings.required = true
        settings.flow.tryEmit(Settings())
        runCurrent()
        vm.onResume()
        assertEquals(UiState.Capturing, vm.state())
        // 撮影失敗の出口では優先順位を評価する
        capturer.fail()
        runCurrent()
        assertEquals(UiState.SetupRequired, vm.state())
    }

    // ---- 撮影 ----

    @Test
    fun シャッターで撮影中からPreviewになり枠を予約する() = runTest(dispatcher) {
        val vm = ready(newVm())
        advanceTimeBy(1234)
        vm.onShutter()
        assertEquals(UiState.Capturing, vm.state())
        runCurrent()
        assertEquals(1, capturer.captureCount)
        // 撮影中の二度押しは無視
        vm.onShutter()
        runCurrent()
        assertEquals(1, capturer.captureCount)

        capturer.succeed()
        runCurrent()
        val p = vm.state() as UiState.Preview
        assertEquals(1_700_000_000_000L + 1234, p.capturedAtMillis)
        assertEquals(capturer.file, p.file)
        assertTrue(outbox.previewExists)
    }

    @Test
    fun OutboxFullではシャッターを受け付けない() = runTest(dispatcher) {
        outbox.activeCount.value = 5
        val vm = ready(newVm())
        vm.onShutter()
        runCurrent()
        assertEquals(UiState.OutboxFull, vm.state())
        assertEquals(0, capturer.captureCount)
    }

    @Test
    fun 撮影失敗はCameraに戻りメッセージをちょうど3000ms出す() = runTest(dispatcher) {
        val vm = ready(newVm())
        val deletesBefore = capturer.deleteCount
        vm.onShutter()
        runCurrent()
        capturer.fail()
        runCurrent()
        assertEquals(UiState.Camera, vm.state())
        assertTrue(vm.ui.value.captureFailureMessageVisible)
        assertTrue(capturer.deleteCount > deletesBefore)
        assertTrue(logs.any { it.startsWith("capture failed") })
        assertFalse(outbox.previewExists)

        advanceTimeBy(2999)
        runCurrent()
        assertTrue(vm.ui.value.captureFailureMessageVisible)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(vm.ui.value.captureFailureMessageVisible)
    }

    @Test
    fun 表示中の二度目の失敗は3000msをやり直す() = runTest(dispatcher) {
        val vm = ready(newVm())
        vm.onShutter()
        runCurrent()
        capturer.fail()
        runCurrent()

        advanceTimeBy(2000)
        runCurrent()
        capturer.next = CompletableDeferred()
        vm.onShutter()
        runCurrent()
        capturer.fail()
        runCurrent()
        assertTrue(vm.ui.value.captureFailureMessageVisible)

        // 1回目から3000ms(2回目から1000ms)では消えない
        advanceTimeBy(1000)
        runCurrent()
        assertTrue(vm.ui.value.captureFailureMessageVisible)
        advanceTimeBy(1999)
        runCurrent()
        assertTrue(vm.ui.value.captureFailureMessageVisible)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(vm.ui.value.captureFailureMessageVisible)
    }

    @Test
    fun 撮影中に5件になって失敗したらOutboxFullに戻る() = runTest(dispatcher) {
        val vm = ready(newVm())
        vm.onShutter()
        runCurrent()
        outbox.activeCount.value = 5
        runCurrent()
        assertEquals(UiState.Capturing, vm.state())
        capturer.fail()
        runCurrent()
        assertEquals(UiState.OutboxFull, vm.state())
        assertTrue(vm.ui.value.captureFailureMessageVisible)
    }

    @Test
    fun 撮影処理が例外を投げても撮影失敗として扱う() = runTest(dispatcher) {
        val vm = ready(newVm())
        capturer.next.completeExceptionally(OutOfMemoryError())
        vm.onShutter()
        runCurrent()
        assertEquals(UiState.Camera, vm.state())
        assertTrue(vm.ui.value.captureFailureMessageVisible)
    }

    @Test
    fun カメラの初期化失敗も同じメッセージを出す() = runTest(dispatcher) {
        val vm = ready(newVm())
        vm.onCameraBindFailed(IllegalStateException())
        assertTrue(vm.ui.value.captureFailureMessageVisible)
        assertEquals(UiState.Camera, vm.state())
        advanceTimeBy(3000)
        runCurrent()
        assertFalse(vm.ui.value.captureFailureMessageVisible)
    }

    @Test
    fun スリープ中に期限を過ぎたメッセージは再開時に消える() = runTest(dispatcher) {
        val vm = ready(newVm())
        vm.onCameraBindFailed(IllegalStateException())
        advanceTimeBy(1000)
        runCurrent()
        // delay は止まっていたが経過時間の時計は 3000ms 以上進んだ
        sleptMs = 2500
        vm.onResume()
        assertFalse(vm.ui.value.captureFailureMessageVisible)
    }

    @Test
    fun 期限前の再開では残り時間で消える() = runTest(dispatcher) {
        val vm = ready(newVm())
        vm.onCameraBindFailed(IllegalStateException())
        advanceTimeBy(1000)
        runCurrent()
        sleptMs = 1000
        vm.onResume()
        assertTrue(vm.ui.value.captureFailureMessageVisible)
        advanceTimeBy(999)
        runCurrent()
        assertTrue(vm.ui.value.captureFailureMessageVisible)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(vm.ui.value.captureFailureMessageVisible)
    }

    // ---- Preview ----

    @Test
    fun やり直すで写真を消してCameraに戻り予約を外す() = runTest(dispatcher) {
        val vm = ready(newVm())
        toPreview(vm)
        val deletes = capturer.deleteCount
        vm.onRetake()
        assertEquals(UiState.Camera, vm.state())
        assertEquals(deletes + 1, capturer.deleteCount)
        assertFalse(outbox.previewExists)
    }

    @Test
    fun やり直す時に5件ならOutboxFullに戻る() = runTest(dispatcher) {
        val vm = ready(newVm())
        toPreview(vm)
        outbox.activeCount.value = 5
        runCurrent()
        vm.onRetake()
        assertEquals(UiState.OutboxFull, vm.state())
    }

    @Test
    fun 待機中5件の送り先タップは受け付けずPreviewのまま() = runTest(dispatcher) {
        val vm = ready(newVm())
        toPreview(vm)
        outbox.activeCount.value = 5
        runCurrent()
        val before = vm.state()
        vm.onRecipientTapped("1")
        assertEquals(before, vm.state())
        assertTrue(outbox.previewExists)
        assertEquals(capturer.file, vm.ui.value.photoFile)
    }

    @Test
    fun 満杯の拒否で案内をちょうど3000ms重ね件数が減れば再び押せる() = runTest(dispatcher) {
        val vm = ready(newVm())
        toPreview(vm)
        outbox.activeCount.value = 5
        runCurrent()
        vm.onRecipientTapped("1")
        assertTrue(vm.ui.value.outboxFullNoticeVisible)
        advanceTimeBy(2999)
        runCurrent()
        assertTrue(vm.ui.value.outboxFullNoticeVisible)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(vm.ui.value.outboxFullNoticeVisible)

        outbox.activeCount.value = 4
        runCurrent()
        vm.onRecipientTapped("1")
        assertFalse(vm.ui.value.outboxFullNoticeVisible)
    }

    @Test
    fun やり直すで満杯の案内と写真の表示を消す() = runTest(dispatcher) {
        val vm = ready(newVm())
        toPreview(vm)
        assertEquals(capturer.file, vm.ui.value.photoFile)
        outbox.activeCount.value = 5
        runCurrent()
        vm.onRecipientTapped("1")
        vm.onRetake()
        assertFalse(vm.ui.value.outboxFullNoticeVisible)
        assertEquals(null, vm.ui.value.photoFile)
    }

    // ---- 設定ルート ----

    @Test
    fun 設定は許された状態からだけ開ける() = runTest(dispatcher) {
        val vm = ready(newVm())
        vm.onShutter()
        runCurrent()
        vm.onSettingsLongPress()
        assertFalse(vm.ui.value.settingsOpen)

        capturer.succeed()
        runCurrent()
        vm.onSettingsLongPress()
        assertTrue(vm.ui.value.settingsOpen)
    }

    @Test
    fun Camera_OutboxFull_SetupRequired_PermissionDeniedから設定を開ける() = runTest(dispatcher) {
        val vm = ready(newVm())
        for (setup in listOf(
            { outbox.activeCount.value = 0 },
            { outbox.activeCount.value = 5 },
        )) {
            setup()
            runCurrent()
            vm.onSettingsLongPress()
            assertTrue(vm.ui.value.settingsOpen)
            vm.onSettingsClosed()
            assertFalse(vm.ui.value.settingsOpen)
        }
        settings.required = true
        settings.flow.tryEmit(Settings())
        runCurrent()
        assertEquals(UiState.SetupRequired, vm.state())
        vm.onSettingsLongPress()
        assertTrue(vm.ui.value.settingsOpen)
        settings.required = false
        settings.flow.tryEmit(Settings())
        runCurrent()
        vm.onSettingsClosed()
        permission.requested = true
        permission.granted = false
        vm.onResume()
        assertEquals(UiState.PermissionDenied, vm.state())
        vm.onSettingsLongPress()
        assertTrue(vm.ui.value.settingsOpen)
    }

    @Test
    fun 設定から戻ると同じ写真のPreviewに戻る() = runTest(dispatcher) {
        val vm = ready(newVm())
        toPreview(vm)
        val p = vm.state()
        vm.onSettingsLongPress()
        vm.onSettingsClosed()
        assertEquals(p, vm.state())
        assertTrue(outbox.previewExists)
    }

    @Test
    fun 設定でSetupRequiredになっても写真を保持し直れば同じPreviewに戻る() = runTest(dispatcher) {
        val vm = ready(newVm())
        toPreview(vm)
        val p = vm.state()
        vm.onSettingsLongPress()
        settings.required = true
        settings.flow.tryEmit(Settings())
        runCurrent()
        vm.onSettingsClosed()
        assertEquals(UiState.SetupRequired, vm.state())
        assertTrue(outbox.previewExists)

        vm.onSettingsLongPress()
        settings.required = false
        settings.flow.tryEmit(Settings())
        runCurrent()
        vm.onSettingsClosed()
        assertEquals(p, vm.state())
        assertTrue(outbox.previewExists)
    }

    @Test
    fun 設定から戻るとCameraとOutboxFullを評価し直す() = runTest(dispatcher) {
        val vm = ready(newVm())
        vm.onSettingsLongPress()
        settings.required = true
        settings.flow.tryEmit(Settings())
        runCurrent()
        vm.onSettingsClosed()
        assertEquals(UiState.SetupRequired, vm.state())
    }

    @Test
    fun 設定を開いている間にSetupRequiredが解けてもカメラ権限は閉じるまで要求しない() = runTest(dispatcher) {
        permission.granted = false
        settings.required = true
        val vm = ready(newVm())
        assertEquals(UiState.SetupRequired, vm.state())
        vm.onSettingsLongPress()
        // 設定画面でメール設定を保存した
        settings.required = false
        settings.flow.tryEmit(Settings())
        runCurrent()
        vm.onResume()
        assertFalse(vm.ui.value.cameraPermissionRequest)
        assertEquals(0, permission.requestCount)
        vm.onSettingsClosed()
        assertTrue(vm.ui.value.cameraPermissionRequest)
        assertEquals(1, permission.requestCount)
    }

    // ---- 通知権限 ----

    @Test
    fun 通知権限はCameraを初めて出した時に1回だけ要求する() = runTest(dispatcher) {
        val vm = ready(newVm(withNotif = true))
        assertEquals(UiState.Camera, vm.state())
        runCurrent()
        assertTrue(vm.ui.value.notificationPermissionRequest)
        vm.onNotificationPermissionRequestLaunched()
        assertFalse(vm.ui.value.notificationPermissionRequest)

        outbox.activeCount.value = 5
        runCurrent()
        outbox.activeCount.value = 0
        runCurrent()
        vm.onResume()
        runCurrent()
        assertFalse(vm.ui.value.notificationPermissionRequest)
        assertTrue(notifAsked)
    }

    @Test
    fun カメラ権限が決まるまで通知権限を要求しない() = runTest(dispatcher) {
        permission.granted = false
        val vm = ready(newVm(withNotif = true))
        runCurrent()
        assertFalse(vm.ui.value.notificationPermissionRequest)
        vm.onCameraPermissionRequestLaunched()
        vm.onCameraPermissionResult(false)
        runCurrent()
        assertEquals(UiState.PermissionDenied, vm.state())
        assertFalse(vm.ui.value.notificationPermissionRequest)
        assertFalse(notifAsked)
    }

    // ---- 取り消し猶予・前面送信・送信後(PLAN-3) ----

    /** Preview から送り先を押して CancelWindow まで進める */
    private fun TestScope.toCancelWindow(vm: MainViewModel) {
        toPreview(vm)
        vm.onRecipientTapped("1")
        runCurrent()
    }

    private val transient: MailResult = MailResult.Failure(FailureKind.TRANSIENT, MailCode.NETWORK, null, null, null)

    @Test
    fun 送り先タップで項目を作り予約を外して3秒の猶予に入る() = runTest(dispatcher) {
        val vm = ready(newVm())
        toPreview(vm)
        val p = vm.state() as UiState.Preview
        vm.onRecipientTapped("1")
        runCurrent()
        assertEquals(Triple("1", p.capturedAtMillis, p.file), outbox.lastCreate)
        assertEquals(UiState.CancelWindow("item1", 3), vm.state())
        assertFalse(outbox.previewExists)
        assertEquals(listOf("create", "preview=false"), outbox.events.takeLast(2))
        assertEquals(capturer.file, vm.ui.value.photoFile)

        advanceTimeBy(1000)
        runCurrent()
        assertEquals(UiState.CancelWindow("item1", 2), vm.state())
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(UiState.CancelWindow("item1", 1), vm.state())
        advanceTimeBy(999)
        runCurrent()
        assertEquals(UiState.CancelWindow("item1", 1), vm.state())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(UiState.Sending("item1"), vm.state())
        assertTrue(outbox.events.containsAll(listOf("sendingFg:item1", "send:item1")))
        assertTrue(outbox.events.indexOf("sendingFg:item1") < outbox.events.indexOf("send:item1"))
    }

    @Test
    fun 取り消すは予約を戻してから項目を消し同じPreviewに戻る() = runTest(dispatcher) {
        val vm = ready(newVm())
        toPreview(vm)
        val p = vm.state()
        vm.onRecipientTapped("1")
        runCurrent()
        advanceTimeBy(1500)
        runCurrent()
        val deletes = capturer.deleteCount
        vm.onCancelUndo()
        runCurrent()
        assertEquals(p, vm.state())
        assertTrue(outbox.previewExists)
        assertEquals(listOf("preview=true", "delete:item1"), outbox.events.takeLast(2))
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(p, vm.state())
        assertFalse(outbox.events.any { it.startsWith("send") })
        // cache の写真は残す
        assertEquals(deletes, capturer.deleteCount)
    }

    @Test
    fun 成功で送りましたを2秒出し合図は1回だけで写真を消してCameraに戻る() = runTest(dispatcher) {
        val vm = ready(newVm())
        toCancelWindow(vm)
        advanceTimeBy(3000)
        runCurrent()
        outbox.sendGate.complete(MailResult.Success)
        runCurrent()
        assertEquals(UiState.Sent, vm.state())
        assertTrue("sent:item1" in outbox.events)
        assertEquals(1, feedbackCount)
        // 再開しても鳴らし直さない
        vm.onResume()
        assertEquals(1, feedbackCount)

        val deletes = capturer.deleteCount
        advanceTimeBy(1999)
        runCurrent()
        assertEquals(UiState.Sent, vm.state())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(UiState.Camera, vm.state())
        assertEquals(deletes + 1, capturer.deleteCount)
        assertEquals(null, vm.ui.value.photoFile)
        assertFalse(outbox.previewExists)
        assertEquals(1, feedbackCount)
    }

    @Test
    fun 失敗はあとで送りますを2秒出し合図なし() = runTest(dispatcher) {
        val vm = ready(newVm())
        toCancelWindow(vm)
        advanceTimeBy(3000)
        runCurrent()
        outbox.sendGate.complete(transient)
        runCurrent()
        assertEquals(UiState.QueuedNotice, vm.state())
        assertEquals(listOf("item1" to transient), outbox.queued)
        assertEquals(0, feedbackCount)
        advanceTimeBy(1999)
        runCurrent()
        assertEquals(UiState.QueuedNotice, vm.state())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(UiState.Camera, vm.state())
    }

    @Test
    fun 回線なしもあとで送ります() = runTest(dispatcher) {
        val vm = ready(newVm())
        toCancelWindow(vm)
        advanceTimeBy(3000)
        runCurrent()
        outbox.sendGate.complete(MailResult.Offline)
        runCurrent()
        assertEquals(UiState.QueuedNotice, vm.state())
        assertEquals(MailResult.Offline, outbox.queued.single().second)
    }

    @Test
    fun 前面送信は60秒で打ち切りTIMEOUTとして送信待ちにする() = runTest(dispatcher) {
        val vm = ready(newVm())
        toCancelWindow(vm)
        advanceTimeBy(3000)
        runCurrent()
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(UiState.Sending("item1"), vm.state())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(UiState.QueuedNotice, vm.state())
        val r = outbox.queued.single().second as MailResult.Failure
        assertEquals(FailureKind.TRANSIENT, r.kind)
        assertEquals(MailCode.TIMEOUT, r.code)
    }

    @Test
    fun 送信後に5件ならOutboxFullに戻る() = runTest(dispatcher) {
        val vm = ready(newVm())
        toCancelWindow(vm)
        advanceTimeBy(3000)
        runCurrent()
        outbox.activeCount.value = 5
        outbox.sendGate.complete(transient)
        runCurrent()
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(UiState.OutboxFull, vm.state())
    }

    @Test
    fun createItemの満杯例外はPreviewと写真を残し案内を出す() = runTest(dispatcher) {
        val vm = ready(newVm())
        toPreview(vm)
        val p = vm.state()
        outbox.createError = OutboxFullException()
        vm.onRecipientTapped("1")
        runCurrent()
        assertEquals(p, vm.state())
        assertTrue(vm.ui.value.outboxFullNoticeVisible)
        assertTrue(outbox.previewExists)
        // 件数が減って例外が出なくなれば押せる
        outbox.createError = null
        vm.onRecipientTapped("1")
        runCurrent()
        assertTrue(vm.state() is UiState.CancelWindow)
        assertFalse(vm.ui.value.outboxFullNoticeVisible)
    }

    @Test
    fun その他のcreateItem失敗はPreviewのまま() = runTest(dispatcher) {
        val vm = ready(newVm())
        toPreview(vm)
        val p = vm.state()
        outbox.createError = java.io.IOException("disk")
        vm.onRecipientTapped("1")
        runCurrent()
        assertEquals(p, vm.state())
        assertTrue(outbox.previewExists)
        assertTrue(logs.any { it.startsWith("createItem failed") })
    }

    @Test
    fun 項目作成中の二度押しとやり直すは無視する() = runTest(dispatcher) {
        val vm = ready(newVm())
        toPreview(vm)
        val gate = CompletableDeferred<Unit>()
        outbox.createGate = gate
        vm.onRecipientTapped("1")
        runCurrent()
        vm.onRecipientTapped("1")
        vm.onRetake()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, outbox.created)
        assertEquals(UiState.CancelWindow("item1", 3), vm.state())
    }

    @Test
    fun スリープで猶予の期限を過ぎていたら再開時にすぐ送信に進む() = runTest(dispatcher) {
        val vm = ready(newVm())
        toCancelWindow(vm)
        advanceTimeBy(500)
        runCurrent()
        sleptMs = 5000
        vm.onResume()
        runCurrent()
        assertEquals(UiState.Sending("item1"), vm.state())
    }

    @Test
    fun スリープで2秒画面を過ぎていたら再開時にCameraで合図は鳴らし直さない() = runTest(dispatcher) {
        val vm = ready(newVm())
        toCancelWindow(vm)
        advanceTimeBy(3000)
        runCurrent()
        outbox.sendGate.complete(MailResult.Success)
        runCurrent()
        sleptMs = 10_000
        vm.onResume()
        runCurrent()
        assertEquals(UiState.Camera, vm.state())
        assertEquals(1, feedbackCount)
    }

    @Test
    fun 猶予中と送信中は設定を開けず再開でも中断しない() = runTest(dispatcher) {
        val vm = ready(newVm())
        toCancelWindow(vm)
        vm.onSettingsLongPress()
        assertFalse(vm.ui.value.settingsOpen)
        permission.requested = true
        permission.granted = false
        vm.onResume()
        runCurrent()
        assertTrue(vm.state() is UiState.CancelWindow)
        advanceTimeBy(3000)
        runCurrent()
        vm.onSettingsLongPress()
        vm.onResume()
        assertEquals(UiState.Sending("item1"), vm.state())
        assertFalse(vm.ui.value.settingsOpen)
    }

    // 画面の終了(タスクを払いのけた時)を再現する。onCleared は ViewModelStore 経由で呼ばれる
    private fun clear(vm: MainViewModel) {
        val store = ViewModelStore()
        ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        })[MainViewModel::class.java]
        store.clear()
    }

    @Test
    fun 猶予中に画面が閉じられたら送信待ちにしてすぐ送る() = runTest(dispatcher) {
        val vm = ready(newVm())
        toCancelWindow(vm)
        clear(vm)
        assertEquals(listOf("item1"), outbox.abandoned)
        assertFalse(outbox.previewExists)
    }

    @Test
    fun 送信中に画面が閉じられたら送信待ちにしてすぐ送る() = runTest(dispatcher) {
        val vm = ready(newVm())
        toCancelWindow(vm)
        advanceTimeBy(3000)
        runCurrent()
        assertEquals(UiState.Sending("item1"), vm.state())
        clear(vm)
        assertEquals(listOf("item1"), outbox.abandoned)
    }

    @Test
    fun 送信後やPreviewで閉じられても何もしない() = runTest(dispatcher) {
        val vm = ready(newVm())
        toCancelWindow(vm)
        advanceTimeBy(3000)
        runCurrent()
        outbox.sendGate.complete(MailResult.Success)
        runCurrent()
        assertEquals(UiState.Sent, vm.state())
        clear(vm)

        val vm2 = ready(newVm())
        toPreview(vm2)
        clear(vm2)
        assertTrue(outbox.abandoned.isEmpty())
    }

    @Test
    fun 項目作成中は設定を開けない() = runTest(dispatcher) {
        val vm = ready(newVm())
        toPreview(vm)
        val gate = CompletableDeferred<Unit>()
        outbox.createGate = gate
        vm.onRecipientTapped("1")
        runCurrent()
        vm.onSettingsLongPress()
        assertFalse(vm.ui.value.settingsOpen)
        gate.complete(Unit)
        runCurrent()
        assertEquals(UiState.CancelWindow("item1", 3), vm.state())
        assertFalse(vm.ui.value.settingsOpen)
    }

    // ---- 戻る操作と設定アイコンの対象 ----

    @Test
    fun 戻るを無視する状態と設定を開ける状態の一覧() {
        val f = File("x")
        val blocking = listOf(
            UiState.Loading, UiState.Capturing, UiState.Preview(0, f), UiState.CancelWindow("i", 3),
            UiState.Sending("i"), UiState.Sent, UiState.QueuedNotice,
        )
        val passing = listOf(UiState.Camera, UiState.OutboxFull, UiState.SetupRequired, UiState.PermissionDenied)
        blocking.forEach { assertTrue("$it", it.blocksBack) }
        passing.forEach { assertFalse("$it", it.blocksBack) }

        val settingsOk = passing + UiState.Preview(0, f)
        settingsOk.forEach { assertTrue("$it", it.allowsSettings) }
        (blocking - UiState.Preview(0, f)).forEach { assertFalse("$it", it.allowsSettings) }

        passing.forEach { assertTrue("$it", it.isPrecedenceOwned) }
        assertTrue(UiState.Loading.isPrecedenceOwned)
        (blocking - UiState.Loading).forEach { assertFalse("$it", it.isPrecedenceOwned) }
    }
}
