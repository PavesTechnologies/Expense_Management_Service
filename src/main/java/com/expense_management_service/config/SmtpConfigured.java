package com.expense_management_service.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Matches only when {@code expense.mail.smtp-host} is set to a non-blank value. An empty .env entry does not match. */
public class SmtpConfigured implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String host = context.getEnvironment().getProperty("expense.mail.smtp-host");
        return host != null && !host.isBlank();
    }
}
