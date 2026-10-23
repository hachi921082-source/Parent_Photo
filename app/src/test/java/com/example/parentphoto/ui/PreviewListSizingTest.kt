package com.example.parentphoto.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Preview の送り先一覧の寸法の決め方(issue #4) */
class PreviewListSizingTest {
    private val sizes = listOf(56f, 96f, 128f)
    private val tall = 900f

    private fun avail(total: Float, s: Float) = PreviewListSizing.availableHeight(total, s)

    @Test
    fun `3件までは設定の高さと間隔16のまま`() {
        for (s in sizes) for (n in 1..3) {
            val l = PreviewListSizing.layout(avail(tall, s), n, s)
            assertEquals(s, l.buttonHeight, 0f)
            assertEquals(16f, l.spacing, 0f)
            assertFalse(l.scrollable)
            assertEquals(PreviewListSizing.naturalHeight(n, s), l.viewportHeight, 0.01f)
        }
    }

    @Test
    fun `一覧の上限は3件分の高さで4件以上でも写真は3件の時より小さくならない`() {
        for (s in sizes) for (n in 4..6) {
            val l = PreviewListSizing.layout(avail(tall, s), n, s)
            assertTrue(l.viewportHeight <= PreviewListSizing.naturalHeight(3, s) + 0.01f)
        }
    }

    @Test
    fun `96で4件は間隔16のまま高さだけ縮む`() {
        val l = PreviewListSizing.layout(avail(tall, 96f), 4, 96f)
        assertEquals(16f, l.spacing, 0f)
        assertEquals(68f, l.buttonHeight, 0.01f)
        assertFalse(l.scrollable)
    }

    @Test
    fun `96で5件は間隔を8に詰めて高さを縮める`() {
        // 3 件分 = 320。16 間隔だと (320-64)/5=51.2 < 56 なので 8 間隔 (320-32)/5=57.6
        val l = PreviewListSizing.layout(avail(tall, 96f), 5, 96f)
        assertEquals(8f, l.spacing, 0f)
        assertEquals(57.6f, l.buttonHeight, 0.01f)
        assertFalse(l.scrollable)
    }

    @Test
    fun `96で6件はスクロールして最後が半分切れる`() {
        val l = PreviewListSizing.layout(avail(tall, 96f), 6, 96f)
        assertTrue(l.scrollable)
        assertEquals(56f, l.buttonHeight, 0f)
        assertEquals(8f, l.spacing, 0f)
        // 4 個 * 64 + 28 = 284 <= 320
        assertEquals(284f, l.viewportHeight, 0.01f)
    }

    @Test
    fun `特大128で6件はちょうど56に縮んで間隔16のまま入る`() {
        val l = PreviewListSizing.layout(avail(tall, 128f), 6, 128f)
        assertFalse(l.scrollable)
        assertEquals(56f, l.buttonHeight, 0.01f)
        assertEquals(16f, l.spacing, 0f)
    }

    @Test
    fun `標準56では4件からスクロールする`() {
        assertFalse(PreviewListSizing.layout(avail(tall, 56f), 3, 56f).scrollable)
        assertTrue(PreviewListSizing.layout(avail(tall, 56f), 4, 56f).scrollable)
    }

    @Test
    fun `小さい画面では写真の絶対下限を守る分だけ一覧が小さくなる`() {
        val total = 560f
        val a = avail(total, 128f)
        assertTrue(a < PreviewListSizing.naturalHeight(3, 128f))
        val photo = total - PreviewListSizing.retakeRowHeight(128f) - PreviewListSizing.LIST_GAPS - a
        assertTrue(photo >= total * 0.25f - 0.01f)
        assertTrue(PreviewListSizing.layout(a, 3, 128f).viewportHeight <= a + 0.01f)
    }

    @Test
    fun `0件は高さ0`() {
        val l = PreviewListSizing.layout(avail(tall, 96f), 0, 96f)
        assertEquals(0f, l.viewportHeight, 0f)
        assertFalse(l.scrollable)
    }
}
