package com.expense_management_service.integration.email;

import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** Sends email through an SMTP server. Created only when {@code expense.mail.smtp-host} is set. */
public class SmtpEmailTransport implements EmailTransport {

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailTransport(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
        } catch (MailException e) {
            throw new EmailTransportException("SMTP send failed: " + e.getMessage());
        }
    }
}
