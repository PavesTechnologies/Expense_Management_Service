package com.expense_management_service.service.impl;

import com.expense_management_service.entity.SystemConfiguration;
import com.expense_management_service.repository.SystemConfigurationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlaPolicyServiceImplTest {

    @Mock
    private SystemConfigurationRepository systemConfigurationRepository;

    private SlaPolicyServiceImpl slaPolicyService;

    @BeforeEach
    void setUp() {
        slaPolicyService = new SlaPolicyServiceImpl(systemConfigurationRepository);
    }

    @Test
    void resolveSlaBusinessDays_returnsConfiguredValue_whenPresent() {
        when(systemConfigurationRepository.findByConfigKey(SlaPolicyServiceImpl.SLA_DAYS_CONFIG_KEY))
                .thenReturn(Optional.of(SystemConfiguration.builder().configValue("5").build()));

        assertThat(slaPolicyService.resolveSlaBusinessDays()).isEqualTo(5);
    }

    @Test
    void resolveSlaBusinessDays_returnsDefault_whenNotConfigured() {
        when(systemConfigurationRepository.findByConfigKey(SlaPolicyServiceImpl.SLA_DAYS_CONFIG_KEY))
                .thenReturn(Optional.empty());

        assertThat(slaPolicyService.resolveSlaBusinessDays()).isEqualTo(SlaPolicyServiceImpl.DEFAULT_SLA_BUSINESS_DAYS);
    }

    @Test
    void resolveSlaBusinessDays_returnsDefault_whenConfiguredValueIsNotNumeric() {
        when(systemConfigurationRepository.findByConfigKey(SlaPolicyServiceImpl.SLA_DAYS_CONFIG_KEY))
                .thenReturn(Optional.of(SystemConfiguration.builder().configValue("not-a-number").build()));

        assertThat(slaPolicyService.resolveSlaBusinessDays()).isEqualTo(SlaPolicyServiceImpl.DEFAULT_SLA_BUSINESS_DAYS);
    }
}
