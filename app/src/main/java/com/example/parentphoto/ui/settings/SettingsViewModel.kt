package com.example.parentphoto.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.parentphoto.settings.ButtonSize
import com.example.parentphoto.settings.Recipient
import com.example.parentphoto.settings.Settings
import com.example.parentphoto.settings.Validators
import com.example.parentphoto.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

/** 設定画面が使う保存先の窓口。本物は SettingsRepository(SettingsViewModelFactory で包む)。 */
interface SettingsScreenStore {
    val settings: Flow<Settings>

    /** 復号できるパスワードが保存されているか。平文は返さない。 */
    suspend fun isPasswordSaved(): Boolean

    suspend fun saveSmtp(senderName: String, user: String, host: String, port: Int, newPasswordOrNull: String?)

    suspend fun setButtonSize(size: ButtonSize)
}

/** メール設定を直した後の再開(ds 9.4 / 7.5)。本物は OutboxRepository.resumeAfterSetupFix。 */
fun interface SetupResumer {
    suspend fun resumeAfterSetupFix()
}

/** 画面への一度きりの合図。 */
sealed interface SettingsEvent {
    /** 保存に成功した(Snackbar「保存しました」)。 */
    data object Saved : SettingsEvent

    /** 保存で例外が起きた(Snackbar「保存できませんでした」、Q70)。入力はそのまま残す。 */
    data object SaveFailed : SettingsEvent

    /** 最初のエラー欄へスクロールしてフォーカスする。 */
    data class FocusField(val field: FormField) : SettingsEvent

    /** 送り先ダイアログの最初のエラー欄へフォーカスする。 */
    data class FocusRecipientField(val field: RecipientField) : SettingsEvent
}

/**
 * 隠し設定画面の ViewModel(settings-screen PLAN-1、送り先は PLAN-2)。
 * Activity 単位で1つ持ち、副画面への移動や画面の作り直しをまたいで未保存の入力を保つ。
 * 設定を閉じたら onClosed で入力を捨て、次に開いたとき onOpened で保存済みの値から読み直す。
 * パスワードの平文・メールアドレスはログに出さない。
 */
class SettingsViewModel(
    private val store: SettingsScreenStore,
    private val resumer: SetupResumer,
    private val recipientStore: RecipientStore,
    private val recipientOutbox: RecipientOutbox,
    private val emailPattern: Pattern,
    private val log: (String) -> Unit,
) : ViewModel() {

    private val _form = MutableStateFlow(SmtpFormState())
    val form: StateFlow<SmtpFormState> = _form.asStateFlow()

    /** ボタンの大きさ。フォームとは別に保存済みの値をそのまま映す(即時保存、ds 9.1 4)。読み込み前は null。 */
    val buttonSize: StateFlow<ButtonSize?> = store.settings
        .map { it.buttonSize }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 送り先の一覧(登録順)。保存済みの値をそのまま映す。読み込み前は null。 */
    val recipients: StateFlow<List<Recipient>?> = store.settings
        .map { it.recipients }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _recipientDialog = MutableStateFlow<RecipientDialogState?>(null)
    /** 送り先の追加・編集ダイアログ。null なら閉じている。 */
    val recipientDialog: StateFlow<RecipientDialogState?> = _recipientDialog.asStateFlow()

    private val _deleteConfirm = MutableStateFlow<DeleteConfirmState?>(null)
    /** 送り先削除の確認ダイアログ。null なら閉じている。 */
    val deleteConfirm: StateFlow<DeleteConfirmState?> = _deleteConfirm.asStateFlow()

    private var recipientSaving = false
    private var deleteCounting = false

    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = _events.receiveAsFlow()

    private var loadJob: Job? = null
    private var saving = false

    // 設定を開いている回の番号。閉じた後に終わった保存が、次の回のフォームを書き換えないようにする
    private var session = 0

    /** 設定を開いた。読み込み済み(画面の作り直しなど)なら何もしない。 */
    fun onOpened() {
        if (_form.value.loaded || loadJob?.isActive == true) return
        val mySession = session
        loadJob = viewModelScope.launch {
            val s = store.settings.first()
            val saved = store.isPasswordSaved()
            if (mySession == session) _form.value = SmtpFormLogic.fromSettings(s, saved)
        }
    }

    /** 設定を閉じた。未保存の入力と、届いていない合図を捨てる。 */
    fun onClosed() {
        session++
        loadJob?.cancel()
        loadJob = null
        saving = false
        recipientSaving = false
        deleteCounting = false
        _form.value = SmtpFormState()
        _recipientDialog.value = null
        _deleteConfirm.value = null
        while (_events.tryReceive().isSuccess) Unit
    }

    fun onSenderNameChange(value: String) =
        edit(FormField.SENDER_NAME) { it.copy(values = it.values.copy(senderName = SmtpFormLogic.limitSenderName(value))) }

    fun onSmtpUserChange(value: String) =
        edit(FormField.SMTP_USER) { it.copy(values = it.values.copy(smtpUser = SmtpFormLogic.limitEmail(value))) }

    /** 空白(スペース・改行)は入力のたびに取り除く(ds 9.2)。 */
    fun onPasswordChange(value: String) =
        edit(FormField.PASSWORD) { it.copy(password = Validators.normalizePassword(value)) }

    fun onHostChange(value: String) =
        edit(FormField.HOST) { it.copy(values = it.values.copy(host = SmtpFormLogic.limitHost(value))) }

    fun onPortChange(value: String) =
        edit(FormField.PORT) { it.copy(values = it.values.copy(port = value)) }

    /** 「変更」: 保存済みパスワードを空の入力欄に切り替える。 */
    fun onChangePassword() {
        _form.update {
            if (it.passwordMode != PasswordMode.SAVED) it
            else it.copy(passwordMode = PasswordMode.CHANGING, password = "")
        }
    }

    /** 「キャンセル」: 保存済みパスワードの状態に戻す。 */
    fun onCancelPasswordChange() {
        _form.update {
            if (it.passwordMode != PasswordMode.CHANGING) it
            else it.copy(passwordMode = PasswordMode.SAVED, password = "", errors = it.errors - FormField.PASSWORD)
        }
    }

    /**
     * 「保存」(ds 9.1 3 / 9.3 / 9.4)。全欄を検証し、エラーがあれば全部出して最初の欄へ移り、何も保存しない。
     * エラーがなければ保存し、「保存しました」を出してから要確認の再開を呼ぶ。
     */
    fun onSave() {
        val state = _form.value
        if (!state.loaded || !state.dirty || saving) return
        val errors = SmtpFormLogic.validate(state, emailPattern)
        if (errors.isNotEmpty()) {
            _form.value = state.copy(errors = errors)
            SmtpFormLogic.firstError(errors)?.let { _events.trySend(SettingsEvent.FocusField(it)) }
            return
        }
        saving = true
        val mySession = session
        val saved = SmtpFormLogic.normalized(state.values)
        viewModelScope.launch {
            try {
                store.saveSmtp(
                    senderName = saved.senderName,
                    user = saved.smtpUser,
                    host = saved.host,
                    port = saved.port.toInt(),
                    newPasswordOrNull = SmtpFormLogic.newPasswordOrNull(state),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 保存できなかったときは入力をそのまま残し「保存できませんでした」を出す(Q70)
                log("saveSmtp failed: ${e.javaClass.simpleName}")
                if (mySession == session) {
                    saving = false
                    _events.trySend(SettingsEvent.SaveFailed)
                }
                return@launch
            }
            if (mySession == session) {
                saving = false
                // 保存中に入力された分は残し、保存した値を新しい基準にする
                _form.update { cur ->
                    val untouched = cur.values == state.values
                    cur.copy(
                        baseline = saved,
                        values = if (untouched) saved else cur.values,
                        passwordMode = PasswordMode.SAVED,
                        password = "",
                        errors = emptyMap(),
                    )
                }
                _events.trySend(SettingsEvent.Saved)
            }
            try {
                resumer.resumeAfterSetupFix()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("resumeAfterSetupFix failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** ボタンの大きさはフォームとは別に、選んだ時点で保存する。 */
    fun onButtonSizeSelected(size: ButtonSize) {
        viewModelScope.launch {
            try {
                store.setButtonSize(size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("setButtonSize failed: ${e.javaClass.simpleName}")
            }
        }
    }

    // ---- 送り先(PLAN-2) ----

    /** 「追加」。6件に達していれば開かない(ボタンも無効)。 */
    fun onAddRecipient() {
        val list = recipients.value ?: return
        if (list.size >= Constants.RECIPIENT_CAP || _recipientDialog.value != null) return
        _recipientDialog.value = RecipientFormLogic.forAdd()
    }

    fun onEditRecipient(r: Recipient) {
        if (_recipientDialog.value != null) return
        _recipientDialog.value = RecipientFormLogic.forEdit(r)
    }

    fun onRecipientNameChange(value: String) {
        _recipientDialog.update { it?.copy(name = value, errors = it.errors - RecipientField.NAME) }
    }

    fun onRecipientEmailChange(value: String) {
        _recipientDialog.update {
            it?.copy(email = RecipientFormLogic.limitEmail(value), errors = it.errors - RecipientField.EMAIL)
        }
    }

    /** 外側タップ・戻る・キャンセル: 保存せず確認なしで閉じる。 */
    fun onRecipientDialogDismiss() {
        if (!recipientSaving) _recipientDialog.value = null
    }

    /**
     * ダイアログの「保存」(常に押せる)。エラーがあれば欄の下に出してダイアログを開いたままにし、最初の欄へ移る。
     * なければ即時保存して閉じる(成功の合図は出さない、Q57)。
     */
    fun onRecipientDialogSave() {
        val d = _recipientDialog.value ?: return
        if (recipientSaving) return
        val errors = RecipientFormLogic.validate(d, emailPattern)
        if (errors.isNotEmpty()) {
            _recipientDialog.value = d.copy(errors = errors)
            RecipientFormLogic.firstError(errors)?.let { _events.trySend(SettingsEvent.FocusRecipientField(it)) }
            return
        }
        recipientSaving = true
        val mySession = session
        viewModelScope.launch {
            try {
                val name = d.name.trim()
                val email = d.email.trim()
                // 上限超過(追加)や削除済み(編集)で保存されなかったときも閉じる。一覧は保存済みの値を映す
                if (d.editId == null) recipientStore.addRecipient(name, email)
                else recipientStore.updateRecipient(d.editId, name, email)
                if (mySession == session) _recipientDialog.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 入力を残したまま「保存できませんでした」(Q70 と同じ扱い)
                log("saveRecipient failed: ${e.javaClass.simpleName}")
                if (mySession == session) _events.trySend(SettingsEvent.SaveFailed)
            } finally {
                if (mySession == session) recipientSaving = false
            }
        }
    }

    /** 削除アイコン: その送り先の未送信の枚数を数えてから確認ダイアログを出す。 */
    fun onDeleteRecipient(r: Recipient) {
        if (_deleteConfirm.value != null || deleteCounting) return
        deleteCounting = true
        val mySession = session
        viewModelScope.launch {
            val count = try {
                recipientOutbox.countForRecipient(r.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("countForRecipient failed: ${e.javaClass.simpleName}")
                0
            }
            if (mySession == session) {
                deleteCounting = false
                _deleteConfirm.value = DeleteConfirmState(r.id, count)
            }
        }
    }

    /** 確認ダイアログの「キャンセル」・外側タップ・戻る。 */
    fun onDeleteDismiss() {
        _deleteConfirm.value = null
    }

    /** 確認ダイアログの「削除」: 送り先を消し、その送り先の待機中の写真を要確認(宛先削除)へ移す(ds 7.5)。 */
    fun onDeleteConfirmed() {
        val c = _deleteConfirm.value ?: return
        _deleteConfirm.value = null
        viewModelScope.launch {
            // 送り先だけ消えて写真が待機中に残らないよう、途中で設定を閉じても最後まで行う
            withContext(NonCancellable) {
                try {
                    recipientStore.deleteRecipient(c.recipientId)
                } catch (e: Exception) {
                    log("deleteRecipient failed: ${e.javaClass.simpleName}")
                    _events.trySend(SettingsEvent.SaveFailed)
                    return@withContext
                }
                try {
                    recipientOutbox.onRecipientDeleted(c.recipientId)
                } catch (e: Exception) {
                    log("onRecipientDeleted failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    // 入力を反映し、その欄のエラー表示を消す(次の保存で検証し直す)
    private fun edit(field: FormField, change: (SmtpFormState) -> SmtpFormState) {
        _form.update { if (!it.loaded) it else change(it).let { n -> n.copy(errors = n.errors - field) } }
    }
}
