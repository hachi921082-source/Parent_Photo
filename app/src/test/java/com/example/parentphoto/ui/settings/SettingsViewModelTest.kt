package com.example.parentphoto.ui.settings

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.example.parentphoto.settings.ButtonSize
import com.example.parentphoto.settings.Recipient
import com.example.parentphoto.settings.Settings
import com.example.parentphoto.settings.ValidationError
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

    private fun vm(store: FakeStore): SettingsViewModel {
        lastStore = store
        return SettingsViewModel(store, { resumes++ }, store, outbox, pat, { logs += it }).also { it.onOpened() }
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

    @Test fun `次へは保存済みパスワード欄を飛ばす`() {
        assertEquals(FormField.PASSWORD, SmtpFormLogic.nextField(FormField.SMTP_USER, PasswordMode.NEW))
        assertEquals(FormField.HOST, SmtpFormLogic.nextField(FormField.SMTP_USER, PasswordMode.SAVED))
        assertNull(SmtpFormLogic.nextField(FormField.PORT, PasswordMode.NEW))
    }
}
