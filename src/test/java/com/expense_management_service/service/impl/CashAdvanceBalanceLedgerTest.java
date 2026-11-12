package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the settlement balance ledger. They cover the rules that must hold for every
 * balance change: partial settlement stays open, zero closes, negative is rejected, and every change
 * is audited. Transaction rollback needs MySQL and is not covered here.
 */
@ExtendWith(MockitoExtension.class)
class CashAdvanceBalanceLedgerTest {

    @Mock private AuditLogRepository auditLogRepository;
    @Mock private ExpenseReportRepository expenseReportRepository;

    private CashAdvanceBalanceLedger ledger;
    private CashAdvance advance;

    @BeforeEach
    void setUp() {
        ledger = new CashAdvanceBalanceLedger(auditLogRepository, expenseReportRepository);
        Currency inr = Currency.builder().currencyId(UUID.randomUUID()).currencyCode("INR").decimalPlaces(2).build();
        advance = CashAdvance.builder()
                .advanceId(UUID.randomUUID())
                .amount(new BigDecimal("15000.00"))
                .outstandingBalance(new BigDecimal("15000.00"))
                .status("IN_PROGRESS")
                .currency(inr)
                .build();
    }

    @Test
    void closing_stampsSettledAtAndSettledBy_andReopening_clearsThem() {
        ledger.applyBalance(advance, BigDecimal.ZERO, "PAYROLL_DEDUCTION_POSTED", "PAYROLL", "salary deduction");

        assertEquals("CLOSED", advance.getStatus());
        assertEquals("PAYROLL", advance.getSettledBy());
        assertTrue(advance.getSettledAt() != null);

        ledger.applyBalance(advance, new BigDecimal("500.00"), "CASH_ADVANCE_BALANCE_RECALCULATED", "SYSTEM", "reopened");

        assertEquals("SETTLEMENT_PENDING", advance.getStatus());
        assertEquals(null, advance.getSettledAt());
        assertEquals(null, advance.getSettledBy());
    }

    @Test
    void partialSettlement_leavesTheAdvanceOpen_andIsAudited() {
        ledger.applyBalance(advance, new BigDecimal("10000.00"), "REPAYMENT_RECORDED", "5100009", "partial repayment");

        assertEquals(0, new BigDecimal("10000.00").compareTo(advance.getOutstandingBalance()));
        assertEquals("SETTLEMENT_PENDING", advance.getStatus());
        assertAuditedOnce("REPAYMENT_RECORDED", "5100009");
    }

    @Test
    void zeroBalance_closesTheAdvance_andIsAudited() {
        ledger.applyBalance(advance, BigDecimal.ZERO, "REPAYMENT_RECORDED", "5100009", "full repayment");

        assertEquals(0, BigDecimal.ZERO.compareTo(advance.getOutstandingBalance()));
        assertEquals("CLOSED", advance.getStatus());
        assertAuditedOnce("REPAYMENT_RECORDED", "5100009");
    }

    @Test
    void owedAmount_neverClosesTheAdvance() {
        // Regression: a non-zero balance (INR 133 here) must never be reported as CLOSED
        advance.setOutstandingBalance(new BigDecimal("133.00"));
        advance.setStatus("IN_PROGRESS");

        ledger.applyBalance(advance, new BigDecimal("133.00"), "CASH_ADVANCE_BALANCE_RECALCULATED", "SYSTEM", "recalc");

        assertEquals("SETTLEMENT_PENDING", advance.getStatus());
        assertTrue(advance.getOutstandingBalance().signum() > 0);
    }

    @Test
    void negativeBalance_isRejected_andNothingIsChangedOrAudited() {
        assertThrows(BusinessRuleViolationException.class,
                () -> ledger.applyBalance(advance, new BigDecimal("-0.01"), "REPAYMENT_RECORDED", "5100009", "overpay"));

        assertEquals(0, new BigDecimal("15000.00").compareTo(advance.getOutstandingBalance()));
        assertEquals("IN_PROGRESS", advance.getStatus());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void sameBalanceAndStatus_writesNoAuditRow() {
        advance.setStatus("SETTLEMENT_PENDING");
        // 15000 and 15000.00 are the same balance, so nothing changed
        ledger.applyBalance(advance, new BigDecimal("15000"), "CASH_ADVANCE_BALANCE_RECALCULATED", "SYSTEM", "no-op");

        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void balanceRoundsToTheCurrency_beforeTheStatusIsDecided() {
        // 0.004 at 2 decimals is zero, so the advance closes at currency precision
        ledger.applyBalance(advance, new BigDecimal("0.004"), "REPAYMENT_RECORDED", "5100009", "residue");

        assertEquals("CLOSED", advance.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(advance.getOutstandingBalance()));
    }

    @Test
    void recordChange_writesAnAuditRowWithTheGivenDetails() {
        UUID adjustmentId = UUID.randomUUID();

        ledger.recordChange("CashAdvanceAdjustment", adjustmentId, "ADJUSTMENT_CAPPED",
                "Adjusted: 500.00", "Adjusted: 300.00", "SYSTEM", "capped");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog row = captor.getValue();
        assertEquals("CashAdvanceAdjustment", row.getEntityName());
        assertEquals(adjustmentId, row.getEntityId());
        assertEquals("ADJUSTMENT_CAPPED", row.getAction());
        assertEquals("Adjusted: 500.00", row.getOldValue());
        assertEquals("Adjusted: 300.00", row.getNewValue());
        assertEquals("SYSTEM", row.getPerformedBy());
    }

    // ---------------------------------------------------------------------
    // Reimbursements owed to the employee keep the advance open after its balance reaches zero
    // ---------------------------------------------------------------------

    @Test
    void fullOffset_withAReimbursementStillOwed_staysAwaitingReimbursement() {
        // The 1000 advance is offset in full by a 11,110.98 report; the other 10,110.98 is still owed to the employee
        ExpenseReport report = linkedReport("11110.98", null);
        when(expenseReportRepository.findByCashAdvance_AdvanceIdOrderByCreatedAtAsc(advance.getAdvanceId())).thenReturn(List.of(report));

        ledger.applyBalance(advance, BigDecimal.ZERO, "AUTO_RECONCILED_ON_APPROVAL", "SYSTEM", "offset");

        assertEquals(0, BigDecimal.ZERO.compareTo(advance.getOutstandingBalance()));
        assertEquals("AWAITING_REIMBURSEMENT", advance.getStatus());
    }

    @Test
    void partialPayment_ofAReimbursement_keepsTheAdvanceOpen() {
        ExpenseReport report = linkedReport("10110.98", "5000.00");
        when(expenseReportRepository.findByCashAdvance_AdvanceIdOrderByCreatedAtAsc(advance.getAdvanceId())).thenReturn(List.of(report));
        advance.setOutstandingBalance(BigDecimal.ZERO);

        ledger.refreshStatus(advance, "5100009", "partial payment");

        assertEquals("AWAITING_REIMBURSEMENT", advance.getStatus());
    }

    @Test
    void finalPayment_ofTheLastReimbursement_closesTheAdvanceOnRecheck() {
        ExpenseReport report = linkedReport("10110.98", "10110.98");
        when(expenseReportRepository.findByCashAdvance_AdvanceIdOrderByCreatedAtAsc(advance.getAdvanceId())).thenReturn(List.of(report));
        advance.setOutstandingBalance(BigDecimal.ZERO);
        advance.setStatus("SETTLEMENT_PENDING");

        ledger.refreshStatus(advance, "5100009", "reimbursement paid");

        assertEquals("CLOSED", advance.getStatus());
        assertAuditedOnce("CASH_ADVANCE_STATUS_RECHECKED", "5100009");
    }

    @Test
    void multipleLinkedReports_stayOpenUntilEveryReimbursementIsPaid() {
        ExpenseReport paid = linkedReport("3000.00", "3000.00");
        ExpenseReport unpaid = linkedReport("7000.00", null);
        when(expenseReportRepository.findByCashAdvance_AdvanceIdOrderByCreatedAtAsc(advance.getAdvanceId()))
                .thenReturn(List.of(paid, unpaid));
        advance.setOutstandingBalance(BigDecimal.ZERO);

        ledger.refreshStatus(advance, "SYSTEM", "recheck");
        assertEquals("AWAITING_REIMBURSEMENT", advance.getStatus());

        unpaid.setPaidAmount(new BigDecimal("7000.00"));
        ledger.refreshStatus(advance, "SYSTEM", "recheck");
        assertEquals("CLOSED", advance.getStatus());
    }

    @Test
    void aReportWithNothingOwed_doesNotBlockClosure() {
        // A report fully covered by the offset has a zero reimbursable amount, so nothing is owed
        ExpenseReport covered = linkedReport("0.00", null);
        when(expenseReportRepository.findByCashAdvance_AdvanceIdOrderByCreatedAtAsc(advance.getAdvanceId())).thenReturn(List.of(covered));

        ledger.applyBalance(advance, BigDecimal.ZERO, "AUTO_RECONCILED_ON_APPROVAL", "SYSTEM", "offset");

        assertEquals("CLOSED", advance.getStatus());
    }

    @Test
    void anOverpaidReimbursement_countsAsPaid_andDoesNotBlockClosure() {
        // AP only records an exact payment, so this is a defensive case: more paid than owed is not still owed
        ExpenseReport report = linkedReport("10110.98", "10200.00");
        when(expenseReportRepository.findByCashAdvance_AdvanceIdOrderByCreatedAtAsc(advance.getAdvanceId())).thenReturn(List.of(report));
        advance.setOutstandingBalance(BigDecimal.ZERO);

        ledger.refreshStatus(advance, "SYSTEM", "recheck");

        assertEquals("CLOSED", advance.getStatus());
    }

    @Test
    void anAdvanceWithNoLinkedReports_closesAtZeroAsBefore() {
        when(expenseReportRepository.findByCashAdvance_AdvanceIdOrderByCreatedAtAsc(advance.getAdvanceId())).thenReturn(List.of());

        ledger.applyBalance(advance, BigDecimal.ZERO, "REPAYMENT_RECORDED", "5100009", "repaid");

        assertEquals("CLOSED", advance.getStatus());
    }

    private static ExpenseReport linkedReport(String reimbursable, String paid) {
        return ExpenseReport.builder()
                .reportId(UUID.randomUUID())
                .reimbursableAmount(new BigDecimal(reimbursable))
                .paidAmount(paid == null ? null : new BigDecimal(paid))
                .build();
    }

    private void assertAuditedOnce(String action, String actor) {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog row = captor.getValue();
        assertEquals("CashAdvance", row.getEntityName());
        assertEquals(advance.getAdvanceId(), row.getEntityId());
        assertEquals(action, row.getAction());
        assertEquals(actor, row.getPerformedBy());
    }
}
