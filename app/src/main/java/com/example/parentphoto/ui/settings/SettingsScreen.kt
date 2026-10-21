package com.example.parentphoto.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.parentphoto.R
import com.example.parentphoto.settings.ButtonSize
import com.example.parentphoto.settings.ValidationError
import kotlinx.coroutines.launch

/** 画面テスト用の目印(testTag)。 */
object SettingsTags {
    const val SCROLL = "settings_scroll"
    const val SAVE = "settings_save"
    fun field(f: FormField) = "settings_field_${f.name}"
}

/**
 * 隠し設定のメイン画面(ds 9 / 9.1、settings-screen PLAN-1)。アプリバー「設定」と戻る矢印、縦スクロールの1画面。
 * 端末のフォント倍率に従う(fontScale を固定しない、ds 3.2 / Q51)。標準の Material3 の大きさ。
 * 戻る(矢印・端末の戻る)は未保存の変更があれば確認ダイアログ、なければそのまま閉じる。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    onOpenUnsent: () -> Unit,
    onOpenLog: () -> Unit,
    onClose: () -> Unit,
) {
    val form by viewModel.form.collectAsState()
    val buttonSize by viewModel.buttonSize.collectAsState()
    val recipients by viewModel.recipients.collectAsState()
    val recipientDialog by viewModel.recipientDialog.collectAsState()
    val deleteConfirm by viewModel.deleteConfirm.collectAsState()
    val recipientFocus = rememberRecipientFocusRequesters()
    val testRecipient by viewModel.testRecipient.collectAsState()
    val savedSetupValid by viewModel.savedSetupValid.collectAsState()
    val testSend by viewModel.testSend.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusRequesters = remember { FormField.entries.associateWith { FocusRequester() } }
    val bringRequesters = remember { FormField.entries.associateWith { BringIntoViewRequester() } }
    var dirtyDialog by rememberSaveable { mutableStateOf(false) }
    val savedMessage = stringResource(R.string.snackbar_saved)
    val saveFailedMessage = stringResource(R.string.snackbar_save_failed)

    LaunchedEffect(viewModel) { viewModel.onOpened() }

    // 送り先ダイアログが閉じたら画面側のフォーカスも外す。外さないと、ダイアログで入力中に閉じたとき
    // フォーカスが送信者名の欄に戻り、キーボードが開いてしまう(PLAN-2 の実機確認で見つけた)
    val screenFocus = LocalFocusManager.current
    val screenKeyboard = LocalSoftwareKeyboardController.current
    val dialogOpen = recipientDialog != null
    LaunchedEffect(dialogOpen) {
        if (!dialogOpen) {
            screenFocus.clearFocus(force = true)
            screenKeyboard?.hide()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { e ->
            when (e) {
                // Snackbar は表示の間待つので、合図の受け取りを止めないよう別に動かす
                SettingsEvent.Saved -> launch {
                    snackbarHostState.showSnackbar(savedMessage, duration = SnackbarDuration.Short)
                }
                SettingsEvent.SaveFailed -> launch {
                    snackbarHostState.showSnackbar(saveFailedMessage, duration = SnackbarDuration.Short)
                }
                // ダイアログの欄は画面内に収まるのでフォーカスだけ移す
                is SettingsEvent.FocusRecipientField -> recipientFocus.getValue(e.field).requestFocus()
                is SettingsEvent.FocusField -> {
                    bringRequesters.getValue(e.field).bringIntoView()
                    focusRequesters.getValue(e.field).requestFocus()
                }
            }
        }
    }

    val back: () -> Unit = { if (form.dirty) dirtyDialog = true else onClose() }
    BackHandler(enabled = true, onBack = back)

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = settingsTopBarInsets(),
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // バー・切り欠き・キーボードの内側に収める(入力中の欄がキーボードに隠れないように)
        contentWindowInsets = settingsInsets(),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .testTag(SettingsTags.SCROLL)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            recipients?.let { list ->
                RecipientsSection(
                    recipients = list,
                    // ダイアログを開く前にフォーカスを外す。入力中の欄が残ったままだと、閉じたときに
                    // 送信者名の欄へフォーカスが戻ってキーボードが開く(レビュー指摘)
                    onAdd = {
                        screenFocus.clearFocus(force = true)
                        viewModel.onAddRecipient()
                    },
                    onEdit = {
                        screenFocus.clearFocus(force = true)
                        viewModel.onEditRecipient(it)
                    },
                    onDelete = {
                        screenFocus.clearFocus(force = true)
                        viewModel.onDeleteRecipient(it)
                    },
                )
            }
            if (form.loaded) {
                SmtpFormSection(
                    form = form,
                    viewModel = viewModel,
                    focusRequesters = focusRequesters,
                    bringRequesters = bringRequesters,
                )
            }
            SectionHeader(R.string.section_button_size)
            ButtonSizeGroup(selected = buttonSize, onSelect = viewModel::onButtonSizeSelected)
            recipients?.let { list ->
                TestSendSection(
                    recipients = list,
                    selected = testRecipient,
                    hintRes = TestSendLogic.hint(list.size, savedSetupValid, form.dirty),
                    canSend = TestSendLogic.canSend(list.size, savedSetupValid, form.dirty),
                    sending = testSend != TestSendPhase.Idle,
                    onSelect = viewModel::onTestRecipientSelected,
                    onSend = viewModel::onTestSend,
                )
            }
            Spacer(Modifier.heightIn(min = 8.dp))
            OutlinedButton(onClick = onOpenUnsent, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.link_unsent))
            }
            OutlinedButton(onClick = onOpenLog, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.link_send_log))
            }
        }
    }

    recipientDialog?.let { d ->
        RecipientDialog(
            state = d,
            focusRequesters = recipientFocus,
            onNameChange = viewModel::onRecipientNameChange,
            onEmailChange = viewModel::onRecipientEmailChange,
            onSave = viewModel::onRecipientDialogSave,
            onDismiss = viewModel::onRecipientDialogDismiss,
        )
    }
    deleteConfirm?.let { c ->
        DeleteRecipientDialog(state = c, onConfirm = viewModel::onDeleteConfirmed, onDismiss = viewModel::onDeleteDismiss)
    }

    when (val t = testSend) {
        TestSendPhase.Idle -> Unit
        TestSendPhase.Sending -> TestSendProgressDialog()
        is TestSendPhase.Done -> TestSendResultDialog(t.result, onOk = viewModel::onTestResultOk)
    }

    if (dirtyDialog) {
        // キャンセルボタンは置かない。外側タップ・戻るは「戻る」(とどまる)と同じ(Q61)
        AlertDialog(
            onDismissRequest = { dirtyDialog = false },
            text = { Text(stringResource(R.string.dirty_message)) },
            confirmButton = {
                TextButton(onClick = {
                    dirtyDialog = false
                    onClose()
                }) { Text(stringResource(R.string.button_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { dirtyDialog = false }) { Text(stringResource(R.string.button_stay)) }
            },
        )
    }
}

@Composable
private fun SectionHeader(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp),
    )
}

/** 送信者名とメール設定(ds 9.1 2-3)。保存ボタンは両方で1つ。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmtpFormSection(
    form: SmtpFormState,
    viewModel: SettingsViewModel,
    focusRequesters: Map<FormField, FocusRequester>,
    bringRequesters: Map<FormField, BringIntoViewRequester>,
) {
    val focusManager = LocalFocusManager.current
    // 「次へ」は画面順の次の欄へ、最後(ポート)の「完了」はキーボードを閉じる
    fun actionsFor(field: FormField) = KeyboardActions(
        onNext = {
            SmtpFormLogic.nextField(field, form.passwordMode)?.let { focusRequesters.getValue(it).requestFocus() }
        },
        onDone = { focusManager.clearFocus() },
    )

    fun Modifier.fieldModifiers(field: FormField): Modifier = this
        .bringIntoViewRequester(bringRequesters.getValue(field))
        .focusRequester(focusRequesters.getValue(field))
        .testTag(SettingsTags.field(field))

    SectionHeader(R.string.section_sender_name)
    FormTextField(
        value = form.values.senderName,
        onValueChange = viewModel::onSenderNameChange,
        label = R.string.label_sender_name,
        field = FormField.SENDER_NAME,
        error = form.errors[FormField.SENDER_NAME],
        actions = actionsFor(FormField.SENDER_NAME),
        modifier = Modifier.fillMaxWidth().fieldModifiers(FormField.SENDER_NAME),
    )

    SectionHeader(R.string.section_mail)
    FormTextField(
        value = form.values.smtpUser,
        onValueChange = viewModel::onSmtpUserChange,
        label = R.string.label_smtp_user,
        field = FormField.SMTP_USER,
        error = form.errors[FormField.SMTP_USER],
        actions = actionsFor(FormField.SMTP_USER),
        modifier = Modifier.fillMaxWidth().fieldModifiers(FormField.SMTP_USER),
    )
    PasswordRow(
        form = form,
        viewModel = viewModel,
        actions = actionsFor(FormField.PASSWORD),
        fieldModifier = Modifier.fieldModifiers(FormField.PASSWORD),
    )
    FormTextField(
        value = form.values.host,
        onValueChange = viewModel::onHostChange,
        label = R.string.label_smtp_host,
        field = FormField.HOST,
        error = form.errors[FormField.HOST],
        actions = actionsFor(FormField.HOST),
        modifier = Modifier.fillMaxWidth().fieldModifiers(FormField.HOST),
    )
    FormTextField(
        value = form.values.port,
        onValueChange = viewModel::onPortChange,
        label = R.string.label_smtp_port,
        field = FormField.PORT,
        error = form.errors[FormField.PORT],
        actions = actionsFor(FormField.PORT),
        modifier = Modifier.fillMaxWidth().fieldModifiers(FormField.PORT),
    )
    // 未保存の変更があれば(正しいかどうかに関係なく)押せる。検証は押したとき(Q53)
    Button(
        onClick = {
            // 入力中の欄のフォーカスを外してから保存する(欄の表示が変わった拍子に別の欄へ移らないように)
            focusManager.clearFocus(force = true)
            viewModel.onSave()
        },
        enabled = form.dirty,
        modifier = Modifier.fillMaxWidth().testTag(SettingsTags.SAVE),
    ) { Text(stringResource(R.string.button_save)) }
}

@Composable
private fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes label: Int,
    field: FormField,
    error: ValidationError?,
    actions: KeyboardActions,
    modifier: Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(stringResource(label)) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it.messageRes)) } },
        keyboardOptions = SmtpFormLogic.keyboardOptions(field),
        keyboardActions = actions,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
    )
}

/**
 * アプリパスワード欄(ds 9.2)。保存済みは●表示(編集不可)と「変更」、変更中は空の伏せ字欄と「キャンセル」。
 * 保存済みの値は出さない。Compose 1.7 の入力欄は明示的に登録しない限り Android の自動入力に出ないので、
 * この欄は自動入力の対象にならない(importantForAutofill = no と同じ扱い、ds 9.2 / Q58)。
 */
@Composable
private fun PasswordRow(
    form: SmtpFormState,
    viewModel: SettingsViewModel,
    actions: KeyboardActions,
    fieldModifier: Modifier,
) {
    val focusManager = LocalFocusManager.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (form.passwordMode == PasswordMode.SAVED) {
            OutlinedTextField(
                value = stringResource(R.string.password_saved_mask),
                onValueChange = {},
                enabled = false,
                singleLine = true,
                label = { Text(stringResource(R.string.label_app_password)) },
                modifier = Modifier.weight(1f).then(fieldModifier),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                focusManager.clearFocus(force = true)
                viewModel.onChangePassword()
            }) { Text(stringResource(R.string.button_change_password)) }
        } else {
            // 表示/非表示は画面だけの状態。欄の状態が変わったら伏せ字に戻す
            var shown by rememberSaveable(form.passwordMode) { mutableStateOf(false) }
            FormTextField(
                value = form.password,
                onValueChange = viewModel::onPasswordChange,
                label = R.string.label_app_password,
                field = FormField.PASSWORD,
                error = form.errors[FormField.PASSWORD],
                actions = actions,
                modifier = Modifier.weight(1f).then(fieldModifier),
                visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { shown = !shown }) {
                        Icon(
                            painterResource(if (shown) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                            contentDescription = stringResource(
                                if (shown) R.string.cd_hide_password else R.string.cd_show_password,
                            ),
                        )
                    }
                },
            )
            if (form.passwordMode == PasswordMode.CHANGING) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    focusManager.clearFocus(force = true)
                    viewModel.onCancelPasswordChange()
                }) {
                    Text(stringResource(R.string.button_settings_cancel))
                }
            }
        }
    }
}

/** ボタンの大きさ(ds 9.1 4)。選んだ時点で保存し、フォームの未保存判定には含めない。 */
@Composable
private fun ButtonSizeGroup(selected: ButtonSize?, onSelect: (ButtonSize) -> Unit) {
    Column(Modifier.selectableGroup()) {
        ButtonSize.entries.forEach { size ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = size == selected, onClick = { onSelect(size) }, role = Role.RadioButton),
            ) {
                RadioButton(selected = size == selected, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(size.labelRes()), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@StringRes
private fun ButtonSize.labelRes(): Int = when (this) {
    ButtonSize.STANDARD -> R.string.button_size_standard
    ButtonSize.LARGE -> R.string.button_size_large
    ButtonSize.XLARGE -> R.string.button_size_xlarge
}
