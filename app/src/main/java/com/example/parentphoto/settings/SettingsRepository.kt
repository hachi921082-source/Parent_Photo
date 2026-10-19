package com.example.parentphoto.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.parentphoto.util.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.UUID

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 設定の保存先(ds 8.1)。DataStore Preferences `settings` を包む。
 * 送り先の書き込みは即時保存。saveSmtp は1回の edit でまとめて保存する。
 * パスワード平文は Settings フローに含めず、readPassword() でのみ取り出す。
 */
class SettingsRepository(
    private val store: DataStore<Preferences>,
    private val crypto: PasswordCrypto = PasswordCrypto(),
) {
    /** 設定の流れ。最初の発行がLoading終了の合図。 */
    val settings: Flow<Settings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }

    /** 送信者名とSMTP設定を一括保存する。newPasswordOrNull が null なら既存の暗号文を保持する。 */
    suspend fun saveSmtp(
        senderName: String,
        user: String,
        host: String,
        port: Int,
        newPasswordOrNull: String?,
    ) {
        // 暗号化は edit の外で行う(鍵生成が重いため)
        val cipher = newPasswordOrNull?.let { crypto.encrypt(Validators.normalizePassword(it)) }
        store.edit { p ->
            p[K_SENDER] = Validators.sanitizeSenderName(senderName)
            p[K_USER] = user.trim()
            p[K_HOST] = host.trim()
            p[K_PORT] = port
            if (cipher != null) p[K_CIPHER] = cipher
        }
    }

    /** 復号したパスワード。未設定または復号不能なら null。ログに出さないこと。 */
    suspend fun readPassword(): String? {
        val cipher = store.data.first()[K_CIPHER].orEmpty()
        return crypto.decrypt(cipher)
    }

    /** SetupRequired かどうか(ds 8.3)。 */
    suspend fun isSetupRequired(): Boolean =
        SetupState.isSetupRequired(settings.first(), readPassword() != null)

    suspend fun setButtonSize(size: ButtonSize) {
        store.edit { it[K_BUTTON] = size.name }
    }

    /** 送り先を末尾に追加する。上限(6件)なら追加せず null。 */
    suspend fun addRecipient(name: String, email: String): Recipient? {
        var added: Recipient? = null
        store.edit { p ->
            val list = RecipientJson.decode(p[K_RECIPIENTS] ?: "[]")
            if (list.size < Constants.RECIPIENT_CAP) {
                val r = Recipient(UUID.randomUUID().toString(), name.trim(), email.trim())
                p[K_RECIPIENTS] = RecipientJson.encode(list + r)
                added = r
            }
        }
        return added
    }

    /** 送り先を更新する(並び位置は維持)。該当idがなければ false。 */
    suspend fun updateRecipient(id: String, name: String, email: String): Boolean {
        var found = false
        store.edit { p ->
            val list = RecipientJson.decode(p[K_RECIPIENTS] ?: "[]")
            if (list.any { it.id == id }) {
                found = true
                p[K_RECIPIENTS] = RecipientJson.encode(
                    list.map { if (it.id == id) it.copy(name = name.trim(), email = email.trim()) else it },
                )
            }
        }
        return found
    }

    suspend fun deleteRecipient(id: String) {
        store.edit { p ->
            val list = RecipientJson.decode(p[K_RECIPIENTS] ?: "[]")
            p[K_RECIPIENTS] = RecipientJson.encode(list.filterNot { it.id == id })
        }
    }

    suspend fun markNotifPermissionAsked() {
        store.edit { it[K_NOTIF_ASKED] = true }
    }

    private fun Preferences.toSettings() = Settings(
        senderName = this[K_SENDER] ?: "",
        smtpUser = this[K_USER] ?: "",
        smtpHost = this[K_HOST] ?: Settings.DEFAULT_HOST,
        smtpPort = this[K_PORT] ?: Settings.DEFAULT_PORT,
        passwordPresent = !this[K_CIPHER].isNullOrEmpty(),
        buttonSize = this[K_BUTTON]?.let { n -> ButtonSize.values().firstOrNull { it.name == n } }
            ?: ButtonSize.LARGE,
        recipients = RecipientJson.decode(this[K_RECIPIENTS] ?: "[]"),
        notifPermissionAsked = this[K_NOTIF_ASKED] ?: false,
    )

    companion object {
        private val K_SENDER = stringPreferencesKey("sender_name")
        private val K_USER = stringPreferencesKey("smtp_user")
        private val K_HOST = stringPreferencesKey("smtp_host")
        private val K_PORT = intPreferencesKey("smtp_port")
        private val K_CIPHER = stringPreferencesKey("password_cipher")
        private val K_BUTTON = stringPreferencesKey("button_size")
        private val K_RECIPIENTS = stringPreferencesKey("recipients_json")
        private val K_NOTIF_ASKED = booleanPreferencesKey("notif_permission_asked")

        /** アプリ共通のインスタンスを作る(DataStoreはコンテキスト単位のシングルトン)。 */
        fun create(context: Context) = SettingsRepository(context.applicationContext.settingsDataStore)
    }
}
