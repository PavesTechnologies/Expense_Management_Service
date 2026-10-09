package com.expense_management_service.service.impl;

import com.expense_management_service.entity.SystemConfiguration;
import com.expense_management_service.repository.SystemConfigurationRepository;
import com.expense_management_service.service.SlaPolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class SlaPolicyServiceImpl implements SlaPolicyService {

    static final String SLA_DAYS_CONFIG_KEY = "approval.sla.business-days";
    static final int DEFAULT_SLA_BUSINESS_DAYS = 3;

    private final SystemConfigurationRepository systemConfigurationRepository;

    @Override
    public int resolveSlaBusinessDays() {
        return systemConfigurationRepository.findByConfigKey(SLA_DAYS_CONFIG_KEY)
                .map(SystemConfiguration::getConfigValue)
                .map(this::parseSlaDays)
                .orElse(DEFAULT_SLA_BUSINESS_DAYS);
    }

    private int parseSlaDays(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("SystemConfiguration '{}' has a non-numeric value '{}', using default of {} business days",
                    SLA_DAYS_CONFIG_KEY, value, DEFAULT_SLA_BUSINESS_DAYS);
            return DEFAULT_SLA_BUSINESS_DAYS;
        }
    }
}
