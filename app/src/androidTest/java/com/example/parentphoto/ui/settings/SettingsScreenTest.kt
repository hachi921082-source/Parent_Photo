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

    private class FakeStore(initial: Settings, var passwordSaved: Boolean) : SettingsScreenStore {
        val flow = MutableStateFlow(initial)
        val saves = mutableListOf<Pair<Settings, String?>>()
        override val settings: Flow<Settings> = flow
        override suspend fun isPasswordSaved() = passwordSaved
        override suspend fun saveSmtp(senderName: String, user: String, host: String, port: Int, newPasswordOrNull: String?) {
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

    private fun show(settings: Settings = Settings(), passwordSaved: Boolean = false) {
        store = FakeStore(settings, passwordSaved)
        // 終了時に viewModelScope を止めるため ViewModelStore に入れる
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(store, { resumes++ }, Patterns.EMAIL_ADDRESS, {}) as T
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
}
