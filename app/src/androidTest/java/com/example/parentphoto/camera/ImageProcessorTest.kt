package com.example.parentphoto.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/** ImageProcessor の端末テスト(向き1/3/6/8、大きい画像、小さい画像、不正入力)。 */
class ImageProcessorTest {
    private lateinit var dir: File
    private lateinit var files: PreviewFiles

    @Before
    fun setUp() {
        dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ip_test").apply {
            deleteRecursively()
            mkdirs()
        }
        files = PreviewFiles(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // 左半分が赤、右半分が青の画像をEXIF向き付きで raw に書く
    private fun writeRaw(w: Int, h: Int, orientation: Int) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLUE)
        c.drawRect(0f, 0f, w / 2f, h.toFloat(), Paint().apply { color = Color.RED })
        // 単色に近い小画像はJPEGが極小になりExifInterfaceが保存に失敗するため、下端にノイズを入れる
        val rnd = java.util.Random(1)
        for (x in 0 until w step 2) bmp.setPixel(x, h - 1, Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)))
        FileOutputStream(files.raw).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        ExifInterface(files.raw.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
    }

    private fun size(f: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        return o.outWidth to o.outHeight
    }

    private fun isRed(c: Int) = Color.red(c) > 150 && Color.blue(c) < 100
    private fun isBlue(c: Int) = Color.blue(c) > 150 && Color.red(c) < 100

    @Test
    fun orientation1_largeImage_scaledToLongEdge1600() = runBlocking {
        writeRaw(4000, 3000, ExifInterface.ORIENTATION_NORMAL)
        assertTrue(ImageProcessor.resize(files).isSuccess)
        assertEquals(1600 to 1200, size(files.preview))
        assertFalse(files.raw.exists())
        val bmp = BitmapFactory.decodeFile(files.preview.absolutePath)
        assertTrue(isRed(bmp.getPixel(100, 600)))
        assertTrue(isBlue(bmp.getPixel(1500, 600)))
    }

    @Test
    fun orientation6_rotates90_swapsDimensionsAndMovesRedToTop() = runBlocking {
        writeRaw(4000, 3000, ExifInterface.ORIENTATION_ROTATE_90)
        assertTrue(ImageProcessor.resize(files).isSuccess)
        assertEquals(1200 to 1600, size(files.preview))
        val bmp = BitmapFactory.decodeFile(files.preview.absolutePath)
        // 時計回り90度: 左半分(赤)が上半分へ
        assertTrue(isRed(bmp.getPixel(600, 100)))
        assertTrue(isBlue(bmp.getPixel(600, 1500)))
    }

    @Test
    fun orientation8_rotates270_movesRedToBottom() = runBlocking {
        writeRaw(4000, 3000, ExifInterface.ORIENTATION_ROTATE_270)
        assertTrue(ImageProcessor.resize(files).isSuccess)
        assertEquals(1200 to 1600, size(files.preview))
        val bmp = BitmapFactory.decodeFile(files.preview.absolutePath)
        assertTrue(isRed(bmp.getPixel(600, 1500)))
        assertTrue(isBlue(bmp.getPixel(600, 100)))
    }

    @Test
    fun orientation3_rotates180_movesRedToRight() = runBlocking {
        writeRaw(4000, 3000, ExifInterface.ORIENTATION_ROTATE_180)
        assertTrue(ImageProcessor.resize(files).isSuccess)
        assertEquals(1600 to 1200, size(files.preview))
        val bmp = BitmapFactory.decodeFile(files.preview.absolutePath)
        assertTrue(isBlue(bmp.getPixel(100, 600)))
        assertTrue(isRed(bmp.getPixel(1500, 600)))
    }

    @Test
    fun smallImage_notUpscaled() = runBlocking {
        writeRaw(800, 600, ExifInterface.ORIENTATION_NORMAL)
        assertTrue(ImageProcessor.resize(files).isSuccess)
        assertEquals(800 to 600, size(files.preview))
    }

    @Test
    fun smallImageWithRotation_notUpscaled() = runBlocking {
        writeRaw(800, 600, ExifInterface.ORIENTATION_ROTATE_90)
        assertTrue(ImageProcessor.resize(files).isSuccess)
        assertEquals(600 to 800, size(files.preview))
    }

    @Test
    fun corruptInput_failsAndDeletesFiles() = runBlocking {
        files.raw.writeText("not a jpeg")
        assertTrue(ImageProcessor.resize(files).isFailure)
        assertFalse(files.raw.exists())
        assertFalse(files.preview.exists())
        assertFalse(files.previewTmp.exists())
    }

    @Test
    fun deleteAll_isSafeWhenNothingExists() {
        files.deleteAll()
        assertFalse(files.preview.exists())
    }
}
