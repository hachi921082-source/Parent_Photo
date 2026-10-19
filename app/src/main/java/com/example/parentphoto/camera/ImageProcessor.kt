package com.example.parentphoto.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** 撮影画像の縮小と向き補正(ds 4.3)。 */
object ImageProcessor {
    private const val TAG = "ParentPhoto/Camera"
    const val MAX_EDGE = 1600
    const val JPEG_QUALITY = 85

    /**
     * raw を読み、EXIFの向きを画素に反映し、長辺を MAX_EDGE 以下(拡大はしない)に縮小して
     * files.preview へ JPEG(品質85)で書く。成功時は raw を削除する。
     * 失敗時は raw・途中のファイルとも消し、Result.failure を返す(OOM含む)。
     */
    suspend fun resize(files: PreviewFiles): Result<File> = withContext(Dispatchers.Default) {
        try {
            resizeBlocking(files.raw, files.previewTmp)
            // 完成してから差し替える(途中状態の preview.jpg を残さない)
            files.preview.delete()
            if (!files.previewTmp.renameTo(files.preview)) {
                throw IOException("preview.jpg への差し替えに失敗")
            }
            files.raw.delete()
            Result.success(files.preview)
        } catch (e: Throwable) {
            files.deleteAll()
            if (e is CancellationException) throw e
            // 写真の内容はログに出さない
            Log.e(TAG, "resize failed: ${e.javaClass.simpleName}", e)
            Result.failure(e)
        }
    }

    // 縮小本体。テストしやすいようファイル2つだけを受け取る
    internal fun resizeBlocking(raw: File, out: File, maxEdge: Int = MAX_EDGE) {
        val orientation = ExifInterface(raw.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(raw.absolutePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) throw IOException("画像として読めない")

        // メモリ節約のため、長辺が maxEdge を下回らない範囲で2のべき乗に間引いて読む
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(maxOf(w, h), maxEdge) }
        val decoded = BitmapFactory.decodeFile(raw.absolutePath, opts)
            ?: throw IOException("デコード失敗")

        val matrix = orientationMatrix(orientation)
        val longEdge = maxOf(decoded.width, decoded.height)
        if (longEdge > maxEdge) {
            val scale = maxEdge.toFloat() / longEdge
            matrix.postScale(scale, scale)
        }
        val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (result !== decoded) decoded.recycle()

        try {
            FileOutputStream(out).use { result.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } finally {
            result.recycle()
        }
    }

    internal fun sampleSize(longEdge: Int, maxEdge: Int): Int {
        var s = 1
        while (longEdge / (s * 2) >= maxEdge) s *= 2
        return s
    }

    // EXIF orientation を画素に焼き込むための行列(反転系も一応対応)
    internal fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
            else -> Unit
        }
    }
}
