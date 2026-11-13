package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.dto.request.CashAdvanceAdjustmentRequest;
import com.expense_management_service.dto.response.CashAdvanceAdjustmentResponse;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceAdjustment;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.mapper.CashAdvanceAdjustmentMapper;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.CashAdvanceAdjustmentRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.ApprovalEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Milestone 1: reversal of cash advance adjustments, reapplication after reversal, duplicate-active prevention,
 * paid-report protection, authorisation and lock order. Balances are computed from active rows only.
 */
@ExtendWith(MockitoExtension.class)
class CashAdvanceAdjustmentReversalTest {

    @Mock private CashAdvanceBudgetMovements cashAdvanceBudgetMovements;
    @Mock private CashAdvanceAdjustmentRepository adjustmentRepository;
    @Mock private CashAdvanceRepository cashAdvanceRepository;
    @Mock private ExpenseReportRepository expenseReportRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private ApprovalEventPublisher approvalEventPublisher;
    @Mock private CurrentUserService currentUserService;
    @Mock private CashAdvanceBalanceLedger cashAdvanceBalanceLedger;
    @Spy private CashAdvanceAdjustmentMapper mapper = new CashAdvanceAdjustmentMapper();

    @InjectMocks private CashAdvanceAdjustmentServiceImpl service;

    private UUID advanceId;
    private UUID reportId;
    private CashAdvance advance;
    private ExpenseReport report;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "FINANCE001", null, List.of(new SimpleGrantedAuthority("ROLE_FINANCE_EXECUTIVE"))));
        advanceId = UUID.randomUUID();
        reportId = UUID.randomUUID();
        advance = CashAdvance.builder()
                .advanceId(advanceId)
                .employeeId("EMP001")
                .amount(new BigDecimal("1000.00"))
                .outstandingBalance(new BigDecimal("1000.00"))
                .status("DISBURSED")
                .build();
        report = ExpenseReport.builder()
                .reportId(reportId)
                .employeeId("EMP001")
                .reportNumber("ER-1")
                .title("Trip")
                .reportStatus(ReportStatus.APPROVED)
                .paymentRoutingStatus(PaymentRoutingStatus.NONE)
                .build();
        lenient().when(currentUserService.getEmployeeId()).thenReturn("FINANCE001");
        lenient().when(cashAdvanceBalanceLedger.statusFor(any(), any(), anyInt()))
                .thenAnswer(inv -> CashAdvanceBalanceRules.statusAfterBalance(inv.<BigDecimal>getArgument(1), inv.<Integer>getArgument(2)));
        lenient().when(cashAdvanceRepository.findById(advanceId)).thenReturn(java.util.Optional.of(advance));
        lenient().when(expenseReportRepository.findById(reportId)).thenReturn(java.util.Optional.of(report));
        lenient().when(adjustmentRepository.save(any(CashAdvanceAdjustment.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(adjustmentRepository.findAdvanceIdOf(any())).thenAnswer(inv -> java.util.Optional.of(advanceId));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private CashAdvanceAdjustment offset(BigDecimal amount, boolean reversed) {
        return CashAdvanceAdjustment.builder()
                .adjustmentId(UUID.randomUUID())
                .cashAdvance(advance)
                .report(report)
                .adjustedAmount(amount)
                .reversed(reversed)
                .build();
    }

    private CashAdvanceAdjustmentRequest request(BigDecimal amount) {
        return new CashAdvanceAdjustmentRequest(advanceId, reportId, amount, "FINANCE001", null);
    }

    // ------------------------------------------------------------------ reversal

    @Test
    void reverse_keepsTheRow_marksItReversed_recordsWhoWhenAndWhy_andRestoresTheBalance() {
        CashAdvanceAdjustment active = offset(new BigDecimal("300.00"), false);
        advance.setOutstandingBalance(new BigDecimal("700.00"));
        advance.setStatus("SETTLEMENT_PENDING");
        when(adjustmentRepository.findById(active.getAdjustmentId())).thenReturn(java.util.Optional.of(active));
        when(adjustmentRepository.findByCashAdvance_AdvanceId(advanceId)).thenReturn(List.of(active));

        CashAdvanceAdjustmentResponse response = service.reverse(active.getAdjustmentId(), "Receipt was for another employee");

        assertTrue(active.isReversed());
        assertEquals("FINANCE001", active.getReversedBy());
        assertNotNull(active.getReversedAt());
        assertEquals("Receipt was for another employee", active.getReversalReason());
        // original amount and links are preserved
        assertEquals(0, new BigDecimal("300.00").compareTo(active.getAdjustedAmount()));
        assertSame(advance, active.getCashAdvance());
        assertSame(report, active.getReport());
        // balance restored from the remaining active rows (none), so 1000
        assertEquals(0, new BigDecimal("1000.00").compareTo(advance.getOutstandingBalance()));
        // status is whatever the balance rule gives for a balance equal to the advance amount (never CLOSED)
        assertEquals(CashAdvanceBalanceRules.statusAfterBalance(new BigDecimal("1000.00"), 2), advance.getStatus());
        assertNotEquals("CLOSED", advance.getStatus());
        verify(cashAdvanceBudgetMovements).reverseOffset(advance, new BigDecimal("300.00"), report);
        verify(adjustmentRepository, never()).delete(any());
        assertNotNull(response);
    }

    @Test
    void reverse_recomputesFromRemainingActiveRows_notByAddingBack() {
        CashAdvanceAdjustment keep = offset(new BigDecimal("200.00"), false);
        CashAdvanceAdjustment drop = offset(new BigDecimal("300.00"), false);
        advance.setOutstandingBalance(new BigDecimal("500.00"));
        when(adjustmentRepository.findById(drop.getAdjustmentId())).thenReturn(java.util.Optional.of(drop));
        when(adjustmentRepository.findByCashAdvance_AdvanceId(advanceId)).thenReturn(List.of(keep, drop));

        service.reverse(drop.getAdjustmentId(), "Duplicate claim");

        // 1000 - 200 (still active) = 800
        assertEquals(0, new BigDecimal("800.00").compareTo(advance.getOutstandingBalance()));
    }

    @Test
    void reverse_takesTheAdvanceRowLockBeforeReadingBalances() {
        CashAdvanceAdjustment active = offset(new BigDecimal("300.00"), false);
        when(adjustmentRepository.findById(active.getAdjustmentId())).thenReturn(java.util.Optional.of(active));
        when(adjustmentRepository.findByCashAdvance_AdvanceId(advanceId)).thenReturn(List.of(active));

        service.reverse(active.getAdjustmentId(), "Wrong trip");

        InOrder order = inOrder(cashAdvanceRepository, adjustmentRepository);
        order.verify(cashAdvanceRepository).findByIdForUpdate(advanceId);
        order.verify(adjustmentRepository).findByCashAdvance_AdvanceId(advanceId);
    }

    @Test
    void reverse_twice_isRejected_andNothingChangesTheSecondTime() {
        CashAdvanceAdjustment already = offset(new BigDecimal("300.00"), true);
        when(adjustmentRepository.findById(already.getAdjustmentId())).thenReturn(java.util.Optional.of(already));

        BusinessRuleViolationException ex = assertThrows(BusinessRuleViolationException.class,
                () -> service.reverse(already.getAdjustmentId(), "again"));
        assertTrue(ex.getMessage().contains("already reversed"));
        verify(cashAdvanceBudgetMovements, never()).reverseOffset(any(), any(), any());
        verify(adjustmentRepository, never()).save(any());
    }

    @Test
    void reverse_withoutAReason_isRejected() {
        CashAdvanceAdjustment active = offset(new BigDecimal("300.00"), false);

        assertThrows(BusinessRuleViolationException.class, () -> service.reverse(active.getAdjustmentId(), "  "));
        assertThrows(BusinessRuleViolationException.class, () -> service.reverse(active.getAdjustmentId(), null));
        assertFalse(active.isReversed());
    }

    @Test
    void reverse_ofAnOffsetOnAPaidReport_isRejected_andTheRowIsUntouched() {
        report.setPaymentRoutingStatus(PaymentRoutingStatus.PAYMENT_COMPLETED);
        CashAdvanceAdjustment active = offset(new BigDecimal("300.00"), false);
        when(adjustmentRepository.findById(active.getAdjustmentId())).thenReturn(java.util.Optional.of(active));

        assertThrows(BusinessRuleViolationException.class, () -> service.reverse(active.getAdjustmentId(), "late"));
        assertFalse(active.isReversed());
        verify(adjustmentRepository, never()).save(any());
    }

    @Test
    void reverse_byAnEmployee_isRejected() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "EMP001", null, List.of(new SimpleGrantedAuthority("ROLE_GENERAL"))));
        CashAdvanceAdjustment active = offset(new BigDecimal("300.00"), false);

        assertThrows(BusinessRuleViolationException.class, () -> service.reverse(active.getAdjustmentId(), "mine"));
        assertFalse(active.isReversed());
    }

    // ------------------------------------------------------------------ reapplication and duplicates

    @Test
    void reapply_afterReversal_createsANewActiveRow_andKeepsTheReversedOne() {
        CashAdvanceAdjustment reversed = offset(new BigDecimal("300.00"), true);
        when(adjustmentRepository.findByCashAdvance_AdvanceId(advanceId)).thenReturn(List.of(reversed));

        CashAdvanceAdjustmentResponse response = service.create(request(new BigDecimal("250.00")));

        ArgumentCaptor<CashAdvanceAdjustment> saved = ArgumentCaptor.forClass(CashAdvanceAdjustment.class);
        verify(adjustmentRepository, atLeastOnce()).save(saved.capture());
        CashAdvanceAdjustment created = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertNotSame(reversed, created);
        assertFalse(created.isReversed());
        assertEquals(0, new BigDecimal("250.00").compareTo(created.getAdjustedAmount()));
        // the reversed row is untouched
        assertTrue(reversed.isReversed());
        assertEquals(0, new BigDecimal("300.00").compareTo(reversed.getAdjustedAmount()));
        // balance: 1000 - 250 (the reversed 300 does not count)
        assertEquals(0, new BigDecimal("750.00").compareTo(advance.getOutstandingBalance()));
        assertNotNull(response);
    }

    @Test
    void duplicateActiveOffset_forTheSameReport_isRejected_andNothingIsSaved() {
        CashAdvanceAdjustment active = offset(new BigDecimal("300.00"), false);
        when(adjustmentRepository.findByCashAdvance_AdvanceId(advanceId)).thenReturn(List.of(active));

        BusinessRuleViolationException ex = assertThrows(BusinessRuleViolationException.class,
                () -> service.create(request(new BigDecimal("100.00"))));
        assertTrue(ex.getMessage().contains("already been adjusted"));
        verify(adjustmentRepository, never()).save(any());
        assertEquals(0, new BigDecimal("1000.00").compareTo(advance.getOutstandingBalance()));
    }

    @Test
    void create_takesTheAdvanceRowLockBeforeReadingTheAdvance() {
        when(adjustmentRepository.findByCashAdvance_AdvanceId(advanceId)).thenReturn(new ArrayList<>());

        service.create(request(new BigDecimal("100.00")));

        InOrder order = inOrder(cashAdvanceRepository);
        order.verify(cashAdvanceRepository).findByIdForUpdate(advanceId);
        order.verify(cashAdvanceRepository).findById(advanceId);
    }

    @Test
    void create_revalidatesTheBalance_againstActiveRowsOnly() {
        // 900 active, plus a reversed 200 that must not count: only 100 is left
        CashAdvanceAdjustment active = offset(new BigDecimal("900.00"), false);
        CashAdvanceAdjustment reversed = offset(new BigDecimal("200.00"), true);
        ExpenseReport other = ExpenseReport.builder().reportId(UUID.randomUUID()).employeeId("EMP001")
                .reportStatus(ReportStatus.APPROVED).paymentRoutingStatus(PaymentRoutingStatus.NONE).build();
        active.setReport(other);
        when(adjustmentRepository.findByCashAdvance_AdvanceId(advanceId)).thenReturn(List.of(active, reversed));

        assertThrows(BusinessRuleViolationException.class, () -> service.create(request(new BigDecimal("150.00"))));
        // 100 is allowed
        service.create(request(new BigDecimal("100.00")));
        verify(adjustmentRepository, atLeastOnce()).save(any());
    }

    @Test
    void create_withAKeyUsedByAReversedOffset_returnsThatRecord_andAppliesNothingAgain() {
        CashAdvanceAdjustment reversed = offset(new BigDecimal("300.00"), true);
        reversed.setIdempotencyKey("key-1");
        when(adjustmentRepository.findByIdempotencyKey("key-1")).thenReturn(java.util.Optional.of(reversed));

        CashAdvanceAdjustmentRequest retry = new CashAdvanceAdjustmentRequest(advanceId, reportId, new BigDecimal("300.00"), "FINANCE001", "key-1");
        CashAdvanceAdjustmentResponse response = service.create(retry);

        assertNotNull(response);
        verify(adjustmentRepository, never()).save(any());
        assertEquals(0, new BigDecimal("1000.00").compareTo(advance.getOutstandingBalance()));
    }

    @Test
    void reversedOffsetsAreNotCountedAsVerified_inTheReportReimbursable() {
        CashAdvanceAdjustment reversed = offset(new BigDecimal("300.00"), true);
        List<CashAdvanceAdjustment> rows = new ArrayList<>(List.of(reversed));
        assertEquals(0, new BigDecimal("1000.00").compareTo(ReimbursementCalculator.reimbursable(new BigDecimal("1000.00"), rows)));
    }

    // ------------------------------------------------------------------ pending AP payment

    @Test
    void reversal_onAReportWaitingForAp_increasesWhatIsOwed_keepsItPayable_andAuditsTheChange() {
        report.setPaymentRoutingStatus(PaymentRoutingStatus.APPROVED_FOR_PAYMENT);
        report.setTotalAmount(new BigDecimal("1000.00"));
        report.setReimbursableAmount(new BigDecimal("200.00"));
        CashAdvanceAdjustment offset = offset(new BigDecimal("800.00"), false);
        when(adjustmentRepository.findById(offset.getAdjustmentId())).thenReturn(java.util.Optional.of(offset));
        when(adjustmentRepository.findByCashAdvance_AdvanceId(advanceId)).thenReturn(List.of(offset));
        when(adjustmentRepository.findByReport_ReportId(reportId)).thenReturn(List.of());

        service.reverse(offset.getAdjustmentId(), "Receipt belonged to another trip");

        assertEquals(0, new BigDecimal("1000.00").compareTo(report.getReimbursableAmount()), "AP now owes the full amount");
        assertEquals(PaymentRoutingStatus.APPROVED_FOR_PAYMENT, report.getPaymentRoutingStatus(), "still payable through AP");
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, atLeastOnce()).save(audit.capture());
        AuditLog recalculated = audit.getAllValues().stream()
                .filter(a -> "REIMBURSEMENT_RECALCULATED".equals(a.getAction())).findFirst().orElseThrow();
        assertTrue(recalculated.getOldValue().contains("200.00"), recalculated.getOldValue());
        assertTrue(recalculated.getNewValue().contains("1000.00"), recalculated.getNewValue());
        assertEquals("ExpenseReport", recalculated.getEntityName());
        assertEquals("Offset reversed: Receipt belonged to another trip", recalculated.getReason());
    }

    @Test
    void reversal_onAReportThatOwedNothing_returnsItToApForPayment() {
        report.setPaymentRoutingStatus(PaymentRoutingStatus.NONE);
        report.setNoPaymentDue(true);
        report.setTotalAmount(new BigDecimal("1000.00"));
        report.setReimbursableAmount(BigDecimal.ZERO);
        CashAdvanceAdjustment offset = offset(new BigDecimal("1000.00"), false);
        when(adjustmentRepository.findById(offset.getAdjustmentId())).thenReturn(java.util.Optional.of(offset));
        when(adjustmentRepository.findByCashAdvance_AdvanceId(advanceId)).thenReturn(List.of(offset));
        when(adjustmentRepository.findByReport_ReportId(reportId)).thenReturn(List.of());

        service.reverse(offset.getAdjustmentId(), "Expense was not covered");

        assertEquals(PaymentRoutingStatus.APPROVED_FOR_PAYMENT, report.getPaymentRoutingStatus());
        assertFalse(report.getNoPaymentDue());
    }

    @Test
    void reversal_onAPaidReport_isRefused_andTheReportIsNotRecalculated() {
        report.setPaymentRoutingStatus(PaymentRoutingStatus.PAYMENT_COMPLETED);
        CashAdvanceAdjustment offset = offset(new BigDecimal("800.00"), false);
        when(adjustmentRepository.findById(offset.getAdjustmentId())).thenReturn(java.util.Optional.of(offset));

        assertThrows(BusinessRuleViolationException.class, () -> service.reverse(offset.getAdjustmentId(), "late"));
        verify(expenseReportRepository, never()).save(any());
    }
}
