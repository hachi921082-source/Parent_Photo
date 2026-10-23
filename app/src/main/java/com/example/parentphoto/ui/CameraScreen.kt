package com.example.parentphoto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.parentphoto.R

// シャッター: 72 dp の円、下端から 32 dp(ds 3.4)
internal val SHUTTER_SIZE = 72.dp
internal val SHUTTER_BOTTOM = 32.dp

// 接続表示の上端: 上余白 16 + バッジ 38 + 間隔 8 + バッジ 38 + 間隔 8
internal val CONNECTIVITY_TOP = 108.dp

/**
 * カメラ画面(Camera / OutboxFull / Capturing、ds 3.4)。cameraFeed には CameraView を渡す
 * (テストでは空の Box)。3つの状態で同じ呼び出し位置に置くので、状態が変わってもカメラは束ね直さない。
 */
@Composable
fun CameraScreen(
    state: UiState,
    unsentCount: Int,
    attentionCount: Int,
    online: Boolean,
    captureFailureVisible: Boolean,
    onShutter: () -> Unit,
    onSettingsLongPress: () -> Unit,
    cameraFeed: @Composable () -> Unit,
) {
    val full = state == UiState.OutboxFull
    val capturing = state == UiState.Capturing
    val shutterEnabled = state == UiState.Camera

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 映像は全面(システムバーの下まで)。操作・バッジ・歯車・文字は下の Box でバーの内側に収める
        Box(Modifier.fillMaxSize()) { cameraFeed() }

        Box(Modifier.fillMaxSize().fatherInsetsPadding()) {
            Badges(
                unsent = unsentCount,
                attention = attentionCount,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            )

            // バッジ2段分(各 38 dp + 間隔 8 dp)の下に固定で置く。バッジの数が変わっても動かない
            ConnectivityIndicator(
                online = online,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = CONNECTIVITY_TOP),
            )

            // 撮影中は設定アイコンを出さない
            if (!capturing) {
                SettingsIcon(
                    tint = ICON_ON_CAMERA,
                    onLongPress = onSettingsLongPress,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                )
            }

            if (capturing) {
                ProgressMark(color = Color.White, modifier = Modifier.align(Alignment.Center))
            }

            if (captureFailureVisible) {
                OverlayMessage(stringResource(R.string.capture_failure), Modifier.align(Alignment.Center))
            }

            if (full) {
                // シャッターのすぐ上(16 dp)に案内を置く
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = SHUTTER_BOTTOM + SHUTTER_SIZE + 16.dp)
                        .background(DARK_BOX)
                        .padding(16.dp),
                ) {
                    MessageText(stringResource(R.string.outbox_full_guidance), color = Color.White)
                }
            }

            Shutter(
                enabled = shutterEnabled,
                onClick = onShutter,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = SHUTTER_BOTTOM),
            )
        }
    }
}

// 白い円に #1C1B1F の 4 dp の輪。押せない時は 40 % の濃さで、押しても何も起きない
@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cd = stringResource(R.string.cd_shutter)
    Box(
        modifier
            .size(SHUTTER_SIZE)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(Color.White)
            .border(4.dp, Color(0xFF1C1B1F), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = cd },
    )
}
