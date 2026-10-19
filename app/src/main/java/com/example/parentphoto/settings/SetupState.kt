package com.example.parentphoto.settings

/** SetupRequired の判定(ds 8.3)。 */
object SetupState {
    /**
     * 送信元アドレスが不正、パスワード未設定または復号不能、ホストが空、
     * ポートが範囲外のいずれかなら true。送信者名と送り先は見ない。
     */
    fun isSetupRequired(
        settings: Settings,
        passwordPresent: Boolean,
        emailPattern: java.util.regex.Pattern = android.util.Patterns.EMAIL_ADDRESS,
    ): Boolean =
        Validators.validateEmail(settings.smtpUser, emailPattern) != null ||
            !passwordPresent ||
            settings.smtpHost.isBlank() ||
            !Validators.isValidPort(settings.smtpPort)
}
