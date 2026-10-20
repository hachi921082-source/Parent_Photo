package com.example.parentphoto.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.parentphoto.R
import com.example.parentphoto.outbox.SendLogEntry
import com.example.parentphoto.util.TokyoTime

/** 送信ログの画面テスト用の目印。 */
object SendLogTags {
    const val LIST = "send_log_list"
    fun row(id: Long) = "send_log_row_$id"
}

/**
 * 送信ログの中身(ds 9.7、settings-screen PLAN-4)。新しい順に最大50件(件数は未送信キュー側で絞る)。
 * 1行目に時刻(東京時間、秒まで)と送り先名、2行目に結果と短いコード(失敗・テスト失敗のときだけ)。
 * 画像も消去ボタンもない。空なら「送信ログはありません」。
 */
@Composable
fun SendLogList(entries: List<SendLogEntry>?, modifier: Modifier = Modifier) {
    if (entries == null) return
    if (entries.isEmpty()) {
        Box(modifier.fillMaxSize().padding(16.dp)) { Text(stringResource(R.string.log_empty)) }
        return
    }
    LazyColumn(modifier.fillMaxSize().testTag(SendLogTags.LIST), contentPadding = PaddingValues(horizontal = 16.dp)) {
        items(entries, key = { it.id }) { e ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(SendLogTags.row(e.id))) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(TokyoTime.formatSecond(e.timeMillis), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        e.recipientName ?: stringResource(R.string.deleted_recipient),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(UnsentLogic.resultLabel(e.result)), style = MaterialTheme.typography.bodyLarge)
                    val code = UnsentLogic.codeShown(e)
                    if (code.isNotEmpty()) Text(code, style = MaterialTheme.typography.bodyLarge)
                }
            }
            HorizontalDivider()
        }
    }
}
