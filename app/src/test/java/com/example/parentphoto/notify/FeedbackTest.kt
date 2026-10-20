package com.example.parentphoto.notify

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackTest {
    private var vibrations = 0
    private var tones = 0

    private fun feedback(normal: Boolean, vibrateFails: Boolean = false) = Feedback(
        vibrate = {
            vibrations++
            if (vibrateFails) error("no vibrator")
        },
        tone = { tones++ },
        ringerNormal = { normal },
    )

    @Test
    fun 通常の着信音モードでは振動と音() {
        feedback(normal = true).play()
        assertEquals(1, vibrations)
        assertEquals(1, tones)
    }

    @Test
    fun マナーモードやサイレントでは振動だけ() {
        feedback(normal = false).play()
        assertEquals(1, vibrations)
        assertEquals(0, tones)
    }

    @Test
    fun 振動に失敗しても音は鳴らす() {
        feedback(normal = true, vibrateFails = true).play()
        assertEquals(1, tones)
    }
}
