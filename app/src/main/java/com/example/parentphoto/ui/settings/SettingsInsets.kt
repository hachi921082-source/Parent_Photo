package com.example.parentphoto.ui.settings

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import com.example.parentphoto.ui.LocalInsetsOverride

/** 設定画面の本文が避ける領域。システムバー・切り欠き・キーボード(テストでは LocalInsetsOverride で差し替え) */
@Composable
internal fun settingsInsets(): WindowInsets = LocalInsetsOverride.current ?: WindowInsets.safeDrawing

/** アプリバーが避ける領域。上端(ステータスバー・切り欠き)と左右 */
@Composable
internal fun settingsTopBarInsets(): WindowInsets =
    settingsInsets().only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
