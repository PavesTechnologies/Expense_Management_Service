package com.expense_management_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code employee-onboarding.*} configuration namespace.
 *
 * @param baseUrl root URL of the Employee Onboarding service, which owns Department master data
 *                (e.g. {@code https://enterpriseappdev.pavestechnologies.net/ems})
 */
@ConfigurationProperties(prefix = "employee-onboarding")
public record EmployeeOnboardingProperties(String baseUrl) {
}
