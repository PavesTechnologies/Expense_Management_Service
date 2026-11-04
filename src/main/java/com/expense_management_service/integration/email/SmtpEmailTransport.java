package com.expense_management_service.integration.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Sends email through an SMTP server. Created only when {@code expense.mail.smtp-host} is set.
 * Each message is sent as HTML (with real tables) and plain text as the fallback.
 */
public class SmtpEmailTransport implements EmailTransport {

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailTransport(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String body) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, EmailHtml.render(body));
            mailSender.send(message);
        } catch (MailException | MessagingException e) {
            throw new EmailTransportException("SMTP send failed: " + e.getMessage());
        }
    }
}
