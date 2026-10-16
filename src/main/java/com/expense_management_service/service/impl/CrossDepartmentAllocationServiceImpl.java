package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.AllocationContextResponse;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.ExpenseSplit;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.repository.ExpenseSplitRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.CrossDepartmentAllocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CrossDepartmentAllocationServiceImpl implements CrossDepartmentAllocationService {

    private final EmployeeCacheRepository employeeCacheRepository;
    private final ExpenseSplitRepository expenseSplitRepository;
    private final CurrentUserService currentUserService;

    @Override
    public AllocationContextResponse getAllocationContextForCurrentUser() {
        return new AllocationContextResponse(homeDepartmentOf(currentUserService.getCurrentUser().employeeId()).orElse(null));
    }

    @Override
    public Optional<String> homeDepartmentOf(String employeeId) {
        if (!StringUtils.hasText(employeeId)) {
            return Optional.empty();
        }
        return employeeCacheRepository.findByEmployeeId(employeeId)
                .map(EmployeeCache::getDepartmentUuid)
                .filter(StringUtils::hasText);
    }

    @Override
    public List<String> findLinesMissingReason(ExpenseReport report) {
        Optional<String> home = homeDepartmentOf(report.getEmployeeId());
        if (home.isEmpty() || report.getExpenseLineItems() == null) {
            return List.of();
        }
        List<String> messages = new ArrayList<>();
        for (ExpenseLineItem line : report.getExpenseLineItems()) {
            if (StringUtils.hasText(line.getAllocationReason())) {
                continue;
            }
            List<String> outside = allocationCostCenters(line, report).stream()
                    .filter(cc -> cc.getDepartmentUuid() != null
                            && !cc.getDepartmentUuid().toString().equalsIgnoreCase(home.get()))
                    .map(CostCenter::getCostCenterName)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (!outside.isEmpty()) {
                messages.add(describe(line) + " is charged to " + String.join(", ", outside)
                        + ", outside your department. Add a reason for the cross-department charge.");
            }
        }
        return messages;
    }

    /** Where the line's cost actually lands: its active splits, else its own cost center, else the report's. */
    private List<CostCenter> allocationCostCenters(ExpenseLineItem line, ExpenseReport report) {
        if (line.getLineItemId() != null) {
            List<CostCenter> splitCostCenters = expenseSplitRepository
                    .findByLineItem_LineItemIdAndRemovedAtIsNullOrderBySplitOrderAsc(line.getLineItemId()).stream()
                    .map(ExpenseSplit::getCostCenter)
                    .filter(Objects::nonNull)
                    .toList();
            if (!splitCostCenters.isEmpty()) {
                return splitCostCenters;
            }
        }
        if (line.getCostCenter() != null) {
            return List.of(line.getCostCenter());
        }
        return report.getCostCenter() != null ? List.of(report.getCostCenter()) : List.of();
    }

    private static String describe(ExpenseLineItem line) {
        String name = StringUtils.hasText(line.getMerchantName()) ? line.getMerchantName().trim()
                : line.getCategory() != null ? line.getCategory().getCategoryName() : null;
        String what = name != null ? "Expense '" + name + "'" : "An expense";
        return what + (line.getExpenseDate() != null ? " on " + line.getExpenseDate() : "");
    }
}
