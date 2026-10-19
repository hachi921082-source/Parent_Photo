package com.example.parentphoto.notify

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionRequesterTest {
    private var asked = false
    private var launched = 0

    private fun requester(sdk: Int, granted: Boolean = false) = NotificationPermissionRequester(
        isAsked = { asked },
        markAsked = { asked = true },
        isGranted = { granted },
        sdkInt = sdk,
    )

    private fun call(r: NotificationPermissionRequester) =
        runBlocking { r.requestIfNeeded { launched++ } }

    @Test
    fun api33で1回だけ要求し二度目は要求しない() {
        val r = requester(33)
        assertTrue(call(r))
        assertTrue(asked)
        assertFalse(call(r))
        assertEquals(1, launched)
    }

    @Test
    fun api32以下は要求しない() {
        assertFalse(call(requester(32)))
        assertEquals(0, launched)
        assertFalse(asked)
    }

    @Test
    fun 許可済みなら要求せずフラグだけ立てる() {
        assertFalse(call(requester(34, granted = true)))
        assertEquals(0, launched)
        assertTrue(asked)
    }

    @Test
    fun 要求済みフラグがあれば要求しない() {
        asked = true
        assertFalse(call(requester(34)))
        assertEquals(0, launched)
    }
}
