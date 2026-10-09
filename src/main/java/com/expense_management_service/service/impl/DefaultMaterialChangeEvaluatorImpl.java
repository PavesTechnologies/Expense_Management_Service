package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ApprovalLevelInstance;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.SystemConfiguration;
import com.expense_management_service.repository.SystemConfigurationRepository;
import com.expense_management_service.service.MaterialChangeEvaluator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Checks four independent dimensions - stops at nothing (unlike {@code
 * FinanceVerificationEligibilityChecker}, every dimension is evaluated so the reason can be logged
 * even though the caller only needs the boolean). Thresholds are {@code SystemConfiguration}-driven,
 * same pattern as {@code SlaPolicyServiceImpl}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class DefaultMaterialChangeEvaluatorImpl implements MaterialChangeEvaluator {

    static final String AMOUNT_THRESHOLD_PERCENT_KEY = "finance.material-change.amount-threshold-percent";
    static final String AMOUNT_THRESHOLD_ABSOLUTE_KEY = "finance.material-change.amount-threshold-absolute";
    /** 10% is the default; an org with no configured override still gets a sane material-change gate. */
    static final BigDecimal DEFAULT_AMOUNT_THRESHOLD_PERCENT = BigDecimal.valueOf(10);

    private final SystemConfigurationRepository systemConfigurationRepository;

    @Override
    public boolean hasMaterialChange(ExpenseReport report, ApprovalLevelInstance materializedSnapshot) {
        boolean amountChanged = isAmountChangeMaterial(report, materializedSnapshot);
        boolean costCenterChanged = isCostCenterChangeMaterial(report, materializedSnapshot);
        boolean clientBillableChanged = isClientBillableChangeMaterial(report, materializedSnapshot);
        boolean glMappingChanged = isGlMappingChangeMaterial(report, materializedSnapshot);

        if (amountChanged || costCenterChanged || clientBillableChanged || glMappingChanged) {
            log.info("Material change detected on report {}: amount={} costCenter={} clientBillable={} glMapping={}",
                    report.getReportId(), amountChanged, costCenterChanged, clientBillableChanged, glMappingChanged);
            return true;
        }
        return false;
    }

    @Override
    public String computeGlAccountFingerprint(ExpenseReport report) {
        if (report.getExpenseLineItems() == null) {
            return "";
        }
        return report.getExpenseLineItems().stream()
                .filter(li -> li.getCategory() != null && li.getCategory().getGlAccount() != null)
                .map(li -> li.getLineItemId() + "=" + li.getCategory().getGlAccount().getGlAccountId())
                .sorted()
                .collect(Collectors.joining(";"));
    }

    private boolean isAmountChangeMaterial(ExpenseReport report, ApprovalLevelInstance snapshot) {
        BigDecimal before = snapshot.getMaterializedTotalAmount();
        BigDecimal after = report.getTotalAmount();
        if (before == null || after == null) {
            return false;
        }
        BigDecimal delta = after.subtract(before).abs();
        if (delta.signum() == 0) {
            return false;
        }
        BigDecimal absoluteThreshold = resolveAbsoluteThreshold();
        if (absoluteThreshold.signum() > 0 && delta.compareTo(absoluteThreshold) >= 0) {
            return true;
        }
        if (before.signum() == 0) {
            return true;
        }
        BigDecimal percentChange = delta.multiply(BigDecimal.valueOf(100)).divide(before.abs(), 4, RoundingMode.HALF_UP);
        return percentChange.compareTo(resolvePercentThreshold()) >= 0;
    }

    private boolean isCostCenterChangeMaterial(ExpenseReport report, ApprovalLevelInstance snapshot) {
        UUID before = snapshot.getMaterializedCostCenterId();
        UUID after = report.getCostCenter() != null ? report.getCostCenter().getCostCenterId() : null;
        return !Objects.equals(before, after);
    }

    private boolean isClientBillableChangeMaterial(ExpenseReport report, ApprovalLevelInstance snapshot) {
        boolean before = Boolean.TRUE.equals(snapshot.getMaterializedClientBillableAny());
        boolean after = report.getExpenseLineItems() != null && report.getExpenseLineItems().stream()
                .anyMatch(li -> Boolean.TRUE.equals(li.getClientBillable()));
        return before != after;
    }

    private boolean isGlMappingChangeMaterial(ExpenseReport report, ApprovalLevelInstance snapshot) {
        return !Objects.equals(snapshot.getMaterializedGlAccountFingerprint(), computeGlAccountFingerprint(report));
    }

    private BigDecimal resolvePercentThreshold() {
        return systemConfigurationRepository.findByConfigKey(AMOUNT_THRESHOLD_PERCENT_KEY)
                .map(SystemConfiguration::getConfigValue)
                .map(this::parseDecimal)
                .orElse(DEFAULT_AMOUNT_THRESHOLD_PERCENT);
    }

    private BigDecimal resolveAbsoluteThreshold() {
        return systemConfigurationRepository.findByConfigKey(AMOUNT_THRESHOLD_ABSOLUTE_KEY)
                .map(SystemConfiguration::getConfigValue)
                .map(this::parseDecimal)
                .orElse(BigDecimal.ZERO);
    }

    private BigDecimal parseDecimal(String value) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ex) {
            log.warn("SystemConfiguration threshold value '{}' is not a valid decimal, ignoring", value);
            return BigDecimal.ZERO;
        }
    }
}
