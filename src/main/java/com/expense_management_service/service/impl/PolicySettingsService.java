package com.expense_management_service.service.impl;

import com.expense_management_service.repository.SystemConfigurationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Reads the cash advance recovery policy settings from system configuration, falling back to the policy defaults
 * when a setting has not been saved. Settings are edited from Masters, so changing a value takes effect without a
 * deployment.
 */
@Service
@RequiredArgsConstructor
public class PolicySettingsService {

    public static final String KEY_GRACE_DAYS = "cash-advance.recovery.grace-days";
    public static final String KEY_PAYROLL_REPORT_EMAIL = "cash-advance.recovery.payroll-report-email";
    public static final String KEY_RECONCILIATION_PERIOD = "reconciliation.default-period";

    public static final int DEFAULT_GRACE_DAYS = 1;
    public static final String DEFAULT_RECONCILIATION_PERIOD = "MONTHLY";

    private final SystemConfigurationRepository systemConfigurationRepository;

    /** Used when no saved setting exists. Set from PAYROLL_REPORT_EMAIL in .env, or changed later in Masters. */
    @Value("${cash-advance.recovery.payroll-report-email:}")
    private String defaultPayrollReportEmail = "";

    /** Days between the refund notice and the salary recovery report. Never less than zero. */
    public int graceDays() {
        return Math.max(0, parseInt(value(KEY_GRACE_DAYS), DEFAULT_GRACE_DAYS));
    }

    /**
     * Address that receives the salary recovery report for the payroll run. The saved setting wins; otherwise the
     * configured default is used. Empty means no report can be sent yet.
     */
    public String payrollReportEmail() {
        return value(KEY_PAYROLL_REPORT_EMAIL).map(String::trim).filter(s -> !s.isEmpty())
                .orElse(defaultPayrollReportEmail == null ? "" : defaultPayrollReportEmail.trim());
    }

    public String defaultReconciliationPeriod() {
        return value(KEY_RECONCILIATION_PERIOD).map(String::trim).filter(s -> !s.isEmpty())
                .orElse(DEFAULT_RECONCILIATION_PERIOD);
    }

    private Optional<String> value(String key) {
        return systemConfigurationRepository.findByConfigKey(key).map(c -> c.getConfigValue());
    }

    private static int parseInt(Optional<String> raw, int fallback) {
        return raw.map(String::trim).filter(s -> !s.isEmpty()).map(s -> {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }).orElse(fallback);
    }
}
