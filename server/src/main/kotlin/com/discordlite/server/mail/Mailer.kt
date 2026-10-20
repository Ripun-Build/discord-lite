package com.discordlite.server.mail

import com.discordlite.server.config.AppConfig
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.Properties

interface Mailer {
    suspend fun send(to: String, subject: String, body: String)
}

/** Sends plain-text mail over SMTP. In development this points at Mailpit (localhost:1025). */
class SmtpMailer(private val config: AppConfig) : Mailer {
    private val log = LoggerFactory.getLogger(SmtpMailer::class.java)
    private val session = Session.getInstance(
        Properties().apply {
            put("mail.smtp.host", config.smtpHost)
            put("mail.smtp.port", config.smtpPort.toString())
            put("mail.smtp.connectiontimeout", "5000")
            put("mail.smtp.timeout", "5000")
        },
    )

    override suspend fun send(to: String, subject: String, body: String) = withContext(Dispatchers.IO) {
        try {
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(config.mailFrom))
                setRecipient(Message.RecipientType.TO, InternetAddress(to))
                setSubject(subject)
                setText(body, "UTF-8")
            }
            Transport.send(message)
        } catch (e: Exception) {
            // Never log the body: it contains one-time codes.
            log.warn("Failed to send email ({}): {}", subject, e.javaClass.simpleName)
        }
    }
}

object Emails {
    fun verifyEmail(code: String) =
        "Your discord-lite verification code is $code\n\nIt expires in 10 minutes. If you didn't sign up, you can ignore this email."

    fun resetPassword(code: String) =
        "Your discord-lite password reset code is $code\n\nIt expires in 10 minutes. If you didn't ask for this, you can ignore this email — your password hasn't changed."

    const val SIGN_UP_ATTEMPT =
        "Someone tried to create a discord-lite account with this email address.\n\nIf that was you, sign in instead — or use \"Forgot password?\" if you can't remember it. If it wasn't you, no action is needed."
}
