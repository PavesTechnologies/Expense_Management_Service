package com.expense_management_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code rms.*} configuration namespace.
 *
 * @param baseUrl root URL of the Resource Management System, e.g. {@code http://localhost:8082/rms}
 *                (RMS's own controllers are mounted under context path {@code /rms})
 */
@ConfigurationProperties(prefix = "rms")
public record RmsProperties(String baseUrl) {
}
