package com.example.parentphoto.camera

import java.io.File

/**
 * 撮影中の一時ファイルの置き場所(ds 4.2 / 4.3)。
 * 撮影直後の元画像と、縮小後のプレビュー画像はそれぞれ常に1枚だけ。
 */
class PreviewFiles(cacheDir: File) {
    /** CameraX が書き込む縮小前の画像 */
    val raw: File = File(cacheDir, "capture_raw.jpg")

    /** 縮小済み・向き補正済みの画像。プレビュー表示と送信に使う */
    val preview: File = File(cacheDir, "preview.jpg")

    /** 縮小処理中の一時ファイル(完成後に preview へ差し替える) */
    internal val previewTmp: File = File(cacheDir, "preview.jpg.tmp")

    /** 起動時・やり直す・送信完了/キュー入り時に呼ぶ。存在しなくても失敗しない */
    fun deleteAll() {
        raw.delete()
        preview.delete()
        previewTmp.delete()
    }
}
