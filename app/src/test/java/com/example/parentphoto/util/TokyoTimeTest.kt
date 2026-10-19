package com.example.parentphoto.util

import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TokyoTimeTest {
    private lateinit var original: TimeZone

    // 2026-10-19 10:36:05 UTC = 2026-10-19 19:36:05 JST
    private val t = 1_792_406_165_000L

    @Before
    fun setUp() {
        original = TimeZone.getDefault()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(original)
    }

    @Test
    fun formats() {
        assertEquals("2026/10/19 19:36", TokyoTime.formatMinute(t))
        assertEquals("2026/10/19 19:36:05", TokyoTime.formatSecond(t))
        assertEquals("20261019_193605", TokyoTime.formatFileName(t))
    }

    @Test
    fun ignoresDefaultTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        assertEquals("2026/10/19 19:36", TokyoTime.formatMinute(t))
        assertEquals("20261019_193605", TokyoTime.formatFileName(t))
    }

    @Test
    fun crossesDateBoundary() {
        // 2026-12-31 16:00:00 UTC = 2027-01-01 01:00:00 JST
        assertEquals("2027/01/01 01:00:00", TokyoTime.formatSecond(1_798_732_800_000L))
    }
}
