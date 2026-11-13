package com.expense_management_service.service.impl;

import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceAdjustment;
import com.expense_management_service.entity.CashAdvanceRepayment;
import com.expense_management_service.repository.CashAdvanceAdjustmentRepository;
import com.expense_management_service.repository.CashAdvanceRepaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * THE authoritative outstanding balance of a cash advance:
 *
 * <pre>
 *   outstanding = amount - carriedOut - sum(ACTIVE adjustments) - sum(repayments)
 * </pre>
 *
 * Every financial decision (repayment limits, overdue checks, payroll deduction ceilings, recovery, carry-forward
 * capacity, dashboards, notifications, reconciliation) reads the balance through this class.
 *
 * <p>The stored {@code outstandingBalance} column is a cache kept for compatibility. Writers set it from this calculation
 * in the same transaction. It must never be used to decide anything. {@link #findDrift} reports any advance whose cache
 * disagrees with this calculation, with the difference. Discrepancies are reported, never hidden or overwritten.
 *
 * <p>Batch methods load every advance's records in two queries, so dashboards and drift checks do not query per advance.
 */
@Component
@RequiredArgsConstructor
public class CashAdvanceBalanceCalculator {

    private final CashAdvanceAdjustmentRepository adjustmentRepository;
    private final CashAdvanceRepaymentRepository repaymentRepository;

    /** Outstanding balance of one advance. Throws if the records imply a negative balance (never clamps). */
    public BigDecimal outstanding(CashAdvance advance) {
        int decimals = decimals(advance);
        return CashAdvanceBalanceRules.requireNonNegative(rawOutstanding(advance), decimals);
    }

    /**
     * The same figure without the non-negative check, for reporting and drift detection. A negative value is returned as
     * it is, so the problem is visible rather than hidden.
     */
    public BigDecimal rawOutstanding(CashAdvance advance) {
        UUID id = advance.getAdvanceId();
        BigDecimal offsets = activeOffsets(adjustmentRepository.findByCashAdvance_AdvanceId(id));
        BigDecimal repaid = repaymentTotal(repaymentRepository.findByCashAdvance_AdvanceId(id));
        return CashAdvanceBalanceRules.ledgerBalance(advance, offsets, repaid);
    }

    /**
     * Raw outstanding for many advances, keyed by advance id, using two queries in total. Advances with no records yet get
     * their full amount.
     */
    public Map<UUID, BigDecimal> rawOutstandingFor(Collection<CashAdvance> advances) {
        Map<UUID, BigDecimal> result = new HashMap<>();
        if (advances == null || advances.isEmpty()) {
            return result;
        }
        List<UUID> ids = advances.stream().map(CashAdvance::getAdvanceId).distinct().toList();
        Map<UUID, List<CashAdvanceAdjustment>> adjustments = adjustmentRepository.findByCashAdvance_AdvanceIdIn(ids).stream()
                .collect(Collectors.groupingBy(a -> a.getCashAdvance().getAdvanceId()));
        Map<UUID, List<CashAdvanceRepayment>> repayments = repaymentRepository.findByCashAdvance_AdvanceIdIn(ids).stream()
                .collect(Collectors.groupingBy(r -> r.getCashAdvance().getAdvanceId()));
        for (CashAdvance advance : advances) {
            UUID id = advance.getAdvanceId();
            BigDecimal offsets = activeOffsets(adjustments.getOrDefault(id, List.of()));
            BigDecimal repaid = repaymentTotal(repayments.getOrDefault(id, List.of()));
            result.put(id, CashAdvanceBalanceRules.ledgerBalance(advance, offsets, repaid));
        }
        return result;
    }

    /** The one rule for summing offsets: only ACTIVE rows count. Every caller that sums offsets uses this. */
    public static BigDecimal activeOffsets(List<CashAdvanceAdjustment> rows) {
        if (rows == null) {
            return BigDecimal.ZERO;
        }
        return rows.stream()
                .filter(CashAdvanceAdjustment::isActive)
                .map(CashAdvanceAdjustment::getAdjustedAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The one rule for summing repayments. */
    public static BigDecimal repaymentTotal(List<CashAdvanceRepayment> rows) {
        if (rows == null) {
            return BigDecimal.ZERO;
        }
        return rows.stream()
                .map(CashAdvanceRepayment::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Compares every advance's cached balance with the calculation. Read-only: nothing is changed. A missing cache value
     * counts as a discrepancy, with a null stored value and no difference.
     */
    public List<Drift> findDrift(List<CashAdvance> advances) {
        Map<UUID, BigDecimal> computed = rawOutstandingFor(advances);
        List<Drift> drift = new ArrayList<>();
        for (CashAdvance a : advances) {
            BigDecimal calc = computed.get(a.getAdvanceId());
            BigDecimal stored = a.getOutstandingBalance();
            if (stored == null) {
                drift.add(new Drift(a.getAdvanceId(), a.getEmployeeId(), null, calc, null));
            } else if (stored.compareTo(calc) != 0) {
                drift.add(new Drift(a.getAdvanceId(), a.getEmployeeId(), stored, calc, stored.subtract(calc)));
            }
        }
        return drift;
    }

    private static int decimals(CashAdvance advance) {
        return advance.getCurrency() != null ? advance.getCurrency().getDecimalPlaces() : 2;
    }

    /**
     * One advance whose cached balance does not match its records. {@code difference} is stored minus calculated (null when
     * there is no cached value). A positive difference means the cache shows more owed than the records support.
     */
    public record Drift(UUID advanceId, String employeeId, BigDecimal stored, BigDecimal computed, BigDecimal difference) {
    }
}
