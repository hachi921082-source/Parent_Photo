package com.example.parentphoto.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.parentphoto.R
import com.example.parentphoto.settings.Recipient

/** テスト送信まわりの画面テスト用の目印。 */
object TestSendTags {
    const val DROPDOWN = "test_send_dropdown"
    const val BUTTON = "test_send_button"
    const val HINT = "test_send_hint"
    fun option(id: String) = "test_send_option_$id"
}

// 案内文の色(ds 9.5)。設定画面の他の文字と同じく端末のフォント倍率に従う
private val HintColor = Color(0xFF49454F)

/**
 * テスト送信のセクション(ds 9.5、settings-screen PLAN-3)。送り先の選択、「テスト送信」ボタン、その下の案内(12sp・灰色)。
 * 案内が出ているときはボタンを押せない。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestSendSection(
    recipients: List<Recipient>,
    selected: Recipient?,
    hintRes: Int?,
    sending: Boolean,
    onSelect: (Recipient) -> Unit,
    onSend: () -> Unit,
) {
    Text(
        text = stringResource(R.string.section_test_send),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp),
    )
    var expanded by rememberSaveable { mutableStateOf(false) }
    val canChoose = recipients.isNotEmpty() && !sending
    ExposedDropdownMenuBox(
        expanded = expanded && canChoose,
        onExpandedChange = { if (canChoose) expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.name.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = canChoose,
            singleLine = true,
            label = { Text(stringResource(R.string.label_test_recipient)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && canChoose) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = canChoose)
                .testTag(TestSendTags.DROPDOWN),
        )
        ExposedDropdownMenu(expanded = expanded && canChoose, onDismissRequest = { expanded = false }) {
            recipients.forEach { r ->
                DropdownMenuItem(
                    text = { Text(r.name) },
                    onClick = {
                        onSelect(r)
                        expanded = false
                    },
                    modifier = Modifier.testTag(TestSendTags.option(r.id)),
                )
            }
        }
    }
    Button(
        onClick = onSend,
        enabled = hintRes == null && selected != null && !sending,
        modifier = Modifier.fillMaxWidth().testTag(TestSendTags.BUTTON),
    ) { Text(stringResource(R.string.button_test_send)) }
    if (hintRes != null) {
        Text(
            text = stringResource(hintRes),
            fontSize = 12.sp,
            color = HintColor,
            modifier = Modifier.testTag(TestSendTags.HINT),
        )
    }
}

/** 送信中の進行ダイアログ。キャンセルなし、戻る・外側タップでは閉じない(ds 9.5)。 */
@Composable
fun TestSendProgressDialog() {
    val cd = stringResource(R.string.cd_progress)
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(32.dp).semantics { contentDescription = cd })
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.sending))
            }
        },
        confirmButton = {},
    )
}

/** 結果ダイアログ。「OK」でだけ閉じる(戻る・外側タップは無視、ds 9.5 / Q58)。 */
@Composable
fun TestSendResultDialog(result: TestSendResult, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        text = {
            Text(
                if (result.arg != null) stringResource(result.messageRes, result.arg)
                else stringResource(result.messageRes),
            )
        },
        confirmButton = { TextButton(onClick = onOk) { Text(stringResource(R.string.button_ok)) } },
    )
}
