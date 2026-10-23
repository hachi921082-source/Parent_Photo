package com.example.parentphoto.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.parentphoto.settings.ButtonSize
import com.example.parentphoto.settings.Recipient
import com.example.parentphoto.ui.theme.FatherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** 父親向け画面の表示テスト(photo-send-flow PLAN-2 5)。状態機械は使わず各画面を直接出す。 */
@RunWith(AndroidJUnit4::class)
class FatherScreensTest {

    @get:Rule
    val rule = createComposeRule()

    private var shutterTaps = 0
    private var longPresses = 0
    private var recipientTaps = mutableListOf<String>()
    private var retakes = 0
    private var openSettings = 0

    private fun recipients(n: Int) = (1..n).map { Recipient("$it", "送り先$it", "r$it@example.com") }

    private fun show(content: @Composable () -> Unit) {
        rule.setContent { FatherTheme { content() } }
    }

    @Composable
    private fun camera(
        state: UiState,
        unsent: Int = 0,
        attention: Int = 0,
        failure: Boolean = false,
        online: Boolean = true,
    ) {
        CameraScreen(
            state = state,
            unsentCount = unsent,
            attentionCount = attention,
            online = online,
            captureFailureVisible = failure,
            onShutter = { shutterTaps++ },
            onSettingsLongPress = { longPresses++ },
            cameraFeed = { Box(Modifier.fillMaxSize()) },
        )
    }

    @Composable
    private fun preview(list: List<Recipient>, size: ButtonSize = ButtonSize.LARGE, notice: Boolean = false) {
        PreviewScreen(
            photoFile = null,
            recipients = list,
            buttonSize = size,
            outboxFullNoticeVisible = notice,
            onRecipient = { recipientTaps += it },
            onRetake = { retakes++ },
            onSettingsLongPress = { longPresses++ },
        )
    }

    private fun noAttentionWord() {
        rule.onAllNodesWithText("要確認", substring = true).assertCountEquals(0)
    }

    private fun settingsIconCount(n: Int) {
        rule.onAllNodesWithContentDescription("設定").assertCountEquals(n)
    }

    private fun rootHeight(): Dp = rule.onRoot().getUnclippedBoundsInRoot().height

    private fun near(expected: Dp, actual: Dp) {
        assertTrue("expected $expected but was $actual", abs(expected.value - actual.value) < 1.5f)
    }

    // ---- 画面ごと ----

    @Test
    fun Loadingは何も出さない() {
        show { LoadingScreen() }
        settingsIconCount(0)
        rule.onAllNodesWithText("", substring = true).assertCountEquals(0)
    }

    @Test
    fun SetupRequiredは文言と設定アイコンだけ() {
        show { SetupRequiredScreen { longPresses++ } }
        rule.onNodeWithText("設定が必要です").assertIsDisplayed()
        settingsIconCount(1)
        noAttentionWord()
    }

    @Test
    fun PermissionDeniedは案内と設定を開くボタン() {
        show { PermissionDeniedScreen(ButtonSize.LARGE, { openSettings++ }, { longPresses++ }) }
        rule.onNodeWithText("カメラの使用を許可してください").assertIsDisplayed()
        rule.onNodeWithText("設定を開く").performClick()
        assertEquals(1, openSettings)
        settingsIconCount(1)
        noAttentionWord()
    }

    @Test
    fun Cameraのシャッターは72dpで下端から32dp() {
        show { camera(UiState.Camera) }
        val shutter = rule.onNodeWithContentDescription("撮影")
        shutter.assertWidthIsEqualTo(72.dp).assertHeightIsEqualTo(72.dp)
        near(rootHeight() - 32.dp, shutter.getUnclippedBoundsInRoot().bottom)
        shutter.performClick()
        assertEquals(1, shutterTaps)
        settingsIconCount(1)
        noAttentionWord()
    }

    @Test
    fun バッジは未送信が上で8dp間隔_0件なら出さない() {
        var unsent by mutableStateOf(2)
        var attention by mutableStateOf(3)
        show { camera(UiState.Camera, unsent, attention) }
        val top = rule.onNodeWithText("未送信 2件").getUnclippedBoundsInRoot()
        val bottom = rule.onNodeWithText("送れていません 3件").getUnclippedBoundsInRoot()
        // 文字の範囲はバッジの内側余白(左右 12 dp、上下 6 dp)を含まない
        near(top.bottom + 6.dp + 8.dp + 6.dp, bottom.top)
        near(16.dp + 12.dp, top.left)
        near(16.dp + 6.dp, top.top)

        unsent = 0
        attention = 0
        rule.waitForIdle()
        rule.onAllNodesWithText("未送信", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("送れていません", substring = true).assertCountEquals(0)

        attention = 1
        rule.waitForIdle()
        rule.onNodeWithText("送れていません 1件").assertIsDisplayed()
        rule.onAllNodesWithText("未送信", substring = true).assertCountEquals(0)
    }

    @Test
    fun 接続表示はオンラインとオフラインで文言が変わりバッジの位置は動かない() {
        var online by mutableStateOf(true)
        show { camera(UiState.Camera, unsent = 2, attention = 3, online = online) }
        val badgeTop = rule.onNodeWithText("未送信 2件").getUnclippedBoundsInRoot()
        val badgeBottom = rule.onNodeWithText("送れていません 3件").getUnclippedBoundsInRoot()
        rule.onNodeWithText("撮影してすぐ送ります").assertIsDisplayed()
        rule.onAllNodesWithText("現在圏外なので後で送ります").assertCountEquals(0)
        // 2段のバッジの下で重ならない
        val onlineBox = rule.onNodeWithText("撮影してすぐ送ります").getUnclippedBoundsInRoot()
        assertTrue("indicator below badges", onlineBox.top >= badgeBottom.bottom + 6.dp)

        online = false
        rule.waitForIdle()
        rule.onNodeWithText("現在圏外なので後で送ります").assertIsDisplayed()
        rule.onAllNodesWithText("撮影してすぐ送ります").assertCountEquals(0)
        near(badgeTop.top, rule.onNodeWithText("未送信 2件").getUnclippedBoundsInRoot().top)
        near(badgeBottom.top, rule.onNodeWithText("送れていません 3件").getUnclippedBoundsInRoot().top)
    }

    private fun assertIndicatorAboveShutter(state: UiState) {
        show { camera(state, unsent = 5, online = false) }
        val ind = rule.onNodeWithText("現在圏外なので後で送ります").getUnclippedBoundsInRoot()
        val shutter = rule.onNodeWithContentDescription("撮影").getUnclippedBoundsInRoot()
        assertTrue("above shutter", ind.bottom < shutter.top)
        rule.onNodeWithText("現在圏外なので後で送ります").assertIsDisplayed()
    }

    @Test
    fun 接続表示はOutboxFullでもシャッターに重ならない() = assertIndicatorAboveShutter(UiState.OutboxFull)

    @Test
    fun 接続表示はCapturingでも出てシャッターに重ならない() = assertIndicatorAboveShutter(UiState.Capturing)

    @Test
    fun 警告バッジは橙色() {
        show { camera(UiState.Camera, 0, 4) }
        val img = rule.onNodeWithText("送れていません 4件").captureToImage().asAndroidBitmap()
        // 文字の無い左の余白(12 dp)の中を見る
        val px = img.getPixel(4.coerceAtMost(img.width - 1), img.height / 2)
        assertEquals(Color(0xFFE65100).toArgb(), px)
    }

    @Test
    fun 撮影失敗メッセージは中央() {
        show { camera(UiState.Camera, failure = true) }
        val b = rule.onNodeWithText("撮影できませんでした。もう一度お試しください").getUnclippedBoundsInRoot()
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        near((root.top + root.bottom) / 2, (b.top + b.bottom) / 2)
        near((root.left + root.right) / 2, (b.left + b.right) / 2)
    }

    @Test
    fun OutboxFullはシャッター無効で案内はシャッターの16dp上() {
        show { camera(UiState.OutboxFull, unsent = 5) }
        val shutter = rule.onNodeWithContentDescription("撮影")
        shutter.assertIsNotEnabled()
        shutter.performClick()
        assertEquals(0, shutterTaps)
        val guidance = rule.onNodeWithText("送れていない写真が5枚あります。ネットワークを確認してください")
            .getUnclippedBoundsInRoot()
        // 文字の箱は 16 dp の内側余白を持つので、文字の下端 + 16 dp(余白) + 16 dp(間隔) = シャッター上端
        near(shutter.getUnclippedBoundsInRoot().top, guidance.bottom + 32.dp)
        settingsIconCount(1)
    }

    @Test
    fun Capturingは処理中の輪だけで設定アイコンなし() {
        show { camera(UiState.Capturing) }
        rule.onNodeWithContentDescription("処理中").assertIsDisplayed()
        rule.onNodeWithContentDescription("撮影").assertIsNotEnabled()
        settingsIconCount(0)
    }

    @Test
    fun Previewは写真30パーセント以上でやり直すが最下部() {
        show { preview(recipients(6)) }
        val h = rootHeight()
        assertTrue(rule.onNodeWithTag(TAG_PHOTO).getUnclippedBoundsInRoot().height >= h * 0.3f - 1.dp)
        near(h - 16.dp, rule.onNodeWithText("やり直す").getUnclippedBoundsInRoot().bottom)
        // 6件目はスクロールで出る
        rule.onNodeWithTag(TAG_RECIPIENTS).performScrollToIndex(5)
        rule.onNodeWithText("送り先6").assertIsDisplayed().performClick()
        assertEquals(listOf("6"), recipientTaps)
        rule.onNodeWithText("やり直す").assertIsDisplayed().performClick()
        assertEquals(1, retakes)
        settingsIconCount(1)
        noAttentionWord()
    }

    @Test
    fun 送り先0件なら案内文() {
        show { preview(emptyList()) }
        rule.onNodeWithText("送り先が登録されていません").assertIsDisplayed()
        rule.onNodeWithText("やり直す").assertIsDisplayed()
    }

    @Test
    fun ボタンの高さは設定に従う() {
        var size by mutableStateOf(ButtonSize.STANDARD)
        show { preview(recipients(1), size) }
        for (s in ButtonSize.values()) {
            size = s
            rule.waitForIdle()
            near(s.dp.dp, rule.onNodeWithText("やり直す").getUnclippedBoundsInRoot().height)
            near(s.dp.dp, rule.onNodeWithText("送り先1").getUnclippedBoundsInRoot().height)
        }
    }

    @Test
    fun 全角10文字の名前は狭い画面で2行に折り返してボタンが伸びる() {
        val long = Recipient("1", "あいうえおかきくけこ", "a@example.com")
        show {
            // 1行に収まらない幅にする
            Box(Modifier.width(220.dp).fillMaxHeight()) {
                PreviewScreen(null, listOf(long), ButtonSize.STANDARD, false, {}, {}, {})
            }
        }
        val name = rule.onNodeWithText("あいうえおかきくけこ").getUnclippedBoundsInRoot()
        val retake = rule.onNodeWithText("やり直す").getUnclippedBoundsInRoot()
        near(56.dp, retake.height)
        assertTrue("button should grow for 2 lines: ${name.height}", name.height > 80.dp)
    }

    @Test
    fun 二行のメッセージは行が重ならない() {
        show { Box(Modifier.width(300.dp)) { PermissionDeniedScreen(ButtonSize.LARGE, {}, {}) } }
        // 36 sp の2行なら少なくとも 72 dp(fontScale = 1)
        val h = rule.onNodeWithText("カメラの使用を許可してください").getUnclippedBoundsInRoot().height
        assertTrue("two lines must not overlap: $h", h >= 80.dp)
    }

    @Test
    fun 満杯の案内をPreviewに重ねる() {
        show { preview(recipients(1), notice = true) }
        rule.onNodeWithText("送れていない写真が5枚あります。ネットワークを確認してください").assertIsDisplayed()
        rule.onNodeWithText("送り先1").assertIsDisplayed()
    }

    @Test
    fun CancelWindowは残り秒数と取り消すだけ() {
        var cancels = 0
        show { CancelWindowScreen(null, 3, ButtonSize.LARGE) { cancels++ } }
        rule.onNodeWithText("3秒後に送ります").assertIsDisplayed()
        rule.onNodeWithText("取り消す").performClick()
        assertEquals(1, cancels)
        rule.onAllNodesWithText("やり直す").assertCountEquals(0)
        settingsIconCount(0)
        near(rootHeight() - 16.dp, rule.onNodeWithText("取り消す").getUnclippedBoundsInRoot().bottom)
    }

    @Test
    fun Sendingは送信中だけ() {
        show { SendingScreen(null) }
        rule.onNodeWithText("送信中...").assertIsDisplayed()
        rule.onAllNodesWithText("取り消す").assertCountEquals(0)
        rule.onAllNodesWithText("やり直す").assertCountEquals(0)
        settingsIconCount(0)
    }

    @Test
    fun Sentは送りましたと背景色() {
        show { SentScreen() }
        rule.onNodeWithText("送りました！").assertIsDisplayed()
        settingsIconCount(0)
        val img = rule.onRoot().captureToImage().asAndroidBitmap()
        assertEquals(Color(0xFFEADDFF).toArgb(), img.getPixel(2, 2))
    }

    @Test
    fun QueuedNoticeはあとで送りますだけ() {
        show { QueuedNoticeScreen() }
        rule.onNodeWithText("あとで送ります").assertIsDisplayed()
        settingsIconCount(0)
        noAttentionWord()
    }

    // ---- フォント倍率 ----

    @Test
    fun 端末のフォント倍率を無視する() {
        var scale by mutableStateOf(1f)
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, scale)) {
                FatherTheme { SentScreen() }
            }
        }
        val h1 = rule.onNodeWithText("送りました！").getUnclippedBoundsInRoot().height
        scale = 2f
        rule.waitForIdle()
        val h2 = rule.onNodeWithText("送りました！").getUnclippedBoundsInRoot().height
        assertEquals(h1, h2)
    }

    // ---- 設定アイコンの長押し ----

    @Test
    fun 長押しは3000msで開き2900msでは開かない() {
        show { SetupRequiredScreen { longPresses++ } }
        rule.mainClock.autoAdvance = false
        val icon = rule.onNodeWithContentDescription("設定")
        icon.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(2900)
        assertEquals(0, longPresses)
        icon.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(500)
        assertEquals(0, longPresses)

        icon.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(3000)
        rule.mainClock.advanceTimeByFrame()
        assertEquals(1, longPresses)
        icon.performTouchInput { up() }
    }

    @Test
    fun 長押し中に指が動けば取り消す() {
        show { SetupRequiredScreen { longPresses++ } }
        rule.mainClock.autoAdvance = false
        val icon = rule.onNodeWithContentDescription("設定")
        icon.performTouchInput {
            down(centerLeft)
            moveBy(androidx.compose.ui.geometry.Offset(viewConfiguration.touchSlop * 3, 0f))
        }
        rule.mainClock.advanceTimeBy(3500)
        assertEquals(0, longPresses)
        icon.performTouchInput { up() }
    }

    @Test
    fun タップでは何も起きない() {
        show { SetupRequiredScreen { longPresses++ } }
        rule.onNodeWithContentDescription("設定").performClick()
        rule.waitForIdle()
        assertEquals(0, longPresses)
    }

    // ---- アニメーションなし ----

    @Test
    fun 状態の切り替えは次のフレームで終わる() {
        var sent by mutableStateOf(false)
        show { if (sent) SentScreen() else QueuedNoticeScreen() }
        rule.mainClock.autoAdvance = false
        sent = true
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("送りました！").assertIsDisplayed()
        rule.onAllNodesWithText("あとで送ります").assertCountEquals(0)
    }

    // ---- システムバーの内側(疑似の余白: 上 100 dp、下 48 dp、左右 8 dp) ----

    private val topInset = 100.dp
    private val bottomInset = 48.dp

    private fun showInset(content: @Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(
                LocalInsetsOverride provides WindowInsets(left = 8.dp, top = topInset, right = 8.dp, bottom = bottomInset),
            ) { FatherTheme { content() } }
        }
    }

    private fun assertInside(desc: String, b: androidx.compose.ui.unit.DpRect) {
        val h = rootHeight()
        assertTrue("$desc top=${b.top}", b.top >= topInset - 0.5.dp)
        assertTrue("$desc bottom=${b.bottom}", b.bottom <= h - bottomInset + 0.5.dp)
    }

    @Test
    fun Cameraの操作とバッジは余白の内側でシャッターは内側の下端から32dp() {
        showInset { camera(UiState.Camera, unsent = 2, attention = 1) }
        val shutter = rule.onNodeWithContentDescription("撮影").getUnclippedBoundsInRoot()
        near(rootHeight() - bottomInset - 32.dp, shutter.bottom)
        assertInside("gear", rule.onNodeWithContentDescription("設定").getUnclippedBoundsInRoot())
        assertInside("badge", rule.onNodeWithText("未送信 2件").getUnclippedBoundsInRoot())
        assertInside("badge2", rule.onNodeWithText("送れていません 1件").getUnclippedBoundsInRoot())
        near(topInset + 16.dp + 6.dp, rule.onNodeWithText("未送信 2件").getUnclippedBoundsInRoot().top)
    }

    @Test
    fun OutboxFullの案内もシャッターの上16dpで余白の内側() {
        showInset { camera(UiState.OutboxFull) }
        assertInside("guidance", rule.onNodeWithText("送れていない写真が5枚あります", substring = true).getUnclippedBoundsInRoot())
    }

    @Test
    fun Previewは写真と歯車とボタンが余白の内側() {
        showInset { preview(recipients(3)) }
        assertInside("photo", rule.onNodeWithTag(TAG_PHOTO).getUnclippedBoundsInRoot())
        assertInside("gear", rule.onNodeWithContentDescription("設定").getUnclippedBoundsInRoot())
        val retry = rule.onNodeWithText("やり直す").getUnclippedBoundsInRoot()
        assertInside("retry", retry)
        near(rootHeight() - bottomInset - 16.dp, retry.bottom)
    }

    @Test
    fun CancelWindowの取り消すは余白の内側の下端から16dp() {
        showInset { CancelWindowScreen(null, 3, ButtonSize.LARGE) {} }
        val cancel = rule.onNodeWithText("取り消す").getUnclippedBoundsInRoot()
        assertInside("cancel", cancel)
        near(rootHeight() - bottomInset - 16.dp, cancel.bottom)
    }

    @Test
    fun 文字だけの画面は余白の内側() {
        showInset { SetupRequiredScreen {} }
        assertInside("gear", rule.onNodeWithContentDescription("設定").getUnclippedBoundsInRoot())
    }

    @Test
    fun PermissionDeniedのボタンと歯車は余白の内側() {
        showInset { PermissionDeniedScreen(ButtonSize.XLARGE, {}, {}) }
        assertInside("gear", rule.onNodeWithContentDescription("設定").getUnclippedBoundsInRoot())
        assertInside("button", rule.onNodeWithText("設定を開く").getUnclippedBoundsInRoot())
    }
}
