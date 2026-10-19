package com.example.parentphoto.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

class SetupStateTest {
    private val pat: Pattern = Pattern.compile("[^@ ]+@[^@ ]+[.][^@ ]+")
    private val ok = Settings(smtpUser = "me@example.com", smtpHost = "smtp.gmail.com", smtpPort = 465)

    private fun req(s: Settings, pw: Boolean) = SetupState.isSetupRequired(s, pw, pat)

    @Test fun allOk_notRequired() = assertFalse(req(ok, true))
    @Test fun badUser_required() = assertTrue(req(ok.copy(smtpUser = "x"), true))
    @Test fun emptyUser_required() = assertTrue(req(ok.copy(smtpUser = ""), true))
    @Test fun noPassword_required() = assertTrue(req(ok, false))
    @Test fun blankHost_required() = assertTrue(req(ok.copy(smtpHost = "  "), true))
    @Test fun portZero_required() = assertTrue(req(ok.copy(smtpPort = 0), true))
    @Test fun portTooBig_required() = assertTrue(req(ok.copy(smtpPort = 65536), true))
    @Test fun senderNameAndRecipientsIgnored() {
        assertFalse(req(ok.copy(senderName = "", recipients = emptyList()), true))
    }
}
