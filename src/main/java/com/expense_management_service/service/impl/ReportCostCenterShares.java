package com.expense_management_service.service.impl;

import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.ExpenseSplit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where a report's cost lands, per cost center: each active split's allocated amount, and each unsplit
 * line's base amount on the line's own cost center (the report's when the line has none). This is the same
 * attribution {@code BudgetEncumbranceServiceImpl} reserves against, so a split is honoured whichever way
 * the money reaches the budget - a reservation, a cash-advance offset, or an AP payment.
 */
final class ReportCostCenterShares {

    /** One cost center's part of an amount. */
    record Share(CostCenter costCenter, BigDecimal amount) {
    }

    private ReportCostCenterShares() {
    }

    /** The report's base-currency cost per cost center, in line order; zero shares are left out. */
    static List<Share> of(ExpenseReport report) {
        Map<UUID, Share> byCostCenter = new LinkedHashMap<>();
        List<ExpenseLineItem> lineItems = report.getExpenseLineItems() != null ? report.getExpenseLineItems() : List.of();
        for (ExpenseLineItem lineItem : lineItems) {
            List<ExpenseSplit> splits = lineItem.getExpenseSplits() == null ? List.of()
                    : lineItem.getExpenseSplits().stream().filter(s -> s.getRemovedAt() == null).toList();
            if (!splits.isEmpty()) {
                splits.forEach(split -> add(byCostCenter, split.getCostCenter(), split.getAllocatedAmount()));
            } else {
                CostCenter costCenter = lineItem.getCostCenter() != null ? lineItem.getCostCenter() : report.getCostCenter();
                add(byCostCenter, costCenter, lineItem.getBaseAmount());
            }
        }
        return byCostCenter.values().stream().filter(share -> share.amount().signum() > 0).toList();
    }

    /**
     * Pro-rates {@code amount} over the report's cost centers in proportion to {@link #of}. Each share is
     * rounded to 2 decimals and the last takes the remainder, so the parts always add up to {@code amount}
     * exactly. A report with no attributable lines puts the whole amount on its own cost center.
     */
    static List<Share> distribute(BigDecimal amount, ExpenseReport report) {
        if (amount == null || amount.signum() <= 0) {
            return List.of();
        }
        List<Share> shares = of(report);
        BigDecimal total = shares.stream().map(Share::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (shares.isEmpty() || total.signum() <= 0) {
            return report.getCostCenter() != null ? List.of(new Share(report.getCostCenter(), amount)) : List.of();
        }
        List<Share> distributed = new ArrayList<>(shares.size());
        BigDecimal assigned = BigDecimal.ZERO;
        for (int i = 0; i < shares.size(); i++) {
            Share share = shares.get(i);
            BigDecimal part = i == shares.size() - 1
                    ? amount.subtract(assigned)
                    : amount.multiply(share.amount()).divide(total, 2, RoundingMode.HALF_UP);
            assigned = assigned.add(part);
            if (part.signum() > 0) {
                distributed.add(new Share(share.costCenter(), part));
            }
        }
        return distributed;
    }

    private static void add(Map<UUID, Share> byCostCenter, CostCenter costCenter, BigDecimal amount) {
        if (costCenter == null || amount == null) {
            return;
        }
        byCostCenter.merge(costCenter.getCostCenterId(), new Share(costCenter, amount),
                (a, b) -> new Share(a.costCenter(), a.amount().add(b.amount())));
    }
}
