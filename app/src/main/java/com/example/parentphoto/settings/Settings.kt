package com.example.parentphoto.settings

/** ボタンの大きさ(ds 8.1)。dpは父親向け画面のボタン一辺。 */
enum class ButtonSize(val dp: Int) {
    STANDARD(56),
    LARGE(96),
    XLARGE(128),
}

/**
 * 設定値のスナップショット(ds 8.1)。
 * アプリパスワードは含めない。復号値は SettingsRepository.readPassword() でのみ得る。
 * passwordPresent は暗号文が保存されているかだけを示す(復号可否は別)。
 */
data class Settings(
    val senderName: String = "",
    val smtpUser: String = "",
    val smtpHost: String = DEFAULT_HOST,
    val smtpPort: Int = DEFAULT_PORT,
    val passwordPresent: Boolean = false,
    val buttonSize: ButtonSize = ButtonSize.LARGE,
    val recipients: List<Recipient> = emptyList(),
    val notifPermissionAsked: Boolean = false,
) {
    companion object {
        const val DEFAULT_HOST = "smtp.gmail.com"
        const val DEFAULT_PORT = 465
    }
}
