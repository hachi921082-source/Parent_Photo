package com.example.parentphoto.ui.settings

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.example.parentphoto.settings.ButtonSize
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

    private class FakeStore(initial: Settings, var passwordSaved: Boolean) : SettingsScreenStore {
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

    private fun vm(store: FakeStore) =
        SettingsViewModel(store, { resumes++ }, pat, { logs += it }).also { it.onOpened() }

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

    @Test fun `次へは保存済みパスワード欄を飛ばす`() {
        assertEquals(FormField.PASSWORD, SmtpFormLogic.nextField(FormField.SMTP_USER, PasswordMode.NEW))
        assertEquals(FormField.HOST, SmtpFormLogic.nextField(FormField.SMTP_USER, PasswordMode.SAVED))
        assertNull(SmtpFormLogic.nextField(FormField.PORT, PasswordMode.NEW))
    }
}
