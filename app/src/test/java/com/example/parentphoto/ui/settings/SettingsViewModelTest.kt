package com.example.parentphoto.ui.settings

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.example.parentphoto.R
import com.example.parentphoto.mail.FailureKind
import com.example.parentphoto.mail.MailCode
import com.example.parentphoto.mail.MailResult
import com.example.parentphoto.mail.SendLogResult
import com.example.parentphoto.outbox.AttentionReason
import com.example.parentphoto.outbox.ItemState
import com.example.parentphoto.outbox.OutboxItemView
import com.example.parentphoto.outbox.Phase
import com.example.parentphoto.outbox.ResendOutcome
import com.example.parentphoto.outbox.SendLogEntry
import com.example.parentphoto.settings.ButtonSize
import com.example.parentphoto.settings.Recipient
import com.example.parentphoto.settings.Settings
import com.example.parentphoto.settings.ValidationError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.regex.Pattern

/** 設定フォームの状態・検証・保存の流れ(settings-screen PLAN-1 7 の JVM 部分)。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    // JVM上ではandroid.util.Patternsが使えないため、簡易パターンを差し替える
    private val pat: Pattern = Pattern.compile("[^@ ]+@[^@ ]+[.][^@ ]+")

    private class Saved(val sender: String, val user: String, val host: String, val port: Int, val pw: String?)

    private class FakeStore(initial: Settings, var passwordSaved: Boolean) : SettingsScreenStore, RecipientStore {
        var nextId = 1
        var failRecipient = false
        override suspend fun addRecipient(name: String, email: String): Recipient? {
            if (failRecipient) throw java.io.IOException("disk")
            val list = flow.value.recipients
            if (list.size >= 6) return null
            val r = Recipient("id${nextId++}", name, email)
            flow.value = flow.value.copy(recipients = list + r)
            return r
        }
        override suspend fun updateRecipient(id: String, name: String, email: String): Boolean {
            val list = flow.value.recipients
            if (list.none { it.id == id }) return false
            flow.value = flow.value.copy(recipients = list.map { if (it.id == id) it.copy(name = name, email = email) else it })
            return true
        }
        override suspend fun deleteRecipient(id: String) {
            flow.value = flow.value.copy(recipients = flow.value.recipients.filterNot { it.id == id })
        }
        val flow = MutableStateFlow(initial)
        val saves = mutableListOf<Saved>()
        var failSave = false
        override val settings: Flow<Settings> = flow
        override suspend fun isPasswordSaved() = passwordSaved
        override suspend fun saveSmtp(senderName: String, user: String, host: String, port: Int, newPasswordOrNull: String?) {
            if (failSave) throw java.io.IOException("disk")
            saves += Saved(senderName, user, host, port, newPasswordOrNull)
            flow.value = flow.value.copy(senderName = senderName, smtpUser = user, smtpHost = host, smtpPort = port,
                passwordPresent = flow.value.passwordPresent || newPasswordOrNull != null)
            if (newPasswordOrNull != null) passwordSaved = true
        }
        override suspend fun setButtonSize(size: ButtonSize) { flow.value = flow.value.copy(buttonSize = size) }
    }

    private var resumes = 0
    private val logs = mutableListOf<String>()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    // 削除の順番(送り先を消してから要確認へ移す)を確かめる
    private val outboxCalls = mutableListOf<String>()
    private var queuedCount = 0
    private lateinit var lastStore: FakeStore
    private val outbox = object : RecipientOutbox {
        override suspend fun countForRecipient(recipientId: String): Int {
            outboxCalls += "count:$recipientId"
            return queuedCount
        }
        override suspend fun onRecipientDeleted(recipientId: String) {
            val stillThere = lastStore.flow.value.recipients.any { it.id == recipientId }
            outboxCalls += "deleted:$recipientId:stillThere=$stillThere"
        }
    }

    // テスト送信の偽物。gate を完了させるまで送信中のまま待つ
    private val sentTo = mutableListOf<String>()
    private var gate = CompletableDeferred<MailResult>()
    private val sender = TestMailSender { r ->
        sentTo += r.id
        gate.await()
    }

    // 未送信キューの偽物(一覧・ログ・今すぐ再送・削除)
    private class FakeUnsent : UnsentOutbox {
        val itemsFlow = MutableStateFlow<List<OutboxItemView>>(emptyList())
        val logFlow = MutableStateFlow<List<SendLogEntry>>(emptyList())
        var outcome = ResendOutcome.Started
        val resent = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        override val items: Flow<List<OutboxItemView>> = itemsFlow
        override val sendLog: Flow<List<SendLogEntry>> = logFlow
        override suspend fun resendNow(itemId: String): ResendOutcome { resent += itemId; return outcome }
        override suspend fun deleteManual(itemId: String) {
            deleted += itemId
            itemsFlow.value = itemsFlow.value.filterNot { it.id == itemId }
        }
    }
    private val unsent = FakeUnsent()

    private fun vm(store: FakeStore): SettingsViewModel {
        lastStore = store
        return SettingsViewModel(store, { resumes++ }, store, outbox, sender, unsent, pat, { logs += it }).also { it.onOpened() }
    }

    private fun recipients(n: Int) = (1..n).map { Recipient("r$it", "送り先$it", "r$it@example.jp") }

    private val pw16 = "abcdefghijklmnop"

    @Test fun `初期値は保存済みの値、ホストとポートは既定値が入る`() {
        val v = vm(FakeStore(Settings(), passwordSaved = false))
        val f = v.form.value
        assertTrue(f.loaded)
        assertEquals("smtp.gmail.com", f.values.host)
        assertEquals("465", f.values.port)
        assertEquals(PasswordMode.NEW, f.passwordMode)
        assertFalse(f.dirty)
    }

    @Test fun `保存済みパスワードがあれば SAVED`() {
        val v = vm(FakeStore(Settings(smtpUser = "a@b.jp"), passwordSaved = true))
        assertEquals(PasswordMode.SAVED, v.form.value.passwordMode)
    }

    @Test fun `不正な入力でも変更があれば dirty、元に戻すと clean`() {
        val v = vm(FakeStore(Settings(), false))
        v.onPortChange("abc")
        assertTrue(v.form.value.dirty)
        v.onPortChange("465")
        assertFalse(v.form.value.dirty)
    }

    @Test fun `エラーは全部まとめて出し、何も保存せず、最初の欄へ移る`() = runTest {
        val store = FakeStore(Settings(), false)
        val v = vm(store)
        val events = mutableListOf<SettingsEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { v.events.toList(events) }
        v.onSmtpUserChange("not-an-email")
        v.onHostChange("smtp gmail.com")
        v.onPortChange("70000")
        v.onSave()
        val e = v.form.value.errors
        assertEquals(ValidationError.EMAIL_FORMAT, e[FormField.SMTP_USER])
        assertEquals(ValidationError.PASSWORD_LENGTH, e[FormField.PASSWORD])
        assertEquals(ValidationError.HOST_FORMAT, e[FormField.HOST])
        assertEquals(ValidationError.PORT_RANGE, e[FormField.PORT])
        assertTrue(store.saves.isEmpty())
        assertEquals(0, resumes)
        assertEquals(listOf<SettingsEvent>(SettingsEvent.FocusField(FormField.SMTP_USER)), events)
        job.cancel()
    }

    @Test fun `ホスト空とポート0のエラー文言`() {
        val v = vm(FakeStore(Settings(smtpUser = "a@b.jp"), true))
        v.onHostChange("   ")
        v.onPortChange("0")
        v.onSave()
        assertEquals(ValidationError.HOST_EMPTY, v.form.value.errors[FormField.HOST])
        assertEquals(ValidationError.PORT_RANGE, v.form.value.errors[FormField.PORT])
        assertNull(v.form.value.errors[FormField.PASSWORD])
    }

    @Test fun `正しい入力なら保存し、保存しましたを出してから再開を呼ぶ`() = runTest {
        val store = FakeStore(Settings(), false)
        val v = vm(store)
        val events = mutableListOf<SettingsEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { v.events.toList(events) }
        v.onSenderNameChange("  お父\r\nさん ")
        v.onSmtpUserChange(" chichi@example.jp ")
        v.onPasswordChange("abcd efgh\nijkl mnop")
        v.onHostChange(" smtp.example.jp ")
        v.onPortChange(" 0587 ")
        v.onSave()
        val s = store.saves.single()
        assertEquals("お父さん", s.sender)
        assertEquals("chichi@example.jp", s.user)
        assertEquals("smtp.example.jp", s.host)
        assertEquals(587, s.port)
        assertEquals(pw16, s.pw)
        assertEquals(1, resumes)
        assertEquals(listOf<SettingsEvent>(SettingsEvent.Saved), events)
        val f = v.form.value
        assertFalse(f.dirty)
        assertEquals(PasswordMode.SAVED, f.passwordMode)
        assertEquals("", f.password)
        assertEquals("587", f.values.port)
        job.cancel()
    }

    @Test fun `保存済みパスワードを変えなければ null で保存`() {
        val store = FakeStore(Settings(smtpUser = "a@b.jp"), true)
        val v = vm(store)
        v.onSenderNameChange("母")
        v.onSave()
        assertNull(store.saves.single().pw)
    }

    @Test fun `変更で dirty、空のまま保存はエラー、キャンセルで元に戻り clean`() {
        val store = FakeStore(Settings(smtpUser = "a@b.jp"), true)
        val v = vm(store)
        v.onChangePassword()
        assertEquals(PasswordMode.CHANGING, v.form.value.passwordMode)
        assertTrue(v.form.value.dirty)
        v.onSave()
        assertEquals(ValidationError.PASSWORD_LENGTH, v.form.value.errors[FormField.PASSWORD])
        assertTrue(store.saves.isEmpty())
        v.onCancelPasswordChange()
        assertEquals(PasswordMode.SAVED, v.form.value.passwordMode)
        assertFalse(v.form.value.dirty)
        assertNull(v.form.value.errors[FormField.PASSWORD])
    }

    @Test fun `変更して16文字なら新しいパスワードを保存`() {
        val store = FakeStore(Settings(smtpUser = "a@b.jp"), true)
        val v = vm(store)
        v.onChangePassword()
        v.onPasswordChange(" $pw16 ")
        assertEquals(pw16, v.form.value.password)
        v.onSave()
        assertEquals(pw16, store.saves.single().pw)
    }

    @Test fun `文字数の上限を超えた分は入らない`() {
        val v = vm(FakeStore(Settings(), false))
        v.onSenderNameChange("あ".repeat(25))
        assertEquals(20, v.form.value.values.senderName.length)
        v.onSmtpUserChange("a".repeat(300))
        assertEquals(254, v.form.value.values.smtpUser.length)
        v.onHostChange("h".repeat(300))
        assertEquals(253, v.form.value.values.host.length)
    }

    @Test fun `欄を直すとその欄のエラーだけ消える`() {
        val v = vm(FakeStore(Settings(), false))
        v.onSmtpUserChange("x")
        v.onSave()
        v.onSmtpUserChange("xy")
        assertNull(v.form.value.errors[FormField.SMTP_USER])
        assertEquals(ValidationError.PASSWORD_LENGTH, v.form.value.errors[FormField.PASSWORD])
    }

    @Test fun `保存に失敗したら入力を残し、保存できませんでしたを出し、再開を呼ばない`() = runTest {
        val store = FakeStore(Settings(smtpUser = "a@b.jp"), true).apply { failSave = true }
        val v = vm(store)
        val events = mutableListOf<SettingsEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { v.events.toList(events) }
        v.onSenderNameChange("父")
        v.onSave()
        assertTrue(v.form.value.dirty)
        assertEquals("父", v.form.value.values.senderName)
        assertEquals(0, resumes)
        assertEquals(listOf<SettingsEvent>(SettingsEvent.SaveFailed), events)
        assertTrue(logs.single().startsWith("saveSmtp failed"))
        // 直れば同じ入力のまま保存し直せる
        store.failSave = false
        v.onSave()
        assertEquals("父", store.saves.single().sender)
        job.cancel()
    }

    @Test fun `閉じると入力を捨て、開き直すと保存済みの値を読む`() {
        val store = FakeStore(Settings(senderName = "父"), false)
        val v = vm(store)
        v.onSenderNameChange("未保存")
        v.onClosed()
        assertFalse(v.form.value.loaded)
        v.onOpened()
        assertEquals("父", v.form.value.values.senderName)
        assertFalse(v.form.value.dirty)
    }

    @Test fun `開いたままの再呼び出しでは入力を保つ`() {
        val v = vm(FakeStore(Settings(), false))
        v.onSenderNameChange("未保存")
        v.onOpened()
        assertEquals("未保存", v.form.value.values.senderName)
    }

    @Test fun `ボタンの大きさは即時保存されフォームを dirty にしない`() = runTest {
        val store = FakeStore(Settings(), false)
        val v = vm(store)
        v.onButtonSizeSelected(ButtonSize.XLARGE)
        assertEquals(ButtonSize.XLARGE, store.flow.first().buttonSize)
        assertEquals(ButtonSize.XLARGE, v.buttonSize.value)
        assertFalse(v.form.value.dirty)
    }

    @Test fun `キーボードとIMEアクション`() {
        val k = SmtpFormLogic::keyboardOptions
        assertEquals(KeyboardType.Text, k(FormField.SENDER_NAME).keyboardType)
        assertEquals(KeyboardType.Email, k(FormField.SMTP_USER).keyboardType)
        assertEquals(KeyboardType.Password, k(FormField.PASSWORD).keyboardType)
        assertEquals(KeyboardType.Text, k(FormField.HOST).keyboardType)
        assertEquals(KeyboardType.Number, k(FormField.PORT).keyboardType)
        FormField.entries.filter { it != FormField.PORT }.forEach { assertEquals(ImeAction.Next, k(it).imeAction) }
        assertEquals(ImeAction.Done, k(FormField.PORT).imeAction)
    }

    // ---- 送り先(PLAN-2) ----

    @Test fun `追加は6件まで、6件で追加ダイアログを開かない`() {
        val store = FakeStore(Settings(), false)
        val v = vm(store)
        repeat(6) { i ->
            v.onAddRecipient()
            assertNull(v.recipientDialog.value!!.editId)
            v.onRecipientNameChange("送り先$i")
            v.onRecipientEmailChange("r$i@example.jp")
            v.onRecipientDialogSave()
            assertNull(v.recipientDialog.value)
        }
        assertEquals(6, v.recipients.value!!.size)
        v.onAddRecipient()
        assertNull(v.recipientDialog.value)
    }

    @Test fun `ダイアログの検証エラーは開いたまま、保存しない、最初の欄へ移る`() = runTest {
        val store = FakeStore(Settings(), false)
        val v = vm(store)
        val events = mutableListOf<SettingsEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { v.events.toList(events) }
        v.onAddRecipient()
        v.onRecipientNameChange("   ")
        v.onRecipientEmailChange("bad")
        v.onRecipientDialogSave()
        val d = v.recipientDialog.value!!
        assertEquals(ValidationError.NAME_EMPTY, d.errors[RecipientField.NAME])
        assertEquals(ValidationError.EMAIL_FORMAT, d.errors[RecipientField.EMAIL])
        assertTrue(store.flow.value.recipients.isEmpty())
        assertEquals(listOf<SettingsEvent>(SettingsEvent.FocusRecipientField(RecipientField.NAME)), events)
        // 欄を直すとその欄のエラーだけ消える
        v.onRecipientNameChange("母")
        assertNull(v.recipientDialog.value!!.errors[RecipientField.NAME])
        assertEquals(ValidationError.EMAIL_FORMAT, v.recipientDialog.value!!.errors[RecipientField.EMAIL])
        job.cancel()
    }

    @Test fun `表示名は10文字まで、11文字はエラー、前後の空白は数えない`() {
        val store = FakeStore(Settings(), false)
        val v = vm(store)
        v.onAddRecipient()
        v.onRecipientNameChange("あいうえおかきくけこさ")
        v.onRecipientEmailChange("a@example.jp")
        v.onRecipientDialogSave()
        assertEquals(ValidationError.NAME_TOO_LONG, v.recipientDialog.value!!.errors[RecipientField.NAME])
        v.onRecipientNameChange("  あいうえおかきくけこ  ")
        v.onRecipientDialogSave()
        assertNull(v.recipientDialog.value)
        assertEquals("あいうえおかきくけこ", store.flow.value.recipients.single().name)
    }

    @Test fun `絵文字はUTF-16の単位で数える、5個まで可で6個はエラー`() {
        val store = FakeStore(Settings(), false)
        val v = vm(store)
        v.onAddRecipient()
        v.onRecipientEmailChange("a@example.jp")
        v.onRecipientNameChange("👨👨👨👨👨👨")
        v.onRecipientDialogSave()
        assertEquals(ValidationError.NAME_TOO_LONG, v.recipientDialog.value!!.errors[RecipientField.NAME])
        v.onRecipientNameChange("👨👨👨👨👨")
        v.onRecipientDialogSave()
        assertEquals("👨👨👨👨👨", store.flow.value.recipients.single().name)
    }

    @Test fun `アドレスは254文字で止まり、前後の空白を除いて保存、重複も許す`() {
        val store = FakeStore(Settings(recipients = recipients(1)), false)
        val v = vm(store)
        v.onAddRecipient()
        v.onRecipientEmailChange("a".repeat(300))
        assertEquals(254, v.recipientDialog.value!!.email.length)
        v.onRecipientNameChange(" 父 ")
        v.onRecipientEmailChange(" r1@example.jp ")
        v.onRecipientDialogSave()
        assertEquals(Recipient("id1", "父", "r1@example.jp"), store.flow.value.recipients[1])
    }

    @Test fun `編集は位置とidを保つ`() {
        val store = FakeStore(Settings(recipients = recipients(3)), false)
        val v = vm(store)
        v.onEditRecipient(store.flow.value.recipients[1])
        val d = v.recipientDialog.value!!
        assertEquals("r2", d.editId)
        assertEquals("送り先2", d.name)
        v.onRecipientNameChange("お母さん")
        v.onRecipientDialogSave()
        assertEquals(listOf("r1", "r2", "r3"), store.flow.value.recipients.map { it.id })
        assertEquals("お母さん", store.flow.value.recipients[1].name)
    }

    @Test fun `ダイアログを閉じると保存しない`() {
        val store = FakeStore(Settings(recipients = recipients(1)), false)
        val v = vm(store)
        v.onEditRecipient(store.flow.value.recipients[0])
        v.onRecipientNameChange("変えた")
        v.onRecipientDialogDismiss()
        assertNull(v.recipientDialog.value)
        assertEquals("送り先1", store.flow.value.recipients[0].name)
    }

    @Test fun `送り先の保存失敗は入力を残して保存できませんでした`() = runTest {
        val store = FakeStore(Settings(), false).apply { failRecipient = true }
        val v = vm(store)
        val events = mutableListOf<SettingsEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { v.events.toList(events) }
        v.onAddRecipient()
        v.onRecipientNameChange("父")
        v.onRecipientEmailChange("a@example.jp")
        v.onRecipientDialogSave()
        assertEquals("父", v.recipientDialog.value!!.name)
        assertEquals(listOf<SettingsEvent>(SettingsEvent.SaveFailed), events)
        job.cancel()
    }

    @Test fun `削除は枚数を数えて確認し、送り先を消してから要確認へ移す`() {
        val store = FakeStore(Settings(recipients = recipients(2)), false)
        val v = vm(store)
        queuedCount = 3
        v.onDeleteRecipient(store.flow.value.recipients[0])
        assertEquals(DeleteConfirmState("r1", 3), v.deleteConfirm.value)
        v.onDeleteConfirmed()
        assertNull(v.deleteConfirm.value)
        assertEquals(listOf("r2"), store.flow.value.recipients.map { it.id })
        assertEquals(listOf("count:r1", "deleted:r1:stillThere=false"), outboxCalls)
    }

    @Test fun `送り先の削除に失敗したら保存できませんでした、閉じた後なら出さない`() = runTest {
        val store = FakeStore(Settings(recipients = recipients(2)), false)
        val failing = object : RecipientStore by store {
            override suspend fun deleteRecipient(id: String) { throw java.io.IOException("disk") }
        }
        val events = mutableListOf<SettingsEvent>()
        val v = SettingsViewModel(store, { resumes++ }, failing, outbox, sender, unsent, pat, { logs += it }).also { it.onOpened() }
        lastStore = store
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { v.events.toList(events) }
        v.onDeleteRecipient(store.flow.value.recipients[0])
        v.onDeleteConfirmed()
        assertEquals(listOf<SettingsEvent>(SettingsEvent.SaveFailed), events)
        assertEquals(2, store.flow.value.recipients.size)
        assertTrue(outboxCalls.none { it.startsWith("deleted") })
        // 削除の最中に設定が閉じられたら、失敗の合図は次の回に持ち越さない
        events.clear()
        val slow = CompletableDeferred<Unit>()
        val failingLater = object : RecipientStore by store {
            override suspend fun deleteRecipient(id: String) { slow.await(); throw java.io.IOException("disk") }
        }
        val v2 = SettingsViewModel(store, { resumes++ }, failingLater, outbox, sender, unsent, pat, { logs += it }).also { it.onOpened() }
        val job2 = launch(UnconfinedTestDispatcher(testScheduler)) { v2.events.toList(events) }
        v2.onDeleteRecipient(store.flow.value.recipients[0])
        v2.onDeleteConfirmed()
        v2.onClosed()
        slow.complete(Unit)
        assertTrue(events.isEmpty())
        job.cancel()
        job2.cancel()
    }

    @Test fun `削除のキャンセルは何も消さない`() {
        val store = FakeStore(Settings(recipients = recipients(1)), false)
        val v = vm(store)
        v.onDeleteRecipient(store.flow.value.recipients[0])
        v.onDeleteDismiss()
        assertNull(v.deleteConfirm.value)
        assertEquals(1, store.flow.value.recipients.size)
        assertEquals(listOf("count:r1"), outboxCalls)
    }

    @Test fun `送り先の変更はフォームを dirty にせず、閉じるとダイアログも閉じる`() {
        val store = FakeStore(Settings(recipients = recipients(1)), false)
        val v = vm(store)
        v.onAddRecipient()
        v.onRecipientNameChange("父")
        assertFalse(v.form.value.dirty)
        v.onClosed()
        assertNull(v.recipientDialog.value)
    }

    @Test fun `送り先ダイアログのキーボード`() {
        assertEquals(KeyboardType.Text, RecipientFormLogic.keyboardOptions(RecipientField.NAME).keyboardType)
        assertEquals(ImeAction.Next, RecipientFormLogic.keyboardOptions(RecipientField.NAME).imeAction)
        assertEquals(KeyboardType.Email, RecipientFormLogic.keyboardOptions(RecipientField.EMAIL).keyboardType)
        assertEquals(ImeAction.Done, RecipientFormLogic.keyboardOptions(RecipientField.EMAIL).imeAction)
    }

    // ---- テスト送信(PLAN-3) ----

    private fun validStore(n: Int = 2) = FakeStore(Settings(smtpUser = "a@b.jp", recipients = recipients(n)), true)

    @Test fun `案内の優先順位、重なったときは上が勝つ`() {
        assertEquals(R.string.hint_no_recipients, TestSendLogic.hint(0, savedSetupValid = false, formDirty = true))
        assertEquals(R.string.hint_no_recipients, TestSendLogic.hint(0, savedSetupValid = true, formDirty = true))
        assertEquals(R.string.hint_save_mail_first, TestSendLogic.hint(1, savedSetupValid = false, formDirty = true))
        assertEquals(R.string.hint_save_first, TestSendLogic.hint(1, savedSetupValid = true, formDirty = true))
        assertNull(TestSendLogic.hint(1, savedSetupValid = true, formDirty = false))
    }

    @Test fun `読み込み前は案内を出さずテスト送信も押せない`() {
        assertNull(TestSendLogic.hint(1, null, false))
        assertNull(TestSendLogic.hint(1, null, true))
        // 送り先なしの案内は読み込みに左右されない
        assertEquals(R.string.hint_no_recipients, TestSendLogic.hint(0, null, false))
        assertFalse(TestSendLogic.canSend(1, null, false))
        assertFalse(TestSendLogic.canSend(0, true, false))
        assertFalse(TestSendLogic.canSend(1, false, false))
        assertFalse(TestSendLogic.canSend(1, true, true))
        assertTrue(TestSendLogic.canSend(1, true, false))
    }

    @Test fun `読み込み前の ViewModel は null、押しても送らない`() {
        val store = FakeStore(Settings(smtpUser = "a@b.jp", recipients = recipients(1)), true)
        // 設定の最初の発行を保留して「読み込み前」を作る
        val gated = object : SettingsScreenStore by store {
            override val settings: Flow<Settings> = kotlinx.coroutines.flow.flow {
                CompletableDeferred<Unit>().await()
                emit(store.flow.value)
            }
        }
        val v = SettingsViewModel(gated, { resumes++ }, store, outbox, sender, unsent, pat, { logs += it })
        assertNull(v.savedSetupValid.value)
        v.onTestSend()
        assertTrue(sentTo.isEmpty())
        assertEquals(TestSendPhase.Idle, v.testSend.value)
    }

    @Test fun `保存済み設定の判定はパスワードとアドレスで変わる`() {
        assertEquals(false, vm(FakeStore(Settings(smtpUser = "a@b.jp"), false)).savedSetupValid.value)
        assertEquals(false, vm(FakeStore(Settings(smtpUser = "bad"), true)).savedSetupValid.value)
        assertEquals(true, vm(FakeStore(Settings(smtpUser = "a@b.jp"), true)).savedSetupValid.value)
    }

    @Test fun `保存すると保存済み設定が有効になる`() {
        val store = FakeStore(Settings(recipients = recipients(1)), false)
        val v = vm(store)
        assertEquals(false, v.savedSetupValid.value)
        v.onSmtpUserChange("a@b.jp")
        v.onPasswordChange(pw16)
        v.onSave()
        assertEquals(true, v.savedSetupValid.value)
    }

    @Test fun `先頭が選ばれ、選んだ送り先が消えたら残りの先頭になる`() {
        val store = validStore(3)
        val v = vm(store)
        assertEquals("r1", v.testRecipient.value!!.id)
        v.onTestRecipientSelected(store.flow.value.recipients[2])
        assertEquals("r3", v.testRecipient.value!!.id)
        v.onDeleteRecipient(store.flow.value.recipients[2])
        v.onDeleteConfirmed()
        assertEquals("r1", v.testRecipient.value!!.id)
        v.onDeleteRecipient(store.flow.value.recipients[0])
        v.onDeleteConfirmed()
        assertEquals("r2", v.testRecipient.value!!.id)
    }

    @Test fun `成功で送信できました、再開を1回呼び、OKで閉じる`() {
        val store = validStore()
        val v = vm(store)
        v.onTestRecipientSelected(store.flow.value.recipients[1])
        v.onTestSend()
        assertEquals(TestSendPhase.Sending, v.testSend.value)
        // 送信中はもう一度押しても送らない
        v.onTestSend()
        assertEquals(listOf("r2"), sentTo)
        gate.complete(MailResult.Success)
        assertEquals(TestSendPhase.Done(TestSendResult(R.string.test_result_ok)), v.testSend.value)
        assertEquals(1, resumes)
        v.onTestResultOk()
        assertEquals(TestSendPhase.Idle, v.testSend.value)
    }

    @Test fun `失敗では再開を呼ばない`() {
        val v = vm(validStore())
        v.onTestSend()
        gate.complete(MailResult.Failure(FailureKind.AUTH, MailCode.AUTH, null, null, 535))
        assertEquals(TestSendPhase.Done(TestSendResult(R.string.test_result_auth)), v.testSend.value)
        assertEquals(0, resumes)
    }

    @Test fun `案内が出ているときは送らない`() {
        val noRecipients = vm(FakeStore(Settings(smtpUser = "a@b.jp"), true))
        noRecipients.onTestSend()
        val invalidSaved = vm(FakeStore(Settings(recipients = recipients(1)), false))
        invalidSaved.onTestSend()
        val dirty = vm(validStore())
        dirty.onSenderNameChange("父")
        dirty.onTestSend()
        assertTrue(sentTo.isEmpty())
        assertEquals(TestSendPhase.Idle, dirty.testSend.value)
    }

    @Test fun `結果の文言の対応`() {
        fun f(code: MailCode) = MailResult.Failure(FailureKind.TRANSIENT, code, null, null, null)
        assertEquals(TestSendResult(R.string.test_result_ok), TestSendLogic.resultOf(MailResult.Success))
        assertEquals(TestSendResult(R.string.test_result_auth), TestSendLogic.resultOf(f(MailCode.AUTH)))
        assertEquals(TestSendResult(R.string.test_result_network), TestSendLogic.resultOf(f(MailCode.NETWORK)))
        assertEquals(TestSendResult(R.string.test_result_network), TestSendLogic.resultOf(f(MailCode.TIMEOUT)))
        assertEquals(TestSendResult(R.string.test_result_rejected), TestSendLogic.resultOf(f(MailCode.REJECTED)))
        assertEquals(TestSendResult(R.string.test_result_other, "OTHER"), TestSendLogic.resultOf(f(MailCode.OTHER)))
    }

    @Test fun `閉じると選択と結果ダイアログを戻す`() {
        val store = validStore()
        val v = vm(store)
        v.onTestRecipientSelected(store.flow.value.recipients[1])
        v.onTestSend()
        gate.complete(MailResult.Success)
        v.onClosed()
        assertEquals(TestSendPhase.Idle, v.testSend.value)
        assertEquals("r1", v.testRecipient.value!!.id)
    }

    // ---- 未送信一覧・送信ログ(PLAN-4) ----

    private fun item(id: String, state: ItemState, phase: Phase = Phase.QUEUED, reason: AttentionReason? = null) =
        OutboxItemView(id, "r1", "送り先1", 1_000L, state, phase, reason, java.io.File("x/$id.jpg"))

    @Test fun `群分けは順番を保つ`() {
        val g = UnsentLogic.group(
            listOf(item("w1", ItemState.WAITING), item("w2", ItemState.WAITING), item("a1", ItemState.ATTENTION), item("a2", ItemState.ATTENTION)),
        )
        assertEquals(listOf("w1", "w2"), g.waiting.map { it.id })
        assertEquals(listOf("a1", "a2"), g.attention.map { it.id })
        assertTrue(UnsentLogic.group(emptyList()).isEmpty)
    }

    @Test fun `ボタンの可否は段階と理由で決まる`() {
        assertEquals(RowButtons(true, true), UnsentLogic.buttons(item("1", ItemState.WAITING)))
        listOf(Phase.CANCEL_WINDOW, Phase.SENDING_FG, Phase.SENDING_BG).forEach {
            assertEquals(RowButtons(false, false), UnsentLogic.buttons(item("1", ItemState.WAITING, it)))
        }
        listOf(AttentionReason.AUTH, AttentionReason.PERMANENT, AttentionReason.RETRY_EXPIRED).forEach {
            assertEquals(RowButtons(true, true), UnsentLogic.buttons(item("1", ItemState.ATTENTION, reason = it)))
        }
        assertEquals(
            RowButtons(false, true),
            UnsentLogic.buttons(item("1", ItemState.ATTENTION, reason = AttentionReason.RECIPIENT_DELETED)),
        )
    }

    @Test fun `ログの文言とコードは失敗とテスト失敗だけ`() {
        val expected = mapOf(
            SendLogResult.SENT to R.string.log_sent,
            SendLogResult.FAILED to R.string.log_failed,
            SendLogResult.QUEUED_OFFLINE to R.string.log_queued_offline,
            SendLogResult.DELETED_MANUAL to R.string.log_deleted_manual,
            SendLogResult.DELETED_OVERFLOW to R.string.log_deleted_overflow,
            SendLogResult.RESEND_REQUESTED to R.string.log_resend_requested,
            SendLogResult.TEST_OK to R.string.log_test_ok,
            SendLogResult.TEST_FAILED to R.string.log_test_failed,
        )
        assertEquals(SendLogResult.entries.toSet(), expected.keys)
        expected.forEach { (r, res) -> assertEquals(res, UnsentLogic.resultLabel(r)) }
        SendLogResult.entries.forEach { r ->
            val shown = UnsentLogic.codeShown(SendLogEntry(1, 0, "x", r, "NETWORK"))
            val wants = r == SendLogResult.FAILED || r == SendLogResult.TEST_FAILED
            assertEquals(if (wants) "NETWORK" else "", shown)
        }
    }

    @Test fun `今すぐ再送は枠待ちのときだけ順番待ちの合図`() = runTest {
        val v = vm(validStore())
        val events = mutableListOf<Unit>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { v.unsentWaitingEvents.toList(events) }
        unsent.outcome = ResendOutcome.Started
        v.onResendNow("a1")
        unsent.outcome = ResendOutcome.NotAllowed
        v.onResendNow("a1")
        assertTrue(events.isEmpty())
        unsent.outcome = ResendOutcome.Waiting
        v.onResendNow("a1")
        assertEquals(1, events.size)
        assertEquals(listOf("a1", "a1", "a1"), unsent.resent)
        job.cancel()
    }

    @Test fun `未送信の削除は確認してから、キャンセルでは消さない`() {
        val v = vm(validStore())
        v.onDeleteUnsent("w1")
        assertEquals("w1", v.unsentDeleteId.value)
        v.onDeleteUnsentDismiss()
        assertNull(v.unsentDeleteId.value)
        assertTrue(unsent.deleted.isEmpty())
        v.onDeleteUnsent("w1")
        v.onDeleteUnsentConfirmed()
        assertEquals(listOf("w1"), unsent.deleted)
        assertNull(v.unsentDeleteId.value)
    }

    @Test fun `一覧とログは窓口の流れを映す`() = runTest {
        val v = vm(validStore())
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { v.unsent.collect {} }
        val job2 = launch(UnconfinedTestDispatcher(testScheduler)) { v.sendLog.collect {} }
        unsent.itemsFlow.value = listOf(item("w1", ItemState.WAITING), item("a1", ItemState.ATTENTION))
        unsent.logFlow.value = listOf(SendLogEntry(2, 0, "x", SendLogResult.SENT, null))
        assertEquals(listOf("w1"), v.unsent.value!!.waiting.map { it.id })
        assertEquals(1, v.sendLog.value!!.size)
        job.cancel()
        job2.cancel()
    }

    @Test fun `次へは保存済みパスワード欄を飛ばす`() {
        assertEquals(FormField.PASSWORD, SmtpFormLogic.nextField(FormField.SMTP_USER, PasswordMode.NEW))
        assertEquals(FormField.HOST, SmtpFormLogic.nextField(FormField.SMTP_USER, PasswordMode.SAVED))
        assertNull(SmtpFormLogic.nextField(FormField.PORT, PasswordMode.NEW))
    }
}
