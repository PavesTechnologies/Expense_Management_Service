package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ApprovalMatrix;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.entity.SystemConfiguration;
import com.expense_management_service.enums.ApproverType;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.repository.SystemConfigurationRepository;
import com.expense_management_service.service.ApproverResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class DefaultApproverResolverImpl implements ApproverResolver {

    /** SystemConfiguration key holding the fallback approver's employeeId, read via findByConfigKey. */
    static final String DEFAULT_APPROVER_CONFIG_KEY = "approval.default-approver-employee-id";

    private final EmployeeCacheRepository employeeCacheRepository;
    private final SystemConfigurationRepository systemConfigurationRepository;

    @Override
    public Optional<String> resolve(ApprovalMatrix matrixRow, String submittingEmployeeId) {
        ApproverType type = matrixRow.getApproverType();
        if (type == null) {
            return Optional.empty();
        }

        return switch (type) {
            case USER -> nonBlank(matrixRow.getApproverReference());

            case COST_CENTER_OWNER -> Optional.ofNullable(matrixRow.getCostCenter())
                    .map(CostCenter::getOwnerEmployeeId)
                    .filter(DefaultApproverResolverImpl::isNonBlank)
                    .or(() -> {
                        log.warn("Cost center {} has no ownerEmployeeId configured, falling back to default approver",
                                matrixRow.getCostCenter() != null ? matrixRow.getCostCenter().getCostCenterId() : null);
                        return fallbackToDefaultApprover();
                    });

            case MANAGER -> employeeCacheRepository.findByEmployeeId(submittingEmployeeId)
                    .map(EmployeeCache::getManagerEmployeeId)
                    .filter(DefaultApproverResolverImpl::isNonBlank)
                    .or(() -> {
                        log.warn("No manager on file in EmployeeCache for employeeId={}, falling back to default approver",
                                submittingEmployeeId);
                        return fallbackToDefaultApprover();
                    });

            // ROLE has no backing data source yet - UMS exposes no list-users-by-role endpoint.
            // Deliberately stubbed, matching the ExchangeRateProvider/StubExchangeRateProvider
            // precedent: unblocks the workflow now, swappable once that capability exists.
            case ROLE -> {
                log.warn("ROLE-based approver resolution ('{}') is not implemented - falling back to default approver",
                        matrixRow.getApproverReference());
                yield fallbackToDefaultApprover();
            }
        };
    }

    private Optional<String> fallbackToDefaultApprover() {
        return systemConfigurationRepository.findByConfigKey(DEFAULT_APPROVER_CONFIG_KEY)
                .map(SystemConfiguration::getConfigValue)
                .filter(DefaultApproverResolverImpl::isNonBlank);
    }

    private static Optional<String> nonBlank(String value) {
        return isNonBlank(value) ? Optional.of(value) : Optional.empty();
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
