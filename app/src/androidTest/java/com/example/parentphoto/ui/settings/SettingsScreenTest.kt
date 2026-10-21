package com.example.parentphoto.ui.settings

import android.util.Patterns
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
import android.os.SystemClock
import android.view.MotionEvent
import com.example.parentphoto.R
import com.example.parentphoto.mail.FailureKind
import com.example.parentphoto.ui.LocalInsetsOverride
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
import kotlinx.coroutines.CompletableDeferred
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

    // テスト送信の偽物。gate を完了させるまで送信中のまま待つ(実メールは送らない)
    private val sentTo = mutableListOf<String>()
    @Volatile private var gate = CompletableDeferred<MailResult>()
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

    private fun recipients(n: Int) = (1..n).map { Recipient("r$it", "送り先$it", "r$it@example.jp") }

    private fun show(
        settings: Settings = Settings(),
        passwordSaved: Boolean = false,
        insets: WindowInsets? = null,
    ) {
        store = FakeStore(settings, passwordSaved)
        // 終了時に viewModelScope を止めるため ViewModelStore に入れる
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(store, { resumes++ }, store, outbox, sender, unsent, Patterns.EMAIL_ADDRESS, {}) as T
        }
        vm = ViewModelProvider(vmStore, factory)[SettingsViewModel::class.java]
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale),
                LocalInsetsOverride provides insets,
            ) {
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

    // 疑似のシステムバー(上 100 dp、下 48 dp)の内側に、アプリバーと本文の端が収まる
    @Test fun contentStaysInsideSystemBars() {
        show(insets = WindowInsets(left = 0.dp, top = 100.dp, right = 0.dp, bottom = 48.dp))
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val back = rule.onNodeWithContentDescription(text(R.string.cd_back)).getUnclippedBoundsInRoot()
        assertTrue("back button top=${back.top}", back.top >= 100.dp - 0.5.dp)
        val scroll = rule.onNodeWithTag(SettingsTags.SCROLL).getUnclippedBoundsInRoot()
        assertTrue("scroll bottom=${scroll.bottom}", scroll.bottom <= root.bottom - 48.dp + 0.5.dp)
        // 副画面も同じ
        rule.onNodeWithText(text(R.string.link_send_log)).performScrollTo().performClick()
        rule.waitForIdle()
        val back2 = rule.onNodeWithContentDescription(text(R.string.cd_back)).getUnclippedBoundsInRoot()
        assertTrue("sub back button top=${back2.top}", back2.top >= 100.dp - 0.5.dp)
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
        rule.onNodeWithText("お母さん").assertExists()
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

    private fun inRow(id: String, text: String) =
        rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(RecipientTags.row(id))), useUnmergedTree = true)

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
        // テスト送信の選択欄にも名前が出るので、一覧の行の中で確かめる
        inRow("id1", "お父さん").assertExists()
        inRow("id1", "chichi@example.jp").assertExists()
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
        inRow("r1", name).assertIsDisplayed()
        val emailBounds = inRow("r1", long).getUnclippedBoundsInRoot()
        val nameBounds = inRow("r1", name).getUnclippedBoundsInRoot()
        // 長いアドレスも1行(名前の行と同程度の高さ)
        assertTrue(emailBounds.height < nameBounds.height * 1.5f)
    }

    // ---- テスト送信(PLAN-3) ----

    private val validSettings = Settings(smtpUser = "chichi@example.jp", recipients = (1..3).map {
        Recipient("r$it", "送り先$it", "r$it@example.jp")
    })

    private fun hint() = rule.onNodeWithTag(TestSendTags.HINT)
    private fun testButton() = rule.onNodeWithTag(TestSendTags.BUTTON)

    // 画面の左上(ダイアログの外)を指で叩く
    private fun tapOutside() {
        val t = SystemClock.uptimeMillis()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.injectInputEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 5f, 300f, 0), true)
        ui.injectInputEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, 5f, 300f, 0), true)
        rule.waitForIdle()
    }

    @Test fun hintPriorityAndButtonState() {
        // 送り先なし(保存済み設定も不正、未保存の変更もあり)は 1 番目の案内
        show()
        type(FormField.SENDER_NAME, "父")
        testButton().performScrollTo().assertIsNotEnabled()
        hint().assertTextEquals("送り先を登録してください")
        // 送り先を足すと 2 番目(保存済み設定が不正)
        addRecipient("母", "haha@example.jp")
        hint().performScrollTo().assertTextEquals("先にメール設定を保存してください")
    }

    @Test fun hintDirtyThenEnabled() {
        show(validSettings, passwordSaved = true)
        testButton().performScrollTo().assertIsEnabled()
        hint().assertDoesNotExist()
        type(FormField.SENDER_NAME, "父")
        testButton().performScrollTo().assertIsNotEnabled()
        hint().assertTextEquals("先に保存してください")
    }

    @Test fun dropdownSelectionFallsBackAfterDelete() {
        show(validSettings, passwordSaved = true)
        rule.onNodeWithTag(TestSendTags.DROPDOWN).performScrollTo().assertTextContains("送り先1")
        rule.onNodeWithTag(TestSendTags.DROPDOWN).performClick()
        rule.onNodeWithTag(TestSendTags.option("r3")).performClick()
        rule.onNodeWithTag(TestSendTags.DROPDOWN).assertTextContains("送り先3")
        rule.onAllNodesWithContentDescription("削除")[2].performScrollTo().performClick()
        dialogButton("削除").performClick()
        rule.waitUntil(5_000) { store.flow.value.recipients.size == 2 }
        rule.onNodeWithTag(TestSendTags.DROPDOWN).assertTextContains("送り先1")
        testButton().performScrollTo().performClick()
        rule.waitUntil(5_000) { sentTo.isNotEmpty() }
        assertEquals(listOf("r1"), sentTo)
        gate.complete(MailResult.Success)
    }

    @Test fun progressDialogBlocksBackAndOutsideThenResultOnlyClosesByOk() {
        show(validSettings, passwordSaved = true)
        testButton().performScrollTo().performClick()
        rule.onNodeWithText("送信中...").assertIsDisplayed()
        rule.onNodeWithContentDescription("処理中").assertExists()
        systemBack()
        tapOutside()
        rule.onNodeWithText("送信中...").assertIsDisplayed()
        assertEquals(0, closes)

        gate.complete(MailResult.Success)
        rule.waitUntil(5_000) { rule.onAllNodesWithText("送信できました").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("送信中...").assertDoesNotExist()
        rule.waitUntil(5_000) { resumes == 1 }
        systemBack()
        tapOutside()
        rule.onNodeWithText("送信できました").assertIsDisplayed()
        assertEquals(0, closes)
        dialogButton("OK").performClick()
        rule.onNodeWithText("送信できました").assertDoesNotExist()
        testButton().assertIsEnabled()
    }

    @Test fun failureTexts() {
        show(validSettings, passwordSaved = true)
        val cases = listOf(
            MailCode.AUTH to "ログインできませんでした。送信元アドレスとアプリパスワードを確認してください",
            MailCode.TIMEOUT to "ネットワークに接続できませんでした",
            MailCode.NETWORK to "ネットワークに接続できませんでした",
            MailCode.REJECTED to "宛先アドレスが受け付けられませんでした",
            MailCode.OTHER to "送信に失敗しました（OTHER）",
        )
        cases.forEach { (code, text) ->
            gate = CompletableDeferred()
            testButton().performScrollTo().performClick()
            rule.waitForIdle()
            gate.complete(MailResult.Failure(FailureKind.PERMANENT, code, null, null, null))
            rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
            dialogButton("OK").performClick()
            rule.waitForIdle()
        }
        assertEquals(0, resumes)
    }

    // ---- 未送信一覧・送信ログ(PLAN-4) ----

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    // 縮小画像の読み込み用に本物の JPEG を作る
    private fun jpeg(name: String): java.io.File {
        val f = java.io.File(ctx.cacheDir, "$name.jpg")
        val bmp = android.graphics.Bitmap.createBitmap(400, 300, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.GREEN)
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
        return f
    }

    private fun item(
        id: String,
        state: ItemState,
        phase: Phase = Phase.QUEUED,
        reason: AttentionReason? = null,
        name: String? = "送り先1",
        file: java.io.File = jpeg(id),
        at: Long = 1_792_406_100_000L,
    ) = OutboxItemView(id, "r1", name, at, state, phase, reason, file)

    private fun openUnsent() {
        rule.onNodeWithText("未送信一覧").performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun openLog() {
        rule.onNodeWithText("送信ログ").performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Test fun unsentGroupsHeadersOrderAndLabels() {
        unsent.itemsFlow.value = listOf(
            item("w1", ItemState.WAITING),
            item("w2", ItemState.WAITING, Phase.SENDING_BG),
            item("a1", ItemState.ATTENTION, reason = AttentionReason.AUTH),
            item("a2", ItemState.ATTENTION, reason = AttentionReason.PERMANENT),
            item("a3", ItemState.ATTENTION, reason = AttentionReason.RETRY_EXPIRED),
            item("a4", ItemState.ATTENTION, reason = AttentionReason.RECIPIENT_DELETED, name = null),
        )
        show()
        openUnsent()
        rule.onNodeWithText("送信待ち 2件").assertIsDisplayed()
        rule.onNodeWithText("要確認 4件/10", useUnmergedTree = true).assertExists()
        // 送信待ちの群が先、行の並びは窓口の順
        val top = { id: String -> rule.onNodeWithTag(UnsentTags.row(id)).getUnclippedBoundsInRoot().top }
        assertTrue(top("w1") < top("w2"))
        rule.onNodeWithTag(UnsentTags.resend("w1")).assertIsEnabled()
        rule.onNodeWithTag(UnsentTags.delete("w1")).assertIsEnabled()
        // 送信中は待機中と表示して両方押せない
        rule.onNodeWithTag(UnsentTags.resend("w2")).assertIsNotEnabled()
        rule.onNodeWithTag(UnsentTags.delete("w2")).assertIsNotEnabled()
        listOf("a1" to "ログイン失敗", "a2" to "送信拒否", "a3" to "24時間再試行しても送れませんでした", "a4" to "宛先が削除されました")
            .forEach { (id, label) ->
                rule.onNodeWithTag(UnsentTags.LIST).performScrollToNode(hasTestTag(UnsentTags.row(id)))
                rule.onNode(hasText(label) and hasAnyAncestor(hasTestTag(UnsentTags.row(id))), useUnmergedTree = true).assertExists()
            }
        rule.onNode(hasText("（削除された宛先）") and hasAnyAncestor(hasTestTag(UnsentTags.row("a4"))), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(UnsentTags.resend("a4")).assertIsNotEnabled()
        rule.onNodeWithTag(UnsentTags.delete("a4")).assertIsEnabled()
        rule.onNodeWithTag(UnsentTags.resend("a1")).assertIsEnabled()
    }

    @Test fun unsentRowLayoutAndMissingImage() {
        unsent.itemsFlow.value = listOf(
            item("a1", ItemState.ATTENTION, reason = AttentionReason.AUTH),
            item("m1", ItemState.ATTENTION, reason = AttentionReason.AUTH, file = java.io.File(ctx.cacheDir, "none.jpg")),
        )
        show()
        openUnsent()
        // 送信待ちが空なら見出しも出さない
        rule.onNodeWithText("送信待ち", substring = true).assertDoesNotExist()
        rule.onNodeWithTag(UnsentTags.thumb("a1")).assertWidthIsEqualTo(96.dp).assertHeightIsEqualTo(96.dp)
        val thumb = rule.onNodeWithTag(UnsentTags.thumb("a1")).getUnclippedBoundsInRoot()
        val resend = rule.onNodeWithTag(UnsentTags.resend("a1")).getUnclippedBoundsInRoot()
        val name = rule.onNode(hasText("送り先1") and hasAnyAncestor(hasTestTag(UnsentTags.row("a1"))), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        // 画像が左、文字が右、ボタンは文字の下
        assertTrue(thumb.right <= name.left)
        assertTrue(resend.top > name.bottom)
        // 画像が無い行は「画像なし」で、削除だけ押せる
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasText("画像なし"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(UnsentTags.resend("m1")).assertIsNotEnabled()
        rule.onNodeWithTag(UnsentTags.delete("m1")).assertIsEnabled()
        rule.onNodeWithTag(UnsentTags.resend("a1")).assertIsEnabled()
    }

    @Test fun unsentDeleteDialogAndCancel() {
        unsent.itemsFlow.value = listOf(item("w1", ItemState.WAITING))
        show()
        openUnsent()
        rule.onNodeWithTag(UnsentTags.delete("w1")).performClick()
        rule.onNodeWithText("削除しますか？").assertIsDisplayed()
        dialogButton("キャンセル").performClick()
        rule.onNodeWithText("削除しますか？").assertDoesNotExist()
        rule.onNodeWithTag(UnsentTags.delete("w1")).performClick()
        systemBack()
        rule.onNodeWithText("削除しますか？").assertDoesNotExist()
        assertTrue(unsent.deleted.isEmpty())
        rule.onNodeWithTag(UnsentTags.delete("w1")).performClick()
        dialogButton("削除").performClick()
        rule.waitUntil(5_000) { unsent.deleted.isNotEmpty() }
        assertEquals(listOf("w1"), unsent.deleted)
        // 両方の群が空になったら空の文言
        rule.onNodeWithText("未送信の写真はありません").assertIsDisplayed()
    }

    @Test fun waitingSnackbarOnlyForWaiting() {
        unsent.itemsFlow.value = listOf(item("a1", ItemState.ATTENTION, reason = AttentionReason.AUTH))
        show()
        openUnsent()
        unsent.outcome = ResendOutcome.Started
        rule.onNodeWithTag(UnsentTags.resend("a1")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("順番待ちです。しばらくお待ちください").assertDoesNotExist()
        unsent.outcome = ResendOutcome.Waiting
        rule.onNodeWithTag(UnsentTags.resend("a1")).performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("順番待ちです。しばらくお待ちください").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf("a1", "a1"), unsent.resent)
    }

    @Test fun emptyStates() {
        show()
        openUnsent()
        rule.onNodeWithText("未送信の写真はありません").assertIsDisplayed()
        rule.onNodeWithContentDescription("戻る").performClick()
        openLog()
        rule.onNodeWithText("送信ログはありません").assertIsDisplayed()
    }

    @Test fun sendLogMappingCodesAndTokyoTime() {
        val original = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try {
            // 2026-10-19 10:36:05 UTC = 19:36:05 東京
            val t = java.time.Instant.parse("2026-10-19T10:36:05Z").toEpochMilli()
            unsent.logFlow.value = SendLogResult.entries.mapIndexed { i, r ->
                SendLogEntry(i.toLong() + 1, t, if (r == SendLogResult.DELETED_MANUAL) null else "送り先1", r, "NETWORK")
            }
            show()
            openLog()
            val texts = mapOf(
                SendLogResult.SENT to "送信成功", SendLogResult.FAILED to "失敗",
                SendLogResult.QUEUED_OFFLINE to "オフラインのため保留", SendLogResult.DELETED_MANUAL to "削除",
                SendLogResult.DELETED_OVERFLOW to "削除（上限超過）", SendLogResult.RESEND_REQUESTED to "再送要求",
                SendLogResult.TEST_OK to "テスト成功", SendLogResult.TEST_FAILED to "テスト失敗",
            )
            SendLogResult.entries.forEachIndexed { i, r ->
                val id = i.toLong() + 1
                rule.onNodeWithTag(SendLogTags.LIST).performScrollToIndex(i)
                val inRow = { text: String ->
                    rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(SendLogTags.row(id))), useUnmergedTree = true)
                }
                inRow(texts.getValue(r)).assertExists()
                inRow("2026/10/19 19:36:05").assertExists()
                if (r == SendLogResult.FAILED || r == SendLogResult.TEST_FAILED) inRow("NETWORK").assertExists()
                else inRow("NETWORK").assertDoesNotExist()
                if (r == SendLogResult.DELETED_MANUAL) inRow("（削除された宛先）").assertExists()
            }
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }

    @Test fun sendLogShowsFiftyRows() {
        unsent.logFlow.value = (1..50).map { SendLogEntry(it.toLong(), it * 1000L, "送り先$it", SendLogResult.SENT, null) }
        show()
        openLog()
        rule.onNodeWithTag(SendLogTags.LIST).performScrollToIndex(49)
        rule.onNodeWithTag(SendLogTags.row(50)).assertExists()
        rule.onNodeWithTag(SendLogTags.LIST).onChildren().fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }
}
