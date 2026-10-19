package com.example.parentphoto.ui

import com.example.parentphoto.mail.ItemId
import com.example.parentphoto.settings.ButtonSize
import com.example.parentphoto.settings.Recipient
import java.io.File

/**
 * 父親向け画面の状態(ds 2.1)。画面は状態ごとに1つ。
 * 設定画面は状態ではなく別ルート(MainUiModel.settingsOpen)で、閉じると元の状態に戻る。
 */
sealed interface UiState {
    /** 設定の最初の読み込みと起動時の復旧が終わるまで。何も表示しない */
    data object Loading : UiState

    /** カメラ権限が無い。案内と「設定を開く」 */
    data object PermissionDenied : UiState

    /** 送信元アドレス・パスワード・ホスト・ポートのどれかが未設定または不正(ds 8.3) */
    data object SetupRequired : UiState

    /** カメラ映像とシャッター */
    data object Camera : UiState

    /** 待機中が5件。シャッター無効と案内 */
    data object OutboxFull : UiState

    /** シャッターを押してから preview.jpg ができるまで */
    data object Capturing : UiState

    /** 撮影済み写真の確認。file は縮小済みの cache/preview.jpg */
    data class Preview(val capturedAtMillis: Long, val file: File) : UiState

    /** 送り先を押した後の3秒の取り消し猶予(PLAN-3) */
    data class CancelWindow(val itemId: ItemId, val secondsLeft: Int) : UiState

    /** 前面送信中(最大60秒、PLAN-3) */
    data class Sending(val itemId: ItemId) : UiState

    /** 「送りました！」2秒(PLAN-3) */
    data object Sent : UiState

    /** 「あとで送ります」2秒(PLAN-3) */
    data object QueuedNotice : UiState

    /** 起動時・設定から戻った時・再開時の優先順位(ds 2.2)で置き換えてよい状態か */
    val isPrecedenceOwned: Boolean
        get() = this is Loading || this is SetupRequired || this is PermissionDenied ||
            this is Camera || this is OutboxFull

    /** システムの戻る操作を無視する状態か(ds 2.3、誤って写真を捨てないため) */
    val blocksBack: Boolean
        get() = this is Loading || this is Capturing || this is Preview || this is CancelWindow ||
            this is Sending || this is Sent || this is QueuedNotice

    /** 設定アイコンの3秒長押しで設定を開ける状態か(ds 2.3 / 3.3) */
    val allowsSettings: Boolean
        get() = this is Camera || this is OutboxFull || this is Preview ||
            this is SetupRequired || this is PermissionDenied
}

/**
 * 画面に渡す1つのまとまり。状態に加えて、状態をまたいで表示される値を持つ。
 * cameraPermissionRequest / notificationPermissionRequest が true の間、Activity がシステムダイアログを出す。
 */
data class MainUiModel(
    val state: UiState = UiState.Loading,
    val settingsOpen: Boolean = false,
    val captureFailureMessageVisible: Boolean = false,
    val unsentCount: Int = 0,
    val attentionCount: Int = 0,
    val recipients: List<Recipient> = emptyList(),
    val buttonSize: ButtonSize = ButtonSize.LARGE,
    val cameraPermissionRequest: Boolean = false,
    val notificationPermissionRequest: Boolean = false,
)
