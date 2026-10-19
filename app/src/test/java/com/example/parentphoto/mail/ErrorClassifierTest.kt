package com.example.parentphoto.mail

import com.sun.mail.smtp.SMTPSendFailedException
import com.sun.mail.util.MailConnectException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.mail.AuthenticationFailedException
import javax.mail.MessagingException
import javax.mail.SendFailedException
import javax.mail.internet.AddressException
import javax.net.ssl.SSLException

class ErrorClassifierTest {
    private fun check(t: Throwable, kind: FailureKind, code: MailCode) {
        val f = ErrorClassifier.classify(t)
        assertEquals(kind, f.kind)
        assertEquals(code, f.code)
    }

    private fun smtp(code: Int) = SMTPSendFailedException("MAIL", code, "$code x", null, null, null, null)

    @Test fun authenticationFailed() = check(AuthenticationFailedException("535 5.7.8 bad"), FailureKind.AUTH, MailCode.AUTH)

    @Test fun authNested() =
        check(MessagingException("outer", AuthenticationFailedException("x")), FailureKind.AUTH, MailCode.AUTH)

    @Test fun authViaNextException() {
        val e = MessagingException("outer")
        e.setNextException(AuthenticationFailedException("x"))
        check(e, FailureKind.AUTH, MailCode.AUTH)
    }

    @Test fun replyAuthCodes() {
        for (c in listOf(530, 534, 535)) {
            val f = ErrorClassifier.classify(smtp(c))
            assertEquals(FailureKind.AUTH, f.kind)
            assertEquals(c, f.smtpReply)
        }
    }

    @Test fun reply4xxTransient() {
        check(smtp(421), FailureKind.TRANSIENT, MailCode.NETWORK)
        check(smtp(451), FailureKind.TRANSIENT, MailCode.NETWORK)
    }

    @Test fun reply550And553Rejected() {
        check(smtp(550), FailureKind.PERMANENT, MailCode.REJECTED)
        check(smtp(553), FailureKind.PERMANENT, MailCode.REJECTED)
    }

    @Test fun reply5xxOther() = check(smtp(554), FailureKind.PERMANENT, MailCode.OTHER)

    @Test fun replyParsedFromMessageText() {
        check(SendFailedException("535 5.7.8 Username and Password not accepted"), FailureKind.AUTH, MailCode.AUTH)
        check(MessagingException("550 5.1.1 no such user"), FailureKind.PERMANENT, MailCode.REJECTED)
    }

    @Test fun addressException() = check(AddressException("bad"), FailureKind.PERMANENT, MailCode.REJECTED)

    @Test fun socketTimeout() =
        check(MessagingException("x", SocketTimeoutException("t")), FailureKind.TRANSIENT, MailCode.TIMEOUT)

    @Test fun networkFamily() {
        check(UnknownHostException("h"), FailureKind.TRANSIENT, MailCode.NETWORK)
        check(ConnectException("c"), FailureKind.TRANSIENT, MailCode.NETWORK)
        check(SSLException("s"), FailureKind.TRANSIENT, MailCode.NETWORK)
        check(IOException("io"), FailureKind.TRANSIENT, MailCode.NETWORK)
        check(MailConnectException(com.sun.mail.util.SocketConnectException("m", ConnectException(), "h", 465, 0)), FailureKind.TRANSIENT, MailCode.NETWORK)
    }

    @Test fun exceptionReadingResponse() =
        check(MessagingException("Exception reading response"), FailureKind.TRANSIENT, MailCode.NETWORK)

    @Test fun unknownIsTransientOther() = check(IllegalStateException("?"), FailureKind.TRANSIENT, MailCode.OTHER)

    @Test fun cyclicCauseDoesNotLoop() {
        val a = MessagingException("a")
        val b = MessagingException("b")
        a.setNextException(b)
        b.setNextException(a)
        check(a, FailureKind.TRANSIENT, MailCode.OTHER)
    }

    @Test fun authBeatsNetwork() =
        check(IOException("io", AuthenticationFailedException("x")), FailureKind.AUTH, MailCode.AUTH)
}
