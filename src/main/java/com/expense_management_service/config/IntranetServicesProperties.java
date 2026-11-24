package com.expense_management_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Base URLs of the other intranet services the Paves Assistant reads from (read-only, with the caller's own token).
 * Projects (PMS), Resource Management (RMS), User Management (UMS) and Employee Onboarding (EMS) reuse their existing
 * {@code pms.*}, {@code rms.*}, {@code ums.*} and {@code employee-onboarding.*} settings.
 * <p>
 * AI Screening, Accounts Payable and Account Receivable have no deployed address yet; while one is blank, the
 * assistant says that module is not connected instead of failing.
 */
@ConfigurationProperties(prefix = "intranet")
public record IntranetServicesProperties(
        String lmsBaseUrl,
        String tmsBaseUrl,
        String airsBaseUrl,
        String apBaseUrl,
        String arBaseUrl) {
}
