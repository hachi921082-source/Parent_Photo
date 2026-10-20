package com.example.parentphoto.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.parentphoto.R
import com.example.parentphoto.settings.ButtonSize
import com.example.parentphoto.ui.theme.LocalExtraColors
import com.example.parentphoto.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/*
 * 父親向け画面の共通部品(ds 3.2 / 3.3 / 3.4、photo-send-flow PLAN-2)。
 * 文字は固定サイズ(ボタン 28 sp、メッセージ 36 sp、バッジ 20 sp)。アニメーションは使わない。
 */

internal val BUTTON_TEXT = 28.sp
internal val MESSAGE_TEXT = 36.sp
internal val BADGE_TEXT = 20.sp

// 行の高さ。テーマ既定(24 sp 固定)のままだと 36 sp / 28 sp の2行目が重なるので文字の大きさに比例させる
internal val LINE_HEIGHT = 1.3.em

// 写真の上や明るい背景の上に置く設定アイコンの色(ds 3.4, Q60)
internal val ICON_ON_LIGHT = Color(0xFF49454F)
internal val ICON_ON_CAMERA = Color.White.copy(alpha = 0.7f)

// カメラ映像の上の暗い箱
internal val DARK_BOX = Color.Black.copy(alpha = 0.6f)

/** 36 sp の中央寄せメッセージ */
@Composable
internal fun MessageText(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onBackground) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = MESSAGE_TEXT,
        lineHeight = LINE_HEIGHT,
        textAlign = TextAlign.Center,
    )
}

/** カメラ映像や写真の上に重ねる暗い角丸の箱(撮影失敗・満杯の案内) */
@Composable
internal fun OverlayMessage(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(16.dp)
            .background(DARK_BOX, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        MessageText(text, color = Color.White)
    }
}

// ボタンの文字。28 sp、中央寄せ、2行まで
@Composable
private fun ButtonLabel(text: String) {
    Text(text = text, fontSize = BUTTON_TEXT, lineHeight = LINE_HEIGHT, textAlign = TextAlign.Center, maxLines = 2)
}

/** 塗りつぶしのボタン(送り先・取り消す・設定を開く)。高さは設定のボタンの大きさ以上、横幅いっぱい */
@Composable
internal fun FatherFilledButton(
    text: String,
    size: ButtonSize,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = size.dp.dp),
        shape = MaterialTheme.shapes.medium,
    ) { ButtonLabel(text) }
}

/** 枠線のボタン(やり直す)。文字は secondary、枠は outline */
@Composable
internal fun FatherOutlinedButton(
    text: String,
    size: ButtonSize,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = size.dp.dp),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
    ) { ButtonLabel(text) }
}

/**
 * 設定アイコン(歯車 24 dp、触れる範囲 48 dp)。タップでは何も起きない。
 * 指を動かさずに 3000 ms 押し続けたときだけ onLongPress(タッチスロップを超えて動いたら取り消し、ds 3.3)。
 */
@Composable
internal fun SettingsIcon(tint: Color, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    val callback by rememberUpdatedState(onLongPress)
    Box(
        modifier = modifier
            .size(48.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    // 時間切れ(null)= 3000 ms 押し続けた。途中で離す・動かすと false
                    val released = withTimeoutOrNull<Boolean>(Constants.LONG_PRESS_MS) {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                ?: return@withTimeoutOrNull false
                            if (!change.pressed) return@withTimeoutOrNull false
                            if ((change.position - down.position).getDistance() > slop) {
                                return@withTimeoutOrNull false
                            }
                        }
                        @Suppress("UNREACHABLE_CODE")
                        false
                    }
                    if (released == null) callback()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_settings),
            contentDescription = stringResource(R.string.cd_settings),
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** 左上のバッジ。「未送信 N件」(N > 0)の下に「送れていません M件」(M > 0、警告色)、8 dp 間隔 */
@Composable
internal fun Badges(unsent: Int, attention: Int, modifier: Modifier = Modifier) {
    if (unsent <= 0 && attention <= 0) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (unsent > 0) {
            Badge(stringResource(R.string.badge_unsent, unsent), DARK_BOX)
        }
        if (attention > 0) {
            Badge(stringResource(R.string.badge_attention, attention), LocalExtraColors.current.warning)
        }
    }
}

@Composable
private fun Badge(text: String, background: Color) {
    Text(
        text = text,
        color = Color.White,
        fontSize = BADGE_TEXT,
        lineHeight = LINE_HEIGHT,
        maxLines = 1,
        modifier = Modifier
            .background(background, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** 縮小済みの写真を読み込んで全体が見えるように表示する。読み込み中は何も出さない */
@Composable
internal fun PhotoView(file: File?, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, file, file?.lastModified()) {
        value = file?.let { f ->
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    Box(modifier) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 進行中を示す小さな輪(読み上げは「処理中」) */
@Composable
internal fun ProgressMark(color: Color, modifier: Modifier = Modifier) {
    val cd = stringResource(R.string.cd_progress)
    androidx.compose.material3.CircularProgressIndicator(
        color = color,
        modifier = modifier.size(32.dp).semantics { contentDescription = cd },
    )
}

/** 全面の単色背景に 36 sp のメッセージだけを出す(Sent / QueuedNotice / SetupRequired) */
@Composable
internal fun CenteredMessageScreen(text: String, background: Color, textColor: Color, content: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxSize().background(background)) {
        MessageText(text, Modifier.align(Alignment.Center).padding(16.dp), color = textColor)
        content()
    }
}
