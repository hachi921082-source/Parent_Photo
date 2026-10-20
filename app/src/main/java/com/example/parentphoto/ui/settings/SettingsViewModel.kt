package com.example.parentphoto.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.parentphoto.settings.ButtonSize
import com.example.parentphoto.settings.Settings
import com.example.parentphoto.settings.Validators
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

    /** 最初のエラー欄へスクロールしてフォーカスする。 */
    data class FocusField(val field: FormField) : SettingsEvent
}

/**
 * 隠し設定画面の ViewModel(settings-screen PLAN-1)。
 * Activity 単位で1つ持ち、副画面への移動や画面の作り直しをまたいで未保存の入力を保つ。
 * 設定を閉じたら onClosed で入力を捨て、次に開いたとき onOpened で保存済みの値から読み直す。
 * パスワードの平文・メールアドレスはログに出さない。
 */
class SettingsViewModel(
    private val store: SettingsScreenStore,
    private val resumer: SetupResumer,
    private val emailPattern: Pattern,
    private val log: (String) -> Unit,
) : ViewModel() {

    private val _form = MutableStateFlow(SmtpFormState())
    val form: StateFlow<SmtpFormState> = _form.asStateFlow()

    /** ボタンの大きさ。フォームとは別に保存済みの値をそのまま映す(即時保存、ds 9.1 4)。読み込み前は null。 */
    val buttonSize: StateFlow<ButtonSize?> = store.settings
        .map { it.buttonSize }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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
        _form.value = SmtpFormState()
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
                // 保存できなかったときは入力をそのまま残す(失敗時の文言は仕様にない)
                log("saveSmtp failed: ${e.javaClass.simpleName}")
                if (mySession == session) saving = false
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

    // 入力を反映し、その欄のエラー表示を消す(次の保存で検証し直す)
    private fun edit(field: FormField, change: (SmtpFormState) -> SmtpFormState) {
        _form.update { if (!it.loaded) it else change(it).let { n -> n.copy(errors = n.errors - field) } }
    }
}
