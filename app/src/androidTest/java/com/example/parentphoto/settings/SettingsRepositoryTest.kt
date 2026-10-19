package com.example.parentphoto.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SettingsRepositoryTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(ctx.cacheDir, "settings_test.preferences_pb")
    private val crypto = PasswordCrypto("parent_photo_test_alias")
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun open() = SettingsRepository(
        PreferenceDataStoreFactory.create(scope = scope) { file }, crypto,
    )

    // 同一ファイルを開き直して「プロセス再起動」を模擬する
    private fun restart(): SettingsRepository {
        runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() } // 完全に閉じてから開き直す
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return open()
    }

    @Before
    fun setUp() {
        file.delete()
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
        runCatching { crypto.deleteKey() }
    }

    @Test fun defaults() = runBlocking {
        val repo = open()
        val s = repo.settings.first()
        assertEquals(Settings(), s)
        assertEquals("smtp.gmail.com", s.smtpHost)
        assertEquals(ButtonSize.LARGE, s.buttonSize)
        assertTrue(repo.isSetupRequired())
    }

    @Test fun saveSmtp_persistsAcrossRestart_andKeepsPasswordWhenNull() = runBlocking {
        var repo = open()
        repo.saveSmtp(" お父\nさん ", " me@example.com ", " smtp.example.com ", 587, "abcd efgh ijkl mnop")
        repo = restart()
        val s = repo.settings.first()
        assertEquals("お父さん", s.senderName)
        assertEquals("me@example.com", s.smtpUser)
        assertEquals("smtp.example.com", s.smtpHost)
        assertEquals(587, s.smtpPort)
        assertTrue(s.passwordPresent)
        assertEquals("abcdefghijklmnop", repo.readPassword())
        // パスワード指定なしの保存は既存を保持
        repo.saveSmtp("", "me@example.com", "smtp.example.com", 465, null)
        assertEquals("abcdefghijklmnop", repo.readPassword())
        assertEquals(465, repo.settings.first().smtpPort)
        assertFalse(repo.isSetupRequired())
    }

    @Test fun keyLost_treatedAsUnset() = runBlocking {
        val repo = open()
        repo.saveSmtp("", "me@example.com", "smtp.gmail.com", 465, "abcdefghijklmnop")
        crypto.deleteKey()
        assertNull(repo.readPassword())
        assertTrue(repo.isSetupRequired())
    }

    @Test fun recipients_addUpdateDeleteCap() = runBlocking {
        var repo = open()
        val ids = (1..6).map { repo.addRecipient(" 名$it ", " $it@example.com ")!!.id }
        assertEquals(6, ids.toSet().size)
        assertNull(repo.addRecipient("七", "7@example.com"))
        repo = restart()
        assertEquals(
            listOf("名1", "名2", "名3", "名4", "名5", "名6"),
            repo.settings.first().recipients.map { it.name },
        )
        assertTrue(repo.updateRecipient(ids[2], "変更", "x@example.com"))
        assertFalse(repo.updateRecipient("none", "a", "b"))
        var list = repo.settings.first().recipients
        assertEquals("変更", list[2].name)
        assertEquals(ids[2], list[2].id)
        repo.deleteRecipient(ids[0])
        list = repo.settings.first().recipients
        assertEquals(5, list.size)
        assertEquals(ids[1], list[0].id)
        // 削除後に追加しても削除済みidは再利用されない
        assertFalse(ids.contains(repo.addRecipient("新", "n@example.com")!!.id))
    }

    @Test fun buttonSize_andNotifFlag_persist() = runBlocking {
        var repo = open()
        repo.setButtonSize(ButtonSize.XLARGE)
        repo.markNotifPermissionAsked()
        repo = restart()
        val s = repo.settings.first()
        assertEquals(ButtonSize.XLARGE, s.buttonSize)
        assertTrue(s.notifPermissionAsked)
    }
}
