package com.example.parentphoto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.parentphoto.R
import com.example.parentphoto.settings.ButtonSize
import com.example.parentphoto.settings.Recipient
import java.io.File

// テストで位置を確かめるための目印
internal const val TAG_PHOTO = "photo"
internal const val TAG_RECIPIENTS = "recipients"

/**
 * 撮影した写真の確認(ds 3.4 Preview)。上に写真(残りの高さ全部、ただし画面の 30 % 以上)、
 * その下に送り先ボタンの一覧(スクロール)、一番下にやり直す。設定アイコンは写真の右上。
 */
@Composable
fun PreviewScreen(
    photoFile: File?,
    recipients: List<Recipient>,
    buttonSize: ButtonSize,
    outboxFullNoticeVisible: Boolean,
    onRecipient: (String) -> Unit,
    onRetake: () -> Unit,
    onSettingsLongPress: () -> Unit,
) {
    // 背景は全面、内容(写真・ボタン)はシステムバーの内側。写真の最小高さなどの割合も内側の領域で数える
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).fatherInsetsPadding()) {
        // 一覧の最大の高さ = 全体 - 写真の最小(30 %) - やり直すの領域 - 間隔
        val listMax: Dp = (maxHeight * 0.7f - buttonSize.dp.dp - 48.dp).coerceAtLeast(0.dp)
        val photoMin: Dp = maxHeight * 0.3f
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f).heightIn(min = photoMin)) {
                PhotoView(photoFile, Modifier.fillMaxSize().testTag(TAG_PHOTO))
                SettingsIcon(
                    tint = ICON_ON_LIGHT,
                    onLongPress = onSettingsLongPress,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            if (recipients.isEmpty()) {
                MessageText(
                    stringResource(R.string.no_recipients),
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = listMax).testTag(TAG_RECIPIENTS),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(recipients, key = { it.id }) { r ->
                        FatherFilledButton(
                            text = r.name,
                            size = buttonSize,
                            onClick = { onRecipient(r.id) },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            FatherOutlinedButton(
                text = stringResource(R.string.retry_button),
                size = buttonSize,
                onClick = onRetake,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        }
        // 満杯で送り先を受け付けなかった時の案内(3000 ms、Q65)
        if (outboxFullNoticeVisible) {
            OverlayMessage(stringResource(R.string.outbox_full_guidance), Modifier.align(Alignment.Center))
        }
    }
}

/** 取り消し猶予(ds 3.4 CancelWindow)。暗くした写真、「n秒後に送ります」、一番下に取り消す */
@Composable
fun CancelWindowScreen(
    photoFile: File?,
    secondsLeft: Int,
    buttonSize: ButtonSize,
    onCancel: () -> Unit,
) {
    DimmedPhotoLayout(photoFile, stringResource(R.string.cancel_window_label, secondsLeft)) {
        FatherFilledButton(
            text = stringResource(R.string.cancel_button),
            size = buttonSize,
            onClick = onCancel,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )
    }
}

/** 前面送信中(ds 3.4 Sending)。暗くした写真と「送信中...」だけ。ボタンは無い */
@Composable
fun SendingScreen(photoFile: File?) {
    DimmedPhotoLayout(photoFile, stringResource(R.string.sending)) {}
}

// Preview と同じ並びで写真を半分暗くし、その下に 36 sp のラベル、最下部に bottom
@Composable
private fun DimmedPhotoLayout(photoFile: File?, label: String, bottom: @Composable () -> Unit) {
    // 背景は全面、内容(写真・ボタン)はシステムバーの内側。写真の最小高さなどの割合も内側の領域で数える
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).fatherInsetsPadding()) {
        val photoMin: Dp = maxHeight * 0.3f
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f).heightIn(min = photoMin)) {
                PhotoView(photoFile, Modifier.fillMaxSize().testTag(TAG_PHOTO))
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)))
            }
            MessageText(label, Modifier.fillMaxWidth().padding(16.dp))
            bottom()
        }
    }
}
