package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.dto.request.CashAdvanceAdjustmentRequest;
import com.expense_management_service.dto.response.CashAdvanceAdjustmentResponse;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceAdjustment;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.mapper.CashAdvanceAdjustmentMapper;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.CashAdvanceAdjustmentRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.ApprovalEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.AfterEach;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CashAdvanceAdjustmentServiceImplTest {

    @Mock
    private CashAdvanceAdjustmentRepository cashAdvanceAdjustmentRepository;

    @Mock
    private CashAdvanceRepository cashAdvanceRepository;

    @Mock
    private ExpenseReportRepository expenseReportRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private ApprovalEventPublisher approvalEventPublisher;

    @Spy
    private CashAdvanceAdjustmentMapper cashAdvanceAdjustmentMapper = new CashAdvanceAdjustmentMapper();

    @Mock
    private CurrentUserService currentUserService;

    @InjectMocks
    private CashAdvanceAdjustmentServiceImpl adjustmentService;

    private UUID advanceId;
    private UUID reportId;
    private UUID adjustmentId;
    private CashAdvance testAdvance;
    private ExpenseReport testReport;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void setUp() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "FINANCE001", null, List.of(new SimpleGrantedAuthority("ROLE_FINANCE_EXECUTIVE"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
        advanceId = UUID.randomUUID();
        reportId = UUID.randomUUID();
        adjustmentId = UUID.randomUUID();

        testAdvance = CashAdvance.builder()
                .advanceId(advanceId)
                .employeeId("EMP001")
                .amount(new BigDecimal("1000.00"))
                .outstandingBalance(new BigDecimal("1000.00"))
                .status("DISBURSED")
                .build();

        testReport = ExpenseReport.builder()
                .reportId(reportId)
                .employeeId("EMP001")
                .title("Trip Expenses")
                .reportStatus(ReportStatus.APPROVED)
                .build();
    }

    @Test
    void create_shouldPartiallySettleAdvanceWhenAdjustedAmountIsLessThanOutstanding() {
        CashAdvanceAdjustmentRequest request = new CashAdvanceAdjustmentRequest(
                advanceId,
                reportId,
                new BigDecimal("400.00"),
                "EMP001"
        );

        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(testAdvance));
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(testReport));
        when(cashAdvanceAdjustmentRepository.save(any(CashAdvanceAdjustment.class))).thenAnswer(inv -> {
            CashAdvanceAdjustment saved = inv.getArgument(0);
            saved.setAdjustmentId(adjustmentId);
            return saved;
        });

        CashAdvanceAdjustmentResponse response = adjustmentService.create(request);

        assertNotNull(response);
        assertEquals(new BigDecimal("600.00"), testAdvance.getOutstandingBalance());
        assertEquals("SETTLEMENT_PENDING", testAdvance.getStatus());
        verify(cashAdvanceRepository).save(testAdvance);
    }

    @Test
    void create_shouldFullySettleAdvanceWhenAdjustedAmountEqualsOutstanding() {
        CashAdvanceAdjustmentRequest request = new CashAdvanceAdjustmentRequest(
                advanceId,
                reportId,
                new BigDecimal("1000.00"),
                "EMP001"
        );

        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(testAdvance));
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(testReport));
        when(cashAdvanceAdjustmentRepository.save(any(CashAdvanceAdjustment.class))).thenAnswer(inv -> inv.getArgument(0));

        adjustmentService.create(request);

        assertEquals(0, testAdvance.getOutstandingBalance().compareTo(BigDecimal.ZERO));
        assertEquals("CLOSED", testAdvance.getStatus());
    }

    @Test
    void create_shouldThrowExceptionIfReportNotApproved() {
        testReport.setReportStatus(ReportStatus.SUBMITTED);
        CashAdvanceAdjustmentRequest request = new CashAdvanceAdjustmentRequest(
                advanceId,
                reportId,
                new BigDecimal("400.00"),
                "EMP001"
        );

        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(testAdvance));
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(testReport));

        assertThrows(BusinessRuleViolationException.class, () -> adjustmentService.create(request));
    }

    @Test
    void create_shouldThrowExceptionIfAdvanceNotDisbursed() {
        testAdvance.setStatus("DRAFT");
        CashAdvanceAdjustmentRequest request = new CashAdvanceAdjustmentRequest(
                advanceId,
                reportId,
                new BigDecimal("200.00"),
                "EMP001"
        );

        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(testAdvance));

        assertThrows(BusinessRuleViolationException.class, () -> adjustmentService.create(request));
    }

    @Test
    void create_shouldThrowExceptionIfAdjustedAmountExceedsOutstandingBalance() {
        CashAdvanceAdjustmentRequest request = new CashAdvanceAdjustmentRequest(
                advanceId,
                reportId,
                new BigDecimal("1500.00"),
                "EMP001"
        );

        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(testAdvance));
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(testReport));

        assertThrows(BusinessRuleViolationException.class, () -> adjustmentService.create(request));
    }
}
