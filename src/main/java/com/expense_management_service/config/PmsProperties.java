package com.expense_management_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code pms.*} configuration namespace.
 *
 * @param baseUrl root URL of the Project Management System, e.g. {@code http://localhost:8081/pms}
 *                (PMS's own controllers are mounted under context path {@code /pms})
 */
@ConfigurationProperties(prefix = "pms")
public record PmsProperties(String baseUrl) {
}
