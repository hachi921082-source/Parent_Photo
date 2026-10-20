package com.example.parentphoto.ui.settings

import android.util.Log
import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.parentphoto.ParentPhotoApp
import com.example.parentphoto.outbox.OutboxRepository
import com.example.parentphoto.settings.ButtonSize
import com.example.parentphoto.settings.Recipient
import com.example.parentphoto.settings.Settings
import com.example.parentphoto.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * SettingsViewModel を本物の部品(ParentPhotoApp の共有インスタンス)で組み立てる。
 * 設定・未送信キューは新しく作らず、アプリに1つだけあるものを使う。
 */
class SettingsViewModelFactory(private val app: ParentPhotoApp) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SettingsViewModel::class.java)) { "unknown ViewModel: $modelClass" }
        val outbox = app.outboxRepository
        val store = RepositorySettingsStore(app.settingsRepository)
        val vm = SettingsViewModel(
            store = store,
            resumer = SetupResumer { outbox.resumeAfterSetupFix() },
            recipientStore = store,
            recipientOutbox = RepositoryRecipientOutbox(outbox),
            emailPattern = Patterns.EMAIL_ADDRESS,
            log = { Log.w(TAG_SETTINGS, it) },
        )
        @Suppress("UNCHECKED_CAST")
        return vm as T
    }

    private companion object {
        const val TAG_SETTINGS = "ParentPhoto/Settings"
    }
}

/** SettingsRepository を設定画面用の窓口にする。暗号化・復号(Keystore)は IO で行う */
private class RepositorySettingsStore(private val repo: SettingsRepository) : SettingsScreenStore, RecipientStore {
    override val settings: Flow<Settings> = repo.settings

    override suspend fun isPasswordSaved(): Boolean = withContext(Dispatchers.IO) { repo.readPassword() != null }

    override suspend fun saveSmtp(senderName: String, user: String, host: String, port: Int, newPasswordOrNull: String?) =
        withContext(Dispatchers.IO) { repo.saveSmtp(senderName, user, host, port, newPasswordOrNull) }

    override suspend fun setButtonSize(size: ButtonSize) = repo.setButtonSize(size)

    override suspend fun addRecipient(name: String, email: String): Recipient? = repo.addRecipient(name, email)

    override suspend fun updateRecipient(id: String, name: String, email: String): Boolean =
        repo.updateRecipient(id, name, email)

    override suspend fun deleteRecipient(id: String) = repo.deleteRecipient(id)
}

/** OutboxRepository のうち送り先削除で使う部分 */
private class RepositoryRecipientOutbox(private val repo: OutboxRepository) : RecipientOutbox {
    override suspend fun countForRecipient(recipientId: String): Int = repo.countForRecipient(recipientId)

    override suspend fun onRecipientDeleted(recipientId: String) = repo.onRecipientDeleted(recipientId)
}
