package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceAdjustment;
import com.expense_management_service.entity.CashAdvanceCarryForward;
import com.expense_management_service.entity.CashAdvanceRepayment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CashAdvanceReconciliationCalculatorTest {

    private static final LocalDate END = LocalDate.of(2026, 10, 31);
    private static final LocalDateTime BEFORE_END = LocalDateTime.of(2026, 10, 15, 10, 0);
    private static final LocalDateTime AFTER_END = LocalDateTime.of(2026, 11, 5, 10, 0);

    // ------------------------------------------------------------ periods

    @Test
    void monthlyPeriod_coversTheWholeMonth() {
        var p = CashAdvanceReconciliationCalculator.period("MONTHLY", LocalDate.of(2026, 10, 23), 0);
        assertThat(p.start()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(p.end()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    void quarterlyHalfYearlyAndFinancialYear_refusedUntilTheFinancialYearStartIsConfigured() {
        for (String type : List.of("QUARTERLY", "HALF_YEARLY", "FINANCIAL_YEAR")) {
            assertThatThrownBy(() -> CashAdvanceReconciliationCalculator.period(type, LocalDate.of(2026, 10, 23), 0))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("has not confirmed the financial-year start");
        }
    }

    @Test
    void withAnAprilStart_quarterHalfAndYearBoundariesAreCorrect() {
        LocalDate october = LocalDate.of(2026, 10, 23);
        var quarter = CashAdvanceReconciliationCalculator.period("QUARTERLY", october, 4);
        assertThat(quarter.start()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(quarter.end()).isEqualTo(LocalDate.of(2026, 12, 31));

        var half = CashAdvanceReconciliationCalculator.period("HALF_YEARLY", october, 4);
        assertThat(half.start()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(half.end()).isEqualTo(LocalDate.of(2027, 3, 31));

        var year = CashAdvanceReconciliationCalculator.period("FINANCIAL_YEAR", LocalDate.of(2027, 2, 10), 4);
        assertThat(year.start()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(year.end()).isEqualTo(LocalDate.of(2027, 3, 31));
    }

    @Test
    void unknownPeriodType_isRejected() {
        assertThatThrownBy(() -> CashAdvanceReconciliationCalculator.period("WEEKLY", LocalDate.of(2026, 10, 23), 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------ expected balances

    @Test
    void expectedBalance_isAmountLessRepaymentsAndAdjustments_andMatchesTheStoredBalance_soNothingIsFlagged() {
        CashAdvance advance = disbursed(1000, 600, "EMP001");
        var repayment = repayment(advance, 400, BEFORE_END);

        var lines = snapshot(List.of(advance), List.of(), List.of(repayment), List.of());

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).expected()).isEqualByComparingTo("600");
        assertThat(lines.get(0).discrepancy()).isEqualByComparingTo("0");
        assertThat(lines.get(0).flagged()).isFalse();
    }

    @Test
    void storedBalanceThatDisagreesWithTheLedger_isFlagged_whenNoLaterMovementExplainsIt() {
        CashAdvance advance = disbursed(1000, 500, "EMP001");   // stored 500, ledger says 1000

        var lines = snapshot(List.of(advance), List.of(), List.of(), List.of());

        assertThat(lines.get(0).expected()).isEqualByComparingTo("1000");
        assertThat(lines.get(0).discrepancy()).isEqualByComparingTo("-500");
        assertThat(lines.get(0).flagged()).isTrue();
    }

    @Test
    void aMismatchExplainedByAMovementAfterThePeriodEnd_isNotFlagged() {
        CashAdvance advance = disbursed(1000, 500, "EMP001");
        var later = repayment(advance, 500, AFTER_END);

        var lines = snapshot(List.of(advance), List.of(), List.of(later), List.of());

        assertThat(lines.get(0).discrepancy()).isEqualByComparingTo("-500");
        assertThat(lines.get(0).laterMovements()).isEqualTo(1);
        assertThat(lines.get(0).flagged()).isFalse();
    }

    @Test
    void advanceNotDisbursedByThePeriodEnd_isNotPartOfTheLedgerYet() {
        CashAdvance advance = disbursed(1000, 1000, "EMP001");
        advance.setDisbursedAt(LocalDateTime.of(2026, 11, 2, 9, 0));

        assertThat(snapshot(List.of(advance), List.of(), List.of(), List.of())).isEmpty();
    }

    @Test
    void carriedInAndCarriedOut_adjustTheExpectedBalance() {
        CashAdvance source = disbursed(1000, 700, "EMP001");
        CashAdvance destination = disbursed(500, 800, "EMP001");   // 500 + 300 carried in
        var allocation = carryForward(source, destination, 300, BEFORE_END, null);

        var lines = snapshot(List.of(source, destination), List.of(allocation), List.of(), List.of());

        assertThat(lines.get(0).expected()).isEqualByComparingTo("700");   // source: 1000 - 300 carried out
        assertThat(lines.get(1).expected()).isEqualByComparingTo("800");   // destination: 500 + 300 carried in
        assertThat(lines.stream().noneMatch(l -> l.flagged())).isTrue();
    }

    @Test
    void reversalBeforeThePeriodEnd_removesTheAllocationFromTheExpectedBalance() {
        CashAdvance source = disbursed(1000, 1000, "EMP001");
        CashAdvance destination = disbursed(500, 500, "EMP001");
        var reversed = carryForward(source, destination, 300, BEFORE_END, LocalDateTime.of(2026, 10, 20, 9, 0));

        var lines = snapshot(List.of(source, destination), List.of(reversed), List.of(), List.of());

        assertThat(lines.get(0).expected()).isEqualByComparingTo("1000");
        assertThat(lines.get(1).expected()).isEqualByComparingTo("500");
    }

    @Test
    void reversalAfterThePeriodEnd_stillCountsAtThePeriodEnd() {
        CashAdvance source = disbursed(1000, 700, "EMP001");
        CashAdvance destination = disbursed(500, 800, "EMP001");
        var reversedLater = carryForward(source, destination, 300, BEFORE_END, AFTER_END);

        var lines = snapshot(List.of(source, destination), List.of(reversedLater), List.of(), List.of());

        assertThat(lines.get(0).expected()).isEqualByComparingTo("700");
        assertThat(lines.get(0).laterMovements()).isEqualTo(1);
    }

    @Test
    void adjustmentDatedAfterThePeriodEnd_doesNotReduceTheExpectedBalanceAtThePeriodEnd() {
        CashAdvance advance = disbursed(1000, 1000, "EMP001");
        var later = adjustment(advance, 200, AFTER_END);

        var lines = snapshot(List.of(advance), List.of(), List.of(), List.of(later));

        assertThat(lines.get(0).expected()).isEqualByComparingTo("1000");
        assertThat(lines.get(0).laterMovements()).isEqualTo(1);
    }

    @Test
    void theSnapshotDoesNotChangeTheInputs() {
        CashAdvance advance = disbursed(1000, 500, "EMP001");
        BigDecimal before = advance.getOutstandingBalance();

        snapshot(List.of(advance), List.of(), List.of(), List.of());

        assertThat(advance.getOutstandingBalance()).isEqualByComparingTo(before);
    }

    // ------------------------------------------------------------ helpers

    private static List<CashAdvanceReconciliationCalculator.LineSnapshot> snapshot(
            List<CashAdvance> advances, List<CashAdvanceCarryForward> cfs,
            List<CashAdvanceRepayment> repayments, List<CashAdvanceAdjustment> adjustments) {
        return CashAdvanceReconciliationCalculator.snapshot(END, advances, cfs, repayments, adjustments);
    }

    private static CashAdvance disbursed(double amount, double outstanding, String employeeId) {
        return CashAdvance.builder()
                .advanceId(UUID.randomUUID())
                .employeeId(employeeId)
                .amount(BigDecimal.valueOf(amount))
                .outstandingBalance(BigDecimal.valueOf(outstanding))
                .status("DISBURSED")
                .disbursedAt(LocalDateTime.of(2026, 9, 1, 9, 0))
                .build();
    }

    private static CashAdvanceRepayment repayment(CashAdvance advance, double amount, LocalDateTime at) {
        CashAdvanceRepayment r = new CashAdvanceRepayment();
        r.setCashAdvance(advance);
        r.setAmount(BigDecimal.valueOf(amount));
        r.setRepaidAt(at);
        return r;
    }

    private static CashAdvanceAdjustment adjustment(CashAdvance advance, double amount, LocalDateTime at) {
        CashAdvanceAdjustment a = new CashAdvanceAdjustment();
        a.setCashAdvance(advance);
        a.setAdjustedAmount(BigDecimal.valueOf(amount));
        a.setAdjustedAt(at);
        return a;
    }

    private static CashAdvanceCarryForward carryForward(CashAdvance source, CashAdvance destination, double amount,
                                                        LocalDateTime appliedAt, LocalDateTime reversedAt) {
        return CashAdvanceCarryForward.builder()
                .carryForwardId(UUID.randomUUID())
                .sourceAdvance(source)
                .destinationAdvance(destination)
                .amount(BigDecimal.valueOf(amount))
                .status(reversedAt == null ? CashAdvanceCarryForward.APPLIED : CashAdvanceCarryForward.REVERSED)
                .appliedAt(appliedAt)
                .reversedAt(reversedAt)
                .build();
    }
}
