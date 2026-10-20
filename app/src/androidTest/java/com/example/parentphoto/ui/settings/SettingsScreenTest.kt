package com.example.parentphoto.ui.settings

import android.util.Patterns
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.height
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.parentphoto.settings.ButtonSize
import com.example.parentphoto.settings.Recipient
import com.example.parentphoto.settings.Settings
import com.example.parentphoto.ui.theme.FamilyPhotoTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 設定メイン画面の表示と操作(settings-screen PLAN-1 7 の Compose 部分)。
 * 保存先は偽物。本物の DataStore・未送信キューは使わない。
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private class FakeStore(initial: Settings, var passwordSaved: Boolean) : SettingsScreenStore, RecipientStore {
        var nextId = 1
        override suspend fun addRecipient(name: String, email: String): Recipient? {
            val list = flow.value.recipients
            if (list.size >= 6) return null
            return Recipient("id${nextId++}", name, email).also { flow.value = flow.value.copy(recipients = list + it) }
        }
        override suspend fun updateRecipient(id: String, name: String, email: String): Boolean {
            flow.value = flow.value.copy(
                recipients = flow.value.recipients.map { if (it.id == id) it.copy(name = name, email = email) else it },
            )
            return true
        }
        override suspend fun deleteRecipient(id: String) {
            flow.value = flow.value.copy(recipients = flow.value.recipients.filterNot { it.id == id })
        }
        val flow = MutableStateFlow(initial)
        val saves = mutableListOf<Pair<Settings, String?>>()
        var failSave = false
        override val settings: Flow<Settings> = flow
        override suspend fun isPasswordSaved() = passwordSaved
        override suspend fun saveSmtp(senderName: String, user: String, host: String, port: Int, newPasswordOrNull: String?) {
            if (failSave) throw java.io.IOException("disk")
            val s = flow.value.copy(senderName = senderName, smtpUser = user, smtpHost = host, smtpPort = port)
            saves += s to newPasswordOrNull
            flow.value = s
            if (newPasswordOrNull != null) passwordSaved = true
        }
        override suspend fun setButtonSize(size: ButtonSize) { flow.value = flow.value.copy(buttonSize = size) }
    }

    private var resumes = 0
    private var closes = 0
    private val vmStore = ViewModelStore()
    private lateinit var store: FakeStore
    private lateinit var vm: SettingsViewModel
    private var fontScale by mutableFloatStateOf(1f)

    private companion object {
        const val DIRTY = "保存していない変更があります。破棄しますか？"
    }

    @After fun tearDown() = vmStore.clear()

    private val pw16 = "abcdefghijklmnop"

    // 未送信キューの偽物。削除確認の枚数と、宛先削除の呼び出しを記録する
    private var queuedCount = 0
    private val deletedCalls = mutableListOf<String>()
    private val outbox = object : RecipientOutbox {
        override suspend fun countForRecipient(recipientId: String) = queuedCount
        override suspend fun onRecipientDeleted(recipientId: String) { deletedCalls += recipientId }
    }

    private fun recipients(n: Int) = (1..n).map { Recipient("r$it", "送り先$it", "r$it@example.jp") }

    private fun show(settings: Settings = Settings(), passwordSaved: Boolean = false) {
        store = FakeStore(settings, passwordSaved)
        // 終了時に viewModelScope を止めるため ViewModelStore に入れる
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(store, { resumes++ }, store, outbox, Patterns.EMAIL_ADDRESS, {}) as T
        }
        vm = ViewModelProvider(vmStore, factory)[SettingsViewModel::class.java]
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                FamilyPhotoTheme { SettingsNavHost(viewModel = vm, onClose = { closes++ }) }
            }
        }
        rule.waitForIdle()
    }

    private fun field(f: FormField) = rule.onNodeWithTag(SettingsTags.field(f))
    private fun save() = rule.onNodeWithTag(SettingsTags.SAVE)
    private fun text(id: Int) = rule.activity.getString(id)
    private fun systemBack() {
        rule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
    }

    private fun type(f: FormField, value: String) {
        field(f).performScrollTo()
        field(f).performTextClearance()
        field(f).performTextInput(value)
    }

    @Test fun showsDefaultsAndSaveFollowsDirty() {
        show()
        rule.onNodeWithText("設定").assertIsDisplayed()
        rule.onNodeWithText("smtp.gmail.com").assertIsDisplayed()
        rule.onNodeWithText("465").assertIsDisplayed()
        save().assertIsNotEnabled()
        // 不正な値でも変更があれば押せる
        type(FormField.PORT, "abc")
        save().assertIsEnabled()
        type(FormField.PORT, "465")
        save().assertIsNotEnabled()
    }

    @Test fun invalidSaveShowsAllErrorsSavesNothingAndFocusesFirst() {
        show()
        fontScale = 2f
        type(FormField.SMTP_USER, "wrong")
        type(FormField.HOST, "smtp gmail.com")
        type(FormField.PORT, "0")
        save().performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(text(com.example.parentphoto.R.string.err_email_format)).assertExists()
        rule.onNodeWithText(text(com.example.parentphoto.R.string.err_password_length)).assertExists()
        rule.onNodeWithText(text(com.example.parentphoto.R.string.err_host_format)).assertExists()
        rule.onNodeWithText(text(com.example.parentphoto.R.string.err_port_range)).assertExists()
        // 画面の下で押しても、最初のエラー欄(送信元アドレス)まで戻ってフォーカスする
        field(FormField.SMTP_USER).assertIsFocused().assertIsDisplayed()
        assertTrue(store.saves.isEmpty())
        assertEquals(0, resumes)
    }

    @Test fun hostEmptyMessage() {
        show(Settings(smtpUser = "chichi@example.jp"), passwordSaved = true)
        type(FormField.HOST, "")
        save().performScrollTo().performClick()
        rule.onNodeWithText(text(com.example.parentphoto.R.string.err_host_empty)).assertExists()
        field(FormField.HOST).assertIsFocused()
    }

    @Test fun validSaveStoresShowsSnackbarAndResumes() {
        show()
        type(FormField.SENDER_NAME, "お父さん")
        type(FormField.SMTP_USER, "chichi@example.jp")
        type(FormField.PASSWORD, "abcd efgh ijkl mnop")
        save().performScrollTo().performClick()
        rule.waitUntil(5_000) { store.saves.isNotEmpty() }
        rule.onNodeWithText("保存しました").assertIsDisplayed()
        val (s, pw) = store.saves.single()
        assertEquals("お父さん", s.senderName)
        assertEquals("chichi@example.jp", s.smtpUser)
        assertEquals(pw16, pw)
        rule.waitUntil(5_000) { resumes == 1 }
        // 保存後は clean、パスワードは保存済み表示
        save().assertIsNotEnabled()
        rule.onNodeWithText(text(com.example.parentphoto.R.string.password_saved_mask)).assertExists()
    }

    @Test fun saveFailureKeepsInputAndShowsSnackbar() {
        show(Settings(smtpUser = "chichi@example.jp"), passwordSaved = true)
        store.failSave = true
        type(FormField.SENDER_NAME, "お父さん")
        save().performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("保存できませんでした").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("保存できませんでした").assertIsDisplayed()
        rule.onNodeWithText("お父さん").assertExists()
        save().assertIsEnabled()
        assertTrue(store.saves.isEmpty())
        assertEquals(0, resumes)
    }

    @Test fun newPasswordToggleAndWhitespace() {
        show()
        type(FormField.PASSWORD, " abcd\nefgh ")
        assertEquals("abcdefgh", vm.form.value.password)
        rule.onNodeWithContentDescription("パスワードを表示").performClick()
        rule.onNodeWithContentDescription("パスワードを隠す").assertExists()
        rule.onNodeWithText("abcdefgh").assertExists()
    }

    @Test fun savedPasswordChangeAndCancel() {
        show(Settings(smtpUser = "chichi@example.jp"), passwordSaved = true)
        field(FormField.PASSWORD).assertIsNotEnabled()
        rule.onNodeWithText(text(com.example.parentphoto.R.string.password_saved_mask)).assertExists()
        save().assertIsNotEnabled()
        rule.onNodeWithText("変更").performClick()
        field(FormField.PASSWORD).assertIsEnabled()
        save().assertIsEnabled()
        // 変更して空のまま保存はエラー
        save().performScrollTo().performClick()
        rule.onNodeWithText(text(com.example.parentphoto.R.string.err_password_length)).assertExists()
        field(FormField.PASSWORD).assertIsFocused()
        assertTrue(store.saves.isEmpty())
        rule.onNodeWithText("キャンセル").performScrollTo().performClick()
        rule.onNodeWithText("変更").assertExists()
        save().assertIsNotEnabled()
        assertNull(vm.form.value.errors[FormField.PASSWORD])
    }

    @Test fun maxLengthsBlockFurtherInput() {
        show()
        type(FormField.SENDER_NAME, "あいうえおかきくけこさしすせそたちつてとなにぬねの")
        assertEquals("あいうえおかきくけこさしすせそたちつてと", vm.form.value.values.senderName)
        type(FormField.SMTP_USER, "a".repeat(260))
        assertEquals(254, vm.form.value.values.smtpUser.length)
        type(FormField.HOST, "h".repeat(260))
        assertEquals(253, vm.form.value.values.host.length)
    }

    @Test fun imeNextMovesInScreenOrder() {
        show()
        field(FormField.SENDER_NAME).performImeAction()
        field(FormField.SMTP_USER).assertIsFocused().performImeAction()
        field(FormField.PASSWORD).assertIsFocused().performImeAction()
        field(FormField.HOST).assertIsFocused().performImeAction()
        field(FormField.PORT).assertIsFocused()
    }

    @Test fun imeNextSkipsSavedPassword() {
        show(Settings(smtpUser = "chichi@example.jp"), passwordSaved = true)
        field(FormField.SMTP_USER).performImeAction()
        field(FormField.HOST).assertIsFocused()
    }

    @Test fun dirtyBackDialogButtons() {
        show()
        // 未保存なしならそのまま閉じる
        rule.onNodeWithContentDescription("戻る").performClick()
        assertEquals(1, closes)
        // 開き直し(画面は出したまま)
        rule.runOnIdle { vm.onOpened() }
        type(FormField.SENDER_NAME, "母")
        rule.onNodeWithContentDescription("戻る").performClick()
        rule.onNodeWithText(DIRTY).assertIsDisplayed()
        rule.onNodeWithText("戻る").performClick()
        rule.onNodeWithText(DIRTY).assertDoesNotExist()
        assertEquals(1, closes)
        assertEquals("母", vm.form.value.values.senderName)
        rule.onNodeWithContentDescription("戻る").performClick()
        rule.onNodeWithText("破棄").performClick()
        assertEquals(2, closes)
        // 閉じると入力は捨てられる
        assertEquals(false, vm.form.value.loaded)
    }

    @Test fun dirtySystemBackShowsDialogAndBackOnDialogStays() {
        show()
        type(FormField.SENDER_NAME, "母")
        systemBack()
        // 1回目の戻るでキーボードが閉じるだけのことがあるので、出るまで押す
        if (rule.onAllNodesWithText(DIRTY).fetchSemanticsNodes().isEmpty()) systemBack()
        rule.onNodeWithText(DIRTY).assertIsDisplayed()
        systemBack()
        rule.onNodeWithText(DIRTY).assertDoesNotExist()
        assertEquals(0, closes)
    }

    @Test fun subScreenKeepsUnsavedInput() {
        show()
        type(FormField.SENDER_NAME, "お母さん")
        rule.onNodeWithText("未送信一覧").performScrollTo().performClick()
        rule.onNodeWithText("未送信一覧").assertIsDisplayed()
        rule.onNodeWithContentDescription("戻る").performClick()
        rule.onNodeWithText("お母さん").assertIsDisplayed()
        rule.onNodeWithText("送信ログ").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("お母さん").assertDoesNotExist()
        systemBack()
        // スクロール位置も残るので、表示範囲ではなく存在で確かめる
        rule.onNodeWithText("お母さん").assertExists()
        assertEquals("お母さん", vm.form.value.values.senderName)
        assertEquals(0, closes)
    }

    @Test fun buttonSizeSavedImmediately() {
        show()
        rule.onNodeWithText("特大").performScrollTo().performClick()
        rule.waitUntil(5_000) { store.flow.value.buttonSize == ButtonSize.XLARGE }
        rule.onNodeWithText("特大").assertIsSelected()
        save().assertIsNotEnabled()
    }

    @Test fun textFollowsSystemFontScale() {
        show()
        val h1 = rule.onNodeWithText("メール設定").getUnclippedBoundsInRoot().height
        fontScale = 2f
        rule.waitForIdle()
        val h2 = rule.onNodeWithText("メール設定").getUnclippedBoundsInRoot().height
        assertTrue("h1=$h1 h2=$h2", h2 > h1 * 1.5f)
    }

    // ---- 送り先(PLAN-2) ----

    private fun recipientField(f: RecipientField) = rule.onNodeWithTag(RecipientTags.field(f))
    private fun dialogButton(label: String) = rule.onNode(hasText(label) and hasAnyAncestor(isDialog()))
    private fun dialogSave() = dialogButton("保存").performClick()

    private fun addRecipient(name: String, email: String) {
        rule.onNodeWithTag(RecipientTags.ADD).performScrollTo().performClick()
        recipientField(RecipientField.NAME).performTextInput(name)
        recipientField(RecipientField.EMAIL).performTextInput(email)
        dialogSave()
        rule.waitForIdle()
    }

    @Test fun addUpToSixThenAddDisabled() {
        show()
        rule.onNodeWithText("送り先 0/6").assertExists()
        addRecipient("お父さん", "chichi@example.jp")
        rule.onNodeWithText("送り先 1/6").assertExists()
        rule.onNodeWithText("お父さん").assertExists()
        rule.onNodeWithText("chichi@example.jp").assertExists()
        (2..6).forEach { addRecipient("送り先$it", "r$it@example.jp") }
        rule.onNodeWithText("送り先 6/6").assertExists()
        rule.onNodeWithTag(RecipientTags.ADD).assertIsNotEnabled()
        assertEquals(6, store.flow.value.recipients.size)
        // 送り先の変更は保存ボタンに影響しない
        save().assertIsNotEnabled()
    }

    @Test fun dialogTitlesAndValidationKeepDialogOpen() {
        show(Settings(recipients = recipients(1)))
        rule.onNodeWithTag(RecipientTags.ADD).performScrollTo().performClick()
        rule.onNodeWithText("送り先を追加").assertIsDisplayed()
        // 未入力でも保存は押せ、押すとエラーで開いたまま
        dialogButton("保存").assertIsEnabled()
        dialogSave()
        rule.onNodeWithText("表示名を入力してください").assertIsDisplayed()
        rule.onNodeWithText("メールアドレスの形式が正しくありません").assertIsDisplayed()
        rule.onNodeWithText("送り先を追加").assertIsDisplayed()
        recipientField(RecipientField.NAME).assertIsFocused()
        // 11文字はエラー、10文字は通る
        recipientField(RecipientField.NAME).performTextInput("あいうえおかきくけこさ")
        recipientField(RecipientField.EMAIL).performTextInput("haha@example.jp")
        dialogSave()
        rule.onNodeWithText("10文字以内で入力してください").assertIsDisplayed()
        assertEquals(1, store.flow.value.recipients.size)
        recipientField(RecipientField.NAME).performTextClearance()
        recipientField(RecipientField.NAME).performTextInput("あいうえおかきくけこ")
        dialogSave()
        rule.waitForIdle()
        rule.onNodeWithText("送り先を追加").assertDoesNotExist()
        assertEquals("あいうえおかきくけこ", store.flow.value.recipients[1].name)

        rule.onAllNodesWithContentDescription("編集")[0].performClick()
        rule.onNodeWithText("送り先を編集").assertIsDisplayed()
    }

    @Test fun imeNextMovesToEmail() {
        show()
        rule.onNodeWithTag(RecipientTags.ADD).performScrollTo().performClick()
        recipientField(RecipientField.NAME).performClick().performImeAction()
        recipientField(RecipientField.EMAIL).assertIsFocused()
    }

    @Test fun editKeepsPositionAndBackDiscards() {
        show(Settings(recipients = recipients(3)))
        rule.onAllNodesWithContentDescription("編集")[1].performClick()
        recipientField(RecipientField.NAME).performTextClearance()
        recipientField(RecipientField.NAME).performTextInput("お母さん")
        // 戻るでは保存しない(1回目はキーボードを閉じるだけのことがある)
        systemBack()
        if (rule.onAllNodesWithText("送り先を編集").fetchSemanticsNodes().isNotEmpty()) systemBack()
        rule.onNodeWithText("送り先を編集").assertDoesNotExist()
        assertEquals("送り先2", store.flow.value.recipients[1].name)
        assertEquals(0, closes)
        // 保存すると同じ位置で変わる
        rule.onAllNodesWithContentDescription("編集")[1].performClick()
        recipientField(RecipientField.NAME).performTextClearance()
        recipientField(RecipientField.NAME).performTextInput("お母さん")
        dialogSave()
        rule.waitForIdle()
        assertEquals(listOf("r1", "r2", "r3"), store.flow.value.recipients.map { it.id })
        assertEquals("お母さん", store.flow.value.recipients[1].name)
    }

    @Test fun cancelButtonDiscards() {
        show(Settings(recipients = recipients(1)))
        rule.onAllNodesWithContentDescription("編集")[0].performClick()
        recipientField(RecipientField.NAME).performTextInput("追記")
        dialogButton("キャンセル").performClick()
        rule.onNodeWithText("送り先を編集").assertDoesNotExist()
        assertEquals("送り先1", store.flow.value.recipients[0].name)
    }

    @Test fun deleteWordingByCountAndCancel() {
        show(Settings(recipients = recipients(2)))
        rule.onAllNodesWithContentDescription("削除").assertCountEquals(2)
        rule.onAllNodesWithContentDescription("編集").assertCountEquals(2)
        rule.onAllNodesWithContentDescription("削除")[0].performClick()
        rule.onNodeWithText("この宛先を削除しますか？").assertIsDisplayed()
        dialogButton("キャンセル").performClick()
        rule.onNodeWithText("この宛先を削除しますか？").assertDoesNotExist()
        assertEquals(2, store.flow.value.recipients.size)

        queuedCount = 2
        rule.onAllNodesWithContentDescription("削除")[0].performClick()
        rule.onNodeWithText(
            "この宛先を削除しますか？ 送れていない写真が2枚あります。削除すると、その写真は送られなくなります（未送信一覧に残ります）。",
        ).assertIsDisplayed()
        // 戻るはキャンセルと同じ
        systemBack()
        rule.onNodeWithText("この宛先を削除しますか？", substring = true).assertDoesNotExist()
        assertEquals(2, store.flow.value.recipients.size)
        assertTrue(deletedCalls.isEmpty())

        rule.onAllNodesWithContentDescription("削除")[0].performClick()
        dialogButton("削除").performClick()
        rule.waitUntil(5_000) { deletedCalls.isNotEmpty() }
        assertEquals(listOf("r2"), store.flow.value.recipients.map { it.id })
        assertEquals(listOf("r1"), deletedCalls)
        rule.onNodeWithText("送り先 1/6").assertExists()
    }

    @Test fun longEmailIsOneLineAndEmojiName() {
        val name = "\uD83D\uDC68\u200D\uD83D\uDC69お父さん"
        val long = "very.long.address.for.testing.ellipsis.${"x".repeat(60)}@example.jp"
        show(Settings(recipients = listOf(Recipient("r1", name, long))))
        rule.onNodeWithText(name).assertIsDisplayed()
        val emailBounds = rule.onNodeWithText(long).getUnclippedBoundsInRoot()
        val nameBounds = rule.onNodeWithText(name).getUnclippedBoundsInRoot()
        // 長いアドレスも1行(名前の行と同程度の高さ)
        assertTrue(emailBounds.height < nameBounds.height * 1.5f)
    }
}
