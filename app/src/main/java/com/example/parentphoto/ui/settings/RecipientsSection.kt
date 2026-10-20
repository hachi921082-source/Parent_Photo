package com.example.parentphoto.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.parentphoto.R
import com.example.parentphoto.settings.Recipient
import com.example.parentphoto.util.Constants

/** 送り先まわりの画面テスト用の目印。 */
object RecipientTags {
    const val ADD = "recipient_add"
    fun row(id: String) = "recipient_row_$id"
    fun field(f: RecipientField) = "recipient_field_${f.name}"
}

/**
 * 送り先のセクション(ds 9.1 1、settings-screen PLAN-2)。見出し「送り先 n/6」、登録順の行、その下に「追加」。
 * 行は表示名と、その下に1行のアドレス(長ければ省略)、編集・削除のアイコン。変更は即時保存(保存ボタンなし)。
 */
@Composable
fun RecipientsSection(
    recipients: List<Recipient>,
    onAdd: () -> Unit,
    onEdit: (Recipient) -> Unit,
    onDelete: (Recipient) -> Unit,
) {
    Text(
        text = stringResource(R.string.section_recipients, recipients.size, Constants.RECIPIENT_CAP),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp),
    )
    recipients.forEach { r ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(RecipientTags.row(r.id)),
        ) {
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    r.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { onEdit(r) }) {
                Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.cd_edit))
            }
            IconButton(onClick = { onDelete(r) }) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.cd_delete))
            }
        }
    }
    OutlinedButton(
        onClick = onAdd,
        enabled = recipients.size < Constants.RECIPIENT_CAP,
        modifier = Modifier.fillMaxWidth().testTag(RecipientTags.ADD),
    ) { Text(stringResource(R.string.button_add_recipient)) }
}

/**
 * 送り先の追加・編集ダイアログ。「保存」は常に押せ、押したときに検証する。エラーなら欄の下に出して開いたまま。
 * 外側タップ・戻る・キャンセルは保存せず確認なしで閉じる(ds 9 / Q58)。
 */
@Composable
fun RecipientDialog(
    state: RecipientDialogState,
    focusRequesters: Map<RecipientField, FocusRequester>,
    onNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (state.editId == null) R.string.dialog_add_recipient else R.string.dialog_edit_recipient))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RecipientTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    label = R.string.label_recipient_name,
                    field = RecipientField.NAME,
                    state = state,
                    focusRequester = focusRequesters.getValue(RecipientField.NAME),
                    actions = KeyboardActions(onNext = { focusRequesters.getValue(RecipientField.EMAIL).requestFocus() }),
                )
                RecipientTextField(
                    value = state.email,
                    onValueChange = onEmailChange,
                    label = R.string.label_recipient_email,
                    field = RecipientField.EMAIL,
                    state = state,
                    focusRequester = focusRequesters.getValue(RecipientField.EMAIL),
                    actions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                )
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text(stringResource(R.string.button_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_settings_cancel)) } },
    )
}

@Composable
private fun RecipientTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: Int,
    field: RecipientField,
    state: RecipientDialogState,
    focusRequester: FocusRequester,
    actions: KeyboardActions,
) {
    val error = state.errors[field]
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it.messageRes)) } },
        keyboardOptions = RecipientFormLogic.keyboardOptions(field),
        keyboardActions = actions,
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag(RecipientTags.field(field)),
    )
}

/** 送り先削除の確認。未送信があれば枚数と、写真が送られなくなることを伝える。外側タップ・戻るはキャンセル。 */
@Composable
fun DeleteRecipientDialog(state: DeleteConfirmState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Text(
                if (state.queuedCount > 0) {
                    stringResource(R.string.confirm_delete_recipient_queued, state.queuedCount)
                } else {
                    stringResource(R.string.confirm_delete_recipient)
                },
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.button_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_settings_cancel)) } },
    )
}

/** 送り先ダイアログの欄のフォーカス先。画面側で1回だけ作る。 */
@Composable
fun rememberRecipientFocusRequesters(): Map<RecipientField, FocusRequester> =
    remember { RecipientField.entries.associateWith { FocusRequester() } }
