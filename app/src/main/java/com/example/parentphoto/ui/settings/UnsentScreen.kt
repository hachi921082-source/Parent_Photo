package com.example.parentphoto.ui.settings

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.parentphoto.R
import com.example.parentphoto.outbox.ItemState
import com.example.parentphoto.outbox.OutboxItemView
import com.example.parentphoto.util.Constants
import com.example.parentphoto.util.TokyoTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 未送信一覧の画面テスト用の目印。 */
object UnsentTags {
    const val LIST = "unsent_list"
    fun row(id: String) = "unsent_row_$id"
    fun thumb(id: String) = "unsent_thumb_$id"
    fun resend(id: String) = "unsent_resend_$id"
    fun delete(id: String) = "unsent_delete_$id"
}

private val PlaceholderColor = Color(0xFFE0E0E0)
private val ThumbSize = 96.dp

/**
 * 未送信一覧の中身(ds 9.6、settings-screen PLAN-4)。「送信待ち n件」の群、続いて「要確認 n件/10」の群。
 * 空の群は見出しも出さない。両方空なら「未送信の写真はありません」。DB の変化をそのまま映す。
 */
@Composable
fun UnsentList(
    groups: UnsentGroups?,
    onResend: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (groups == null) return
    if (groups.isEmpty) {
        Box(modifier.fillMaxSize().padding(16.dp)) { Text(stringResource(R.string.unsent_empty)) }
        return
    }
    LazyColumn(modifier.fillMaxSize().testTag(UnsentTags.LIST), contentPadding = PaddingValues(16.dp)) {
        if (groups.waiting.isNotEmpty()) {
            item(key = "h_waiting") { GroupHeader(stringResource(R.string.unsent_group_waiting, groups.waiting.size)) }
            items(groups.waiting, key = { it.id }) { UnsentRow(it, onResend, onDelete) }
        }
        if (groups.attention.isNotEmpty()) {
            item(key = "h_attention") {
                GroupHeader(stringResource(R.string.unsent_group_attention, groups.attention.size, Constants.ATTENTION_CAP))
            }
            items(groups.attention, key = { it.id }) { UnsentRow(it, onResend, onDelete) }
        }
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

/** 1行: 左に96dpの縮小画像、右に文字、文字の下にボタン2つ。 */
@Composable
private fun UnsentRow(item: OutboxItemView, onResend: (String) -> Unit, onDelete: (String) -> Unit) {
    val thumb = rememberThumbnail(item.file)
    val buttons = UnsentLogic.buttons(item)
    // 画像が読めない行は削除だけ押せる
    val resendEnabled = buttons.resend && thumb !is Thumb.Missing
    Column(Modifier.fillMaxWidth().testTag(UnsentTags.row(item.id))) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Thumbnail(thumb, Modifier.size(ThumbSize).testTag(UnsentTags.thumb(item.id)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.recipientName ?: stringResource(R.string.deleted_recipient),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(TokyoTime.formatMinute(item.capturedAtMillis), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(UnsentLogic.stateLabel(item)), style = MaterialTheme.typography.bodyMedium)
                if (item.state == ItemState.ATTENTION) {
                    UnsentLogic.reasonLabel(item.reason)?.let {
                        Text(
                            stringResource(it),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    OutlinedButton(
                        onClick = { onResend(item.id) },
                        enabled = resendEnabled,
                        modifier = Modifier.testTag(UnsentTags.resend(item.id)),
                    ) { Text(stringResource(R.string.button_resend_now)) }
                    OutlinedButton(
                        onClick = { onDelete(item.id) },
                        enabled = buttons.delete,
                        modifier = Modifier.testTag(UnsentTags.delete(item.id)),
                    ) { Text(stringResource(R.string.button_delete)) }
                }
            }
        }
        HorizontalDivider()
    }
}

/** 縮小画像の読み込み状態。 */
sealed interface Thumb {
    data object Loading : Thumb
    data class Loaded(val bitmap: ImageBitmap) : Thumb
    data object Missing : Thumb
}

/** 未送信の写真を 96dp 相当に縮めて別スレッドで読む。無い・読めないときは Missing。 */
@Composable
private fun rememberThumbnail(file: File): Thumb {
    val px = with(LocalDensity.current) { ThumbSize.roundToPx() }
    val state by produceState<Thumb>(Thumb.Loading, file.path, px) {
        value = withContext(Dispatchers.IO) { decodeThumb(file, px) }
    }
    return state
}

private fun decodeThumb(file: File, targetPx: Int): Thumb {
    if (!file.isFile) return Thumb.Missing
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Thumb.Missing
        // 短い辺が表示の大きさを下回らない範囲で2の累乗に縮める
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        if (bmp == null) Thumb.Missing else Thumb.Loaded(bmp.asImageBitmap())
    } catch (e: Exception) {
        Thumb.Missing
    } catch (e: OutOfMemoryError) {
        Thumb.Missing
    }
}

@Composable
private fun Thumbnail(thumb: Thumb, modifier: Modifier) {
    when (thumb) {
        is Thumb.Loaded -> Image(thumb.bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
        Thumb.Loading -> Box(modifier.background(PlaceholderColor))
        Thumb.Missing -> Box(modifier.background(PlaceholderColor), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_image), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 未送信の項目の削除確認。外側タップ・戻るはキャンセル。 */
@Composable
fun DeleteUnsentDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(R.string.confirm_delete_item)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.button_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_settings_cancel)) } },
    )
}
