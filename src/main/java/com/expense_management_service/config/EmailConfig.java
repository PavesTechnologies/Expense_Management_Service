package com.expense_management_service.config;

import com.expense_management_service.integration.email.EmailTransport;
import com.expense_management_service.integration.email.SmtpEmailTransport;
import com.expense_management_service.integration.email.UnconfiguredEmailTransport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/**
 * Chooses the email transport. With {@code expense.mail.smtp-host} set, mail goes through SMTP. Without it, the
 * default transport refuses to send, so messages stay queued in the outbox instead of being marked sent.
 */
@Configuration
public class EmailConfig {

    @Bean
    @org.springframework.context.annotation.Conditional(SmtpConfigured.class)
    public EmailTransport smtpEmailTransport(
            @Value("${expense.mail.smtp-host}") String host,
            @Value("${expense.mail.smtp-port:587}") int port,
            @Value("${expense.mail.smtp-username:}") String username,
            @Value("${expense.mail.smtp-password:}") String password,
            @Value("${expense.mail.from:}") String from) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        boolean authenticate = username != null && !username.isBlank();
        if (authenticate) {
            sender.setUsername(username);
            sender.setPassword(password);
        }
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", String.valueOf(authenticate));
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");
        return new SmtpEmailTransport(sender, from);
    }

    /** Used when no SMTP host is configured. Replaced automatically by the SMTP transport above. */
    @Bean
    @ConditionalOnMissingBean(EmailTransport.class)
    public EmailTransport unconfiguredEmailTransport() {
        return new UnconfiguredEmailTransport();
    }
}
