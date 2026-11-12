package com.expense_management_service.service.impl;

import com.expense_management_service.entity.SystemConfiguration;
import com.expense_management_service.repository.SystemConfigurationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicySettingsServiceTest {

    @Mock
    private SystemConfigurationRepository repository;

    private PolicySettingsService policy;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        policy = new PolicySettingsService(repository);
        ReflectionTestUtils.setField(policy, "defaultPayrollReportEmail", "lokeswari353@gmail.com");
    }

    @Test
    void payrollRecipient_usesTheConfiguredDefault_whenNothingIsSaved() {
        when(repository.findByConfigKey(anyString())).thenReturn(Optional.empty());

        assertThat(policy.payrollReportEmail()).isEqualTo("lokeswari353@gmail.com");
    }

    @Test
    void payrollRecipient_savedInMasters_overridesTheDefault() {
        when(repository.findByConfigKey(PolicySettingsService.KEY_PAYROLL_REPORT_EMAIL)).thenReturn(Optional.of(
                SystemConfiguration.builder().configKey(PolicySettingsService.KEY_PAYROLL_REPORT_EMAIL)
                        .configValue("payroll@company.test").build()));

        assertThat(policy.payrollReportEmail()).isEqualTo("payroll@company.test");
    }

    @Test
    void graceDays_defaultsToOne_andRejectsGarbageInput() {
        when(repository.findByConfigKey(PolicySettingsService.KEY_GRACE_DAYS)).thenReturn(Optional.empty());
        assertThat(policy.graceDays()).isEqualTo(1);

        when(repository.findByConfigKey(PolicySettingsService.KEY_GRACE_DAYS)).thenReturn(Optional.of(
                SystemConfiguration.builder().configKey(PolicySettingsService.KEY_GRACE_DAYS).configValue("abc").build()));
        assertThat(policy.graceDays()).isEqualTo(1);
    }
}
