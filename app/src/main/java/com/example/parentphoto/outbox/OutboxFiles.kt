package com.example.parentphoto.outbox

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 未送信写真のファイル置き場 filesDir/outbox/(ds 7.1)。
 * アプリ専用領域なので共有ストレージにもメディアスキャンにも出ない。ファイル名はUUID。
 */
class OutboxFiles(filesDir: File) {
    val dir: File = File(filesDir, DIR_NAME)

    fun file(id: String): File = File(dir, "$id.jpg")

    fun tmpFile(id: String): File = File(dir, "$id.jpg$TMP_SUFFIX")

    /** 置き場の全ファイル(作りかけの .tmp も含む)。孤立ファイルの掃除に使う。 */
    fun listFiles(): List<File> = dir.listFiles()?.toList().orEmpty()

    /**
     * source を {id}.jpg として保存する。一時ファイルに書いて同期してから名前を変える
     * (途中でアプリが落ちても壊れた .jpg を残さない)。
     */
    fun importCopy(id: String, source: File): File {
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) throw IOException("cannot create outbox dir")
        val tmp = tmpFile(id)
        val target = file(id)
        try {
            source.inputStream().use { input ->
                FileOutputStream(tmp).use { out ->
                    input.copyTo(out)
                    out.fd.sync()
                }
            }
            if (!tmp.renameTo(target)) throw IOException("rename failed")
        } catch (e: Exception) {
            tmp.delete()
            target.delete()
            throw e
        }
        return target
    }

    /** {id}.jpg と作りかけの .tmp を消す。失敗しても例外にしない(残りは起動時の掃除で消える)。 */
    fun delete(id: String) {
        file(id).delete()
        tmpFile(id).delete()
    }

    companion object {
        const val DIR_NAME = "outbox"
        const val TMP_SUFFIX = ".tmp"
    }
}
