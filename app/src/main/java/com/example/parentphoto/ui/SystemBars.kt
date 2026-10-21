package com.example.parentphoto.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** 画面の状態から、システムバーのアイコンを暗くするか(= 明るい背景か)を決める。カメラ映像(黒)の画面だけ明るいアイコン */
internal fun darkSystemBarIcons(model: MainUiModel): Boolean {
    if (model.settingsOpen) return true
    return when (model.state) {
        UiState.Camera, UiState.OutboxFull, UiState.Capturing -> false
        else -> true
    }
}

/**
 * ステータスバーとナビゲーションバーのアイコンの色を背景に合わせる。
 * 明るい背景なら暗いアイコン、カメラ映像の上なら明るいアイコン。Activity 以外(テスト用の窓など)では何もしない。
 */
@Composable
internal fun SystemBarIcons(darkIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = darkIcons
        controller.isAppearanceLightNavigationBars = darkIcons
    }
}
