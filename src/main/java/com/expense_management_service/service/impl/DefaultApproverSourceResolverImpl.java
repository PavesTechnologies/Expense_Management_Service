package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ApprovalLevelApprover;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.ApproverSourceType;
import com.expense_management_service.repository.DepartmentApproverRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.service.ApproverSourceResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class DefaultApproverSourceResolverImpl implements ApproverSourceResolver {

    private final EmployeeCacheRepository employeeCacheRepository;
    private final DepartmentApproverRepository departmentApproverRepository;

    @Override
    public Optional<String> resolve(ApprovalLevelApprover entry, ExpenseReport report) {
        ApproverSourceType type = entry.getSourceType();
        if (type == null) {
            return Optional.empty();
        }

        return switch (type) {
            case NAMED_USER -> nonBlank(entry.getSourceReference());

            case REPORTING_MANAGER -> employeeCacheRepository.findByEmployeeId(report.getEmployeeId())
                    .map(EmployeeCache::getManagerEmployeeId)
                    .filter(DefaultApproverSourceResolverImpl::isNonBlank)
                    .or(() -> {
                        log.warn("No manager on file in EmployeeCache for employeeId={}", report.getEmployeeId());
                        return Optional.empty();
                    });

            // Resolves against the SUBMITTER's own department, not the cost center's department -
            // consistent with REPORTING_MANAGER also being submitter-relative.
            case DEPARTMENT_OWNER -> employeeCacheRepository.findByEmployeeId(report.getEmployeeId())
                    .map(EmployeeCache::getDepartmentUuid)
                    .filter(DefaultApproverSourceResolverImpl::isNonBlank)
                    .flatMap(this::resolveDepartmentApprover)
                    .or(() -> {
                        log.warn("No department approver mapping found for submitter {}", report.getEmployeeId());
                        return Optional.empty();
                    });

            case COST_CENTER_OWNER -> Optional.ofNullable(report.getCostCenter())
                    .map(cc -> cc.getOwnerEmployeeId())
                    .filter(DefaultApproverSourceResolverImpl::isNonBlank)
                    .or(() -> {
                        log.warn("Cost center {} has no ownerEmployeeId configured",
                                report.getCostCenter() != null ? report.getCostCenter().getCostCenterId() : null);
                        return Optional.empty();
                    });
        };
    }

    private Optional<String> resolveDepartmentApprover(String departmentUuidString) {
        try {
            UUID departmentUuid = UUID.fromString(departmentUuidString);
            return departmentApproverRepository.findByDepartmentUuid(departmentUuid)
                    .map(com.expense_management_service.entity.DepartmentApprover::getApproverEmployeeId)
                    .filter(DefaultApproverSourceResolverImpl::isNonBlank);
        } catch (IllegalArgumentException ex) {
            log.warn("EmployeeCache.departmentUuid '{}' is not a valid UUID", departmentUuidString);
            return Optional.empty();
        }
    }

    private static Optional<String> nonBlank(String value) {
        return isNonBlank(value) ? Optional.of(value) : Optional.empty();
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
