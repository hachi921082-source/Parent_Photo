package com.example.parentphoto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.parentphoto.R
import com.example.parentphoto.settings.ButtonSize

/*
 * 文字だけの父親向け画面(ds 3.4)。Loading / SetupRequired / PermissionDenied / Sent / QueuedNotice。
 */

/** 起動直後。背景色だけで何も出さない(時間切れなし) */
@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
}

/** 設定が必要。ボタンは無く、右上の設定アイコン(長押し)だけ */
@Composable
fun SetupRequiredScreen(onSettingsLongPress: () -> Unit) {
    val c = MaterialTheme.colorScheme
    CenteredMessageScreen(stringResource(R.string.setup_required), c.background, c.onBackground) {
        Box(Modifier.fillMaxSize()) {
            SettingsIcon(ICON_ON_LIGHT, onSettingsLongPress, Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
    }
}

/** カメラ権限が無い。案内の下に「設定を開く」(アプリ情報の画面へ) */
@Composable
fun PermissionDeniedScreen(
    buttonSize: ButtonSize,
    onOpenSettings: () -> Unit,
    onSettingsLongPress: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.Center).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MessageText(stringResource(R.string.permission_denied_guidance))
            FatherFilledButton(stringResource(R.string.open_settings_button), buttonSize, onOpenSettings)
        }
        SettingsIcon(ICON_ON_LIGHT, onSettingsLongPress, Modifier.align(Alignment.TopEnd).padding(4.dp))
    }
}

/** 「送りました！」(primaryContainer の全面) */
@Composable
fun SentScreen() {
    val c = MaterialTheme.colorScheme
    CenteredMessageScreen(stringResource(R.string.sent), c.primaryContainer, c.onPrimaryContainer)
}

/** 「あとで送ります」(背景色の全面) */
@Composable
fun QueuedNoticeScreen() {
    val c = MaterialTheme.colorScheme
    CenteredMessageScreen(stringResource(R.string.queued_notice), c.background, c.onBackground)
}
